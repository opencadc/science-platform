"""Provider test doubles and runtime lifecycle paths."""

from __future__ import annotations

import asyncio

import pytest

from metrics.cache import InMemoryTTLCache
from metrics.core.runtime import MetricsRuntime, platform_metrics_cache_key
from metrics.core.settings import CacheConfig, Settings
from metrics.errors import RuntimeStartupError
from metrics.services.platform import CachedMetrics, PlatformMetricsService
from metrics.telemetry import NoopMetricsRecorder
from tests.fakes import LifecycleProvider


def test_platform_cache_key_preserves_scope_schema_cluster_and_fingerprint() -> None:
    assert (
        platform_metrics_cache_key(
            cluster_name="kind-metrics",
            fingerprint="abc123",
        )
        == "platform:4:kind-metrics:abc123"
    )


class _RecordingRedis:
    def __init__(self, events: list[str]) -> None:
        self._events = events

    async def aclose(self) -> None:
        self._events.append("redis shutdown")


def _runtime_with(
    provider: LifecycleProvider,
    *,
    redis: _RecordingRedis | None = None,
) -> MetricsRuntime:
    service = PlatformMetricsService(
        platform=provider.platform,
        cache=InMemoryTTLCache[CachedMetrics](ttl_seconds=60),
        key=lambda: "platform:4:c:stub",
        telemetry=NoopMetricsRecorder(),
        provider=provider.name,
    )
    runtime = MetricsRuntime(Settings(cache=CacheConfig(backend="memory")))
    runtime.wire(
        provider=provider,
        platform_service=service,
        redis=redis,
    )
    return runtime


@pytest.mark.anyio
async def test_metrics_runtime_starts_and_stops_owned_provider_once() -> None:
    events: list[str] = []
    runtime = _runtime_with(LifecycleProvider(events))
    await runtime.start()
    await runtime.start()
    await runtime.shutdown()
    await runtime.shutdown()

    assert events == ["startup", "provider shutdown"]
    with pytest.raises(RuntimeError, match="not initialised"):
        _ = runtime.platform_service
    with pytest.raises(RuntimeError, match="not initialised"):
        await runtime.get_platform_metrics()


@pytest.mark.anyio
@pytest.mark.parametrize(
    ("startup_error", "expected_message"),
    [
        (RuntimeStartupError("invalid provider configuration"), "invalid provider configuration"),
        (ValueError("sensitive implementation detail"), "Unexpected error"),
    ],
)
async def test_metrics_runtime_startup_failure_cleans_up_and_sanitizes(
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
    await runtime.shutdown()
    assert events == ["startup", "provider shutdown", "redis shutdown"]


@pytest.mark.anyio
async def test_metrics_runtime_shutdown_failure_does_not_skip_remaining_cleanup() -> None:
    events: list[str] = []
    runtime = _runtime_with(
        LifecycleProvider(events, shutdown_error=RuntimeError("boom")),
        redis=_RecordingRedis(events),
    )

    await runtime.shutdown()

    assert events == ["provider shutdown", "redis shutdown"]


@pytest.mark.anyio
async def test_metrics_runtime_cancellation_cleans_up_remaining_resources() -> None:
    events: list[str] = []
    runtime = _runtime_with(
        LifecycleProvider(events, shutdown_error=asyncio.CancelledError()),
        redis=_RecordingRedis(events),
    )

    with pytest.raises(asyncio.CancelledError):
        await runtime.shutdown()

    assert events == ["provider shutdown", "redis shutdown"]


@pytest.mark.anyio
async def test_metrics_runtime_startup_cancellation_cleans_up_resources() -> None:
    events: list[str] = []
    runtime = _runtime_with(
        LifecycleProvider(events, startup_error=asyncio.CancelledError()),
        redis=_RecordingRedis(events),
    )

    with pytest.raises(asyncio.CancelledError):
        await runtime.start()

    assert events == ["startup", "provider shutdown", "redis shutdown"]


