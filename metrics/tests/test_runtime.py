"""Runtime lifecycle, registry binding, single-flight service, and cache backends."""

from __future__ import annotations

import asyncio
import json

import pytest
from redis.exceptions import RedisError

from metrics.cache import InMemoryTTLCache, RedisJSONTTLCache
from metrics.core.runtime import MetricsRuntime, build_cache_backend, platform_metrics_cache_key
from metrics.core.settings import CacheConfig, Settings
from metrics.errors import RuntimeStartupError
from metrics.schemas.metrics import PlatformMetricsData
from metrics.services.platform import CachedMetrics, PlatformMetricsService
from metrics.telemetry import NoopMetricsRecorder
from tests.fakes import LifecycleProvider

pytestmark = pytest.mark.anyio

# --- registry ---


def test_platform_cache_key_preserves_scope_schema_cluster_and_fingerprint() -> None:
    key = platform_metrics_cache_key(cluster_name="kind-metrics", fingerprint="abc123")
    assert key == "platform:4:kind-metrics:abc123"


# --- runtime lifecycle ---


class _RecordingRedis:
    def __init__(self, events: list[str]) -> None:
        self._events = events

    async def aclose(self) -> None:
        self._events.append("redis shutdown")


def _runtime_with(provider: LifecycleProvider, *, redis: _RecordingRedis | None = None):
    service = PlatformMetricsService(
        platform=provider.platform,
        cache=InMemoryTTLCache[CachedMetrics](ttl_seconds=60),
        key=lambda: "platform:4:c:stub",
        telemetry=NoopMetricsRecorder(),
        provider=provider.name,
    )
    return MetricsRuntime(
        Settings(cache=CacheConfig(backend="memory")),
        provider=provider,
        platform_service=service,
        redis=redis,
    )


async def test_runtime_starts_and_stops_owned_provider_once() -> None:
    events: list[str] = []
    runtime = _runtime_with(LifecycleProvider(events))
    await runtime.start()
    await runtime.start()
    await runtime.shutdown()
    await runtime.shutdown()

    assert events == ["startup", "provider shutdown"]
    with pytest.raises(RuntimeError, match="not initialised"):
        _ = runtime.platform_service


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


# --- single-flight platform service ---


async def test_concurrent_misses_coalesce_and_share_results_even_when_cancelled() -> None:
    loads = 0
    release = asyncio.Event()
    provider_names: list[str] = []

    class CaptureRecorder(NoopMetricsRecorder):
        def record_provider_duration(
            self, *, provider: str, scope: str, status: str, seconds: float
        ) -> None:
            provider_names.append(provider)

    async def counting() -> PlatformMetricsData:
        nonlocal loads
        loads += 1
        await release.wait()
        return PlatformMetricsData(cluster="c", capacity={"cpu": "1"}, allocated={"cpu": "0"})

    service = PlatformMetricsService(
        platform=counting,
        cache=InMemoryTTLCache[CachedMetrics](ttl_seconds=60),
        key=lambda: "platform:4:c:",
        telemetry=CaptureRecorder(),
        provider="my-adapter",
    )

    tasks = [asyncio.create_task(service.get_platform_metrics()) for _ in range(10)]
    await asyncio.sleep(0)  # let every request reach the miss path
    tasks[0].cancel()  # cancelling one waiter must not kill the shared load
    await asyncio.gather(tasks[0], return_exceptions=True)
    release.set()
    results = await asyncio.gather(*tasks[1:])

    assert loads == 1
    assert all(r.data.capacity["cpu"] == "1" and r.cached is False for r in results)
    assert (await service.get_platform_metrics()).cached is True
    assert provider_names == ["my-adapter"]  # one timed load, injected provider name


async def test_concurrent_misses_share_the_same_mapped_error() -> None:
    from metrics.errors import AppError, ProviderUnavailableError

    loads = 0

    async def failing() -> PlatformMetricsData:
        nonlocal loads
        loads += 1
        await asyncio.sleep(0)
        raise ProviderUnavailableError("down")

    service = PlatformMetricsService(
        platform=failing,
        cache=InMemoryTTLCache[CachedMetrics](ttl_seconds=60),
        key=lambda: "platform:4:c:",
    )

    tasks = [asyncio.create_task(service.get_platform_metrics()) for _ in range(5)]
    await asyncio.sleep(0)
    results = await asyncio.gather(*tasks, return_exceptions=True)

    assert loads == 1
    assert all(isinstance(r, AppError) and r.status_code == 503 for r in results)


# --- cache backends ---


class FakeRedis:
    def __init__(self, *, fail_get: bool = False, fail_set: bool = False) -> None:
        self._values: dict[str, str] = {}
        self._fail_get = fail_get
        self._fail_set = fail_set

    async def get(self, key: str) -> str | None:
        if self._fail_get:
            raise RedisError("get failed")
        return self._values.get(key)

    async def set(self, key: str, value: str, *, ex: int) -> None:
        if self._fail_set:
            raise RedisError("set failed")
        self._values[key] = value


def _redis_cache(fake: FakeRedis) -> RedisJSONTTLCache[int]:
    return RedisJSONTTLCache[int](
        ttl_seconds=30,
        redis=fake,
        key_prefix="metrics:",
        serializer=lambda value: json.dumps({"value": value}),
        deserializer=lambda payload: json.loads(payload)["value"],
    )


async def test_memory_cache_expires_and_redis_cache_round_trips_and_degrades() -> None:
    memory = InMemoryTTLCache[int](ttl_seconds=60)
    await memory.set("k", 1)
    assert await memory.get("k") == 1

    expiring = InMemoryTTLCache[int](ttl_seconds=0)
    await expiring.set("k", 1)
    await asyncio.sleep(0.005)  # entry is stale as soon as the clock advances
    assert await expiring.get("k") is None

    healthy = _redis_cache(FakeRedis())
    await healthy.set("example", 42)
    assert await healthy.get("example") == 42

    # Redis failures degrade to cache misses instead of surfacing errors.
    assert await _redis_cache(FakeRedis(fail_get=True)).get("example") is None
    degraded = _redis_cache(FakeRedis(fail_set=True))
    await degraded.set("example", 42)
    assert await degraded.get("example") is None


def test_build_cache_backend_selects_configured_backend() -> None:
    memory_cache, no_redis = build_cache_backend(
        Settings(cache=CacheConfig(backend="memory", ttl_seconds=10))
    )
    assert isinstance(memory_cache, InMemoryTTLCache)
    assert no_redis is None

    redis_cache, redis_client = build_cache_backend(
        Settings(cache=CacheConfig(backend="redis", ttl_seconds=10))
    )
    assert isinstance(redis_cache, RedisJSONTTLCache)
    assert redis_client is not None
