from __future__ import annotations

from typing import Any

import pytest

from metrics.cache import InMemoryTTLCache
from metrics.errors import AppError, ProviderExecutionError, ProviderUnavailableError
from metrics.schemas.metrics import PlatformMetricsData
from metrics.services.platform_metrics import CachedMetrics, PlatformMetricsService
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
        load_platform=good,
        cache=InMemoryTTLCache[CachedMetrics](ttl_seconds=60),
        cache_key=_fixed_cache_key,
    )

    first = await service.get_platform_metrics()
    second = await service.get_platform_metrics()

    assert first.cached is False
    assert second.cached is True
    assert first.created == second.created
    assert first.data.capacity["cpu"] == "10"
    assert first.data.allocated["cpu"] == "5"


@pytest.mark.anyio
async def test_service_raises_unavailable() -> None:
    async def bad() -> PlatformMetricsData:
        raise ProviderUnavailableError("nope")

    service = PlatformMetricsService(
        load_platform=bad,
        cache=InMemoryTTLCache[CachedMetrics](ttl_seconds=60),
        cache_key=_fixed_cache_key,
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
        load_platform=good,
        cache=InMemoryTTLCache[CachedMetrics](ttl_seconds=60),
        cache_key=_fixed_cache_key,
        metrics_recorder=CaptureRecorder(),
        telemetry_provider_name="my-adapter",
    )
    await service.get_platform_metrics()
    assert recorded, "expected provider duration telemetry"
    assert recorded[0]["provider"] == "my-adapter"
    assert recorded[0]["scope"] == "platform"


@pytest.mark.anyio
async def test_service_raises_on_execution() -> None:
    async def bad() -> PlatformMetricsData:
        raise ProviderExecutionError("e")

    service = PlatformMetricsService(
        load_platform=bad,
        cache=InMemoryTTLCache[CachedMetrics](ttl_seconds=60),
        cache_key=_fixed_cache_key,
    )
    with pytest.raises(AppError) as ei:
        await service.get_platform_metrics()
    assert ei.value.status_code == 502
