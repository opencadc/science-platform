from __future__ import annotations

import asyncio
from typing import Any

import pytest

from metrics.cache import InMemoryTTLCache
from metrics.errors import AppError, ProviderExecutionError, ProviderUnavailableError
from metrics.schemas.metrics import PlatformMetricsData
from metrics.services.platform import CachedMetrics, PlatformMetricsService
from metrics.telemetry import MetricsRecorder


def _fixed_cache_key() -> str:
    return "platform:4:testcluster:"


@pytest.mark.anyio
async def test_service_returns_platform_metrics_and_uses_cache() -> None:
    async def good() -> PlatformMetricsData:
        return PlatformMetricsData(
            cluster="prod",
            capacity={"cpu": "10", "memory": "20Gi"},
            allocated={"cpu": "5", "memory": "10Gi"},
        )

    service = PlatformMetricsService(
        platform=good,
        cache=InMemoryTTLCache[CachedMetrics](ttl_seconds=60),
        key=_fixed_cache_key,
    )

    first = await service.get_platform_metrics()
    second = await service.get_platform_metrics()

    assert first.cached is False
    assert second.cached is True
    assert first.created == second.created
    assert first.data.capacity["cpu"] == "10"
    assert first.data.allocated["cpu"] == "5"


@pytest.mark.anyio
async def test_concurrent_misses_coalesce_to_one_backend_load() -> None:
    loads = 0
    release = asyncio.Event()

    async def counting() -> PlatformMetricsData:
        nonlocal loads
        loads += 1
        await release.wait()
        return PlatformMetricsData(cluster="c", capacity={"cpu": "1"}, allocated={"cpu": "0"})

    service = PlatformMetricsService(
        platform=counting,
        cache=InMemoryTTLCache[CachedMetrics](ttl_seconds=60),
        key=_fixed_cache_key,
    )

    tasks = [asyncio.create_task(service.get_platform_metrics()) for _ in range(10)]
    await asyncio.sleep(0)  # let every request reach the miss path
    release.set()
    results = await asyncio.gather(*tasks)

    assert loads == 1
    assert all(r.data.capacity["cpu"] == "1" for r in results)
    assert all(r.cached is False for r in results)
    # Follow-up call is now a plain cache hit.
    assert (await service.get_platform_metrics()).cached is True


@pytest.mark.anyio
async def test_concurrent_misses_share_the_same_mapped_error() -> None:
    loads = 0

    async def failing() -> PlatformMetricsData:
        nonlocal loads
        loads += 1
        await asyncio.sleep(0)
        raise ProviderUnavailableError("down")

    service = PlatformMetricsService(
        platform=failing,
        cache=InMemoryTTLCache[CachedMetrics](ttl_seconds=60),
        key=_fixed_cache_key,
    )

    tasks = [asyncio.create_task(service.get_platform_metrics()) for _ in range(5)]
    await asyncio.sleep(0)
    results = await asyncio.gather(*tasks, return_exceptions=True)

    assert loads == 1
    assert all(isinstance(r, AppError) and r.status_code == 503 for r in results)


@pytest.mark.anyio
async def test_cancelling_one_waiter_keeps_the_shared_load_alive() -> None:
    loads = 0
    release = asyncio.Event()

    async def counting() -> PlatformMetricsData:
        nonlocal loads
        loads += 1
        await release.wait()
        return PlatformMetricsData(cluster="c", capacity={"cpu": "1"}, allocated={"cpu": "0"})

    service = PlatformMetricsService(
        platform=counting,
        cache=InMemoryTTLCache[CachedMetrics](ttl_seconds=60),
        key=_fixed_cache_key,
    )

    first = asyncio.create_task(service.get_platform_metrics())
    second = asyncio.create_task(service.get_platform_metrics())
    await asyncio.sleep(0)
    first.cancel()
    await asyncio.gather(first, return_exceptions=True)
    release.set()

    result = await second
    assert result.data.capacity["cpu"] == "1"
    assert loads == 1


@pytest.mark.anyio
async def test_service_raises_unavailable() -> None:
    async def bad() -> PlatformMetricsData:
        raise ProviderUnavailableError("nope")

    service = PlatformMetricsService(
        platform=bad,
        cache=InMemoryTTLCache[CachedMetrics](ttl_seconds=60),
        key=_fixed_cache_key,
    )
    with pytest.raises(AppError) as ei:
        await service.get_platform_metrics()
    assert ei.value.status_code == 503


@pytest.mark.anyio
async def test_service_telemetry_uses_telemetry_provider_name() -> None:
    """Seam: :class:`PlatformMetricsService` records the injected provider name, not a constant."""

    recorded: list[dict[str, Any]] = []

    class CaptureRecorder(MetricsRecorder):
        def record_cache_lookup(self, *, backend: str, hit: bool, scope: str) -> None:
            return

        def record_http_request(
            self,
            *,
            scope: str,
            status_code: int,
            cached: bool,
        ) -> None:
            return

        def record_compute_duration(self, *, seconds: float, status: str, scope: str) -> None:
            return

        def record_provider_duration(
            self,
            *,
            provider: str,
            scope: str,
            status: str,
            seconds: float,
        ) -> None:
            recorded.append(
                {
                    "provider": provider,
                    "scope": scope,
                    "status": status,
                    "seconds": seconds,
                }
            )

    async def good() -> PlatformMetricsData:
        return PlatformMetricsData(
            cluster="c",
            capacity={},
            allocated={},
        )

    service = PlatformMetricsService(
        platform=good,
        cache=InMemoryTTLCache[CachedMetrics](ttl_seconds=60),
        key=_fixed_cache_key,
        telemetry=CaptureRecorder(),
        provider="my-adapter",
    )
    await service.get_platform_metrics()
    assert recorded, "expected provider duration telemetry"
    assert recorded[0]["provider"] == "my-adapter"
    assert recorded[0]["scope"] == "platform"


@pytest.mark.anyio
async def test_service_raises_on_execution() -> None:
    async def bad() -> PlatformMetricsData:
        raise ProviderExecutionError(
            "bad-secret-value from https://kube.test containing implementation details"
        )

    service = PlatformMetricsService(
        platform=bad,
        cache=InMemoryTTLCache[CachedMetrics](ttl_seconds=60),
        key=_fixed_cache_key,
    )
    with pytest.raises(AppError) as ei:
        await service.get_platform_metrics()
    assert ei.value.status_code == 502
    assert ei.value.message == "Platform metrics collection failed"
    assert "bad-secret-value" not in ei.value.message
    assert "kube.test" not in ei.value.message
