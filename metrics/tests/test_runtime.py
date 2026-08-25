"""Runtime lifecycle and cache/service composition."""

from __future__ import annotations

import asyncio

import pytest

from metrics.cache import FRESHNESS_POLICIES, CacheIdentity, InMemoryCoordinator, RedisCoordinator
from metrics.core.runtime import MetricsRuntime, build_cache, platform_cache_identity
from metrics.core.settings import CacheConfig, Settings
from metrics.errors import RuntimeStartupError
from metrics.services.metrics import MetricsService
from metrics.services.models import PLATFORM_SUBJECT, CachedSnapshot, PlatformObservation
from metrics.telemetry import NoopMetricsRecorder
from tests.fakes import LifecycleProvider

pytestmark = pytest.mark.anyio


def _memory_cache() -> InMemoryCoordinator[CachedSnapshot]:
    return InMemoryCoordinator(
        policy=FRESHNESS_POLICIES["platform"],
        created=lambda snapshot: snapshot.created,
    )


def test_platform_cache_identity_preserves_source_dimensions() -> None:
    identity = platform_cache_identity(
        cluster_name="kind-metrics",
        source="kueue",
        fingerprint="abc123",
    )
    assert identity == CacheIdentity("platform", "canfar", "kind-metrics", "kueue", "abc123")


def test_runtime_wires_accounting_only_when_promql_is_enabled() -> None:
    recorder = NoopMetricsRecorder()
    core = MetricsRuntime.from_settings(
        Settings(cache=CacheConfig(backend="memory")),
        recorder=recorder,
    )
    assert core.accounting_service is None

    accounting = MetricsRuntime.from_settings(
        Settings.model_validate(
            {
                "cache": {"backend": "memory"},
                "providers": {"promql": {"enabled": True}},
            }
        ),
        recorder=recorder,
    )
    assert accounting.accounting_service is not None


class _RecordingRedis:
    def __init__(self, events: list[str]) -> None:
        self._events = events

    async def aclose(self) -> None:
        self._events.append("redis shutdown")


def _runtime_with(provider: LifecycleProvider, *, redis: _RecordingRedis | None = None):
    cache = _memory_cache()
    service = MetricsService(
        platform=provider.read_platform,
        cache=cache,
        identity=lambda: CacheIdentity("platform", "canfar", "c", "stub"),
        provider=provider.name,
    )
    return MetricsRuntime(
        Settings(cache=CacheConfig(backend="memory")),
        provider=provider,
        metrics_service=service,
        cache=cache,
        redis=redis,
    )


async def test_runtime_starts_and_stops_owned_provider_once() -> None:
    events: list[str] = []
    runtime = _runtime_with(LifecycleProvider(events))
    await runtime.start()
    await runtime.start()
    assert runtime.ready
    await runtime.shutdown()
    await runtime.shutdown()

    assert events == ["startup", "provider shutdown"]
    with pytest.raises(RuntimeError, match="not initialised"):
        _ = runtime.metrics_service


@pytest.mark.parametrize(
    ("startup_error", "expected_message"),
    [
        (RuntimeStartupError("invalid provider configuration"), "invalid provider configuration"),
        (ValueError("sensitive implementation detail"), "Unexpected error"),
    ],
)
async def test_runtime_startup_failure_cleans_up_and_sanitizes(
    startup_error: Exception,
    expected_message: str,
) -> None:
    events: list[str] = []
    runtime = _runtime_with(
        LifecycleProvider(events, startup_error=startup_error),
        redis=_RecordingRedis(events),
    )

    with pytest.raises(RuntimeStartupError) as error:
        await runtime.start()

    assert expected_message in str(error.value)
    assert "sensitive implementation detail" not in str(error.value)
    assert events == ["startup", "provider shutdown", "redis shutdown"]


@pytest.mark.parametrize("failing_hook", ["startup", "shutdown"])
async def test_runtime_cancellation_still_cleans_up_all_resources(failing_hook: str) -> None:
    events: list[str] = []
    error = asyncio.CancelledError()
    provider = LifecycleProvider(
        events,
        startup_error=error if failing_hook == "startup" else None,
        shutdown_error=error if failing_hook == "shutdown" else None,
    )
    runtime = _runtime_with(provider, redis=_RecordingRedis(events))

    with pytest.raises(asyncio.CancelledError):
        await runtime.start() if failing_hook == "startup" else await runtime.shutdown()

    assert events[-2:] == ["provider shutdown", "redis shutdown"]


async def test_runtime_shutdown_failure_does_not_skip_remaining_cleanup() -> None:
    events: list[str] = []
    runtime = _runtime_with(
        LifecycleProvider(events, shutdown_error=RuntimeError("boom")),
        redis=_RecordingRedis(events),
    )
    await runtime.shutdown()
    assert events == ["provider shutdown", "redis shutdown"]


async def test_metrics_service_reads_through_coordinator() -> None:
    loads = 0

    async def counting() -> PlatformObservation:
        nonlocal loads
        loads += 1
        return PlatformObservation(cluster="c", capacity={"cpu": "1"}, allocated={"cpu": "0"})

    service = MetricsService(
        platform=counting,
        cache=_memory_cache(),
        identity=lambda: CacheIdentity("platform", "canfar", "c", "stub"),
    )

    first = await service.get(PLATFORM_SUBJECT)
    second = await service.get(PLATFORM_SUBJECT)
    assert loads == 1
    assert first.cached is False
    assert second.cached is True


def test_build_cache_selects_test_memory_or_required_redis() -> None:
    memory, no_redis = build_cache(Settings(cache=CacheConfig(backend="memory")))
    assert isinstance(memory, InMemoryCoordinator)
    assert no_redis is None

    redis_cache, redis_client = build_cache(
        Settings(cache=CacheConfig(backend="redis", key_secret="x" * 32))
    )
    assert isinstance(redis_cache, RedisCoordinator)
    assert redis_client is not None
