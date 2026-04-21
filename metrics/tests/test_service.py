from __future__ import annotations

from datetime import UTC, datetime

import pytest

from metrics.cache import InMemoryTTLCache
from metrics.errors import AppError, ProviderUnavailableError
from metrics.models import CapacityReading, UsageReading
from metrics.service import CachedMetrics, PlatformMetricsService


class DummyCapacityProvider:
    source_name = "dummy_capacity"

    def __init__(self, reading: CapacityReading) -> None:
        self._reading = reading
        self.calls = 0

    async def get_capacity(self) -> CapacityReading:
        self.calls += 1
        return self._reading


class DummyUsageProvider:
    source_name = "dummy_usage"

    def __init__(self, reading: UsageReading) -> None:
        self._reading = reading
        self.calls = 0

    async def get_usage(self) -> UsageReading:
        self.calls += 1
        return self._reading

    async def get_usage_for_user(self, user_id: str) -> UsageReading:
        del user_id
        self.calls += 1
        return self._reading

    async def get_usage_for_session(
        self, user_id: str, session_id: str
    ) -> UsageReading:
        del user_id, session_id
        self.calls += 1
        return self._reading


@pytest.mark.anyio
async def test_service_returns_metrics_and_uses_cache() -> None:
    now = datetime.now(UTC)
    capacity = DummyCapacityProvider(
        CapacityReading(cpu_cores=10, memory_gib=20, source="kueue", observed_at=now)
    )
    usage = DummyUsageProvider(
        UsageReading(
            requested_cpu_cores=5,
            requested_memory_gib=10,
            source="prometheus",
            observed_at=now,
        )
    )

    service = PlatformMetricsService(
        cluster_name="prod",
        capacity_provider=capacity,
        usage_provider=usage,
        cache=InMemoryTTLCache[CachedMetrics](ttl_seconds=60),
    )

    first = await service.get_platform_metrics()
    second = await service.get_platform_metrics()

    assert first.cached is False
    assert second.cached is True
    assert first.created == second.created
    assert first.data.capacity["cpu"] == "10"
    assert first.data.capacity["memory"] == "20Gi"
    assert first.data.allocated["cpu"] == "5"
    assert first.data.allocated["memory"] == "10Gi"
    assert capacity.calls == 1
    assert usage.calls == 1


@pytest.mark.anyio
async def test_platform_static_allocated_reflects_usage_without_clamping() -> None:
    now = datetime.now(UTC)
    capacity = DummyCapacityProvider(
        CapacityReading(cpu_cores=10, memory_gib=20, source="kueue", observed_at=now)
    )
    usage = DummyUsageProvider(
        UsageReading(
            requested_cpu_cores=50,
            requested_memory_gib=40,
            source="prometheus",
            observed_at=now,
        )
    )

    service = PlatformMetricsService(
        cluster_name="prod",
        capacity_provider=capacity,
        usage_provider=usage,
        cache=InMemoryTTLCache[CachedMetrics](ttl_seconds=60),
    )

    result = await service.get_platform_metrics()
    assert result.data.allocated["cpu"] == "50"
    assert result.data.allocated["memory"] == "40Gi"


@pytest.mark.anyio
async def test_service_raises_when_capacity_unavailable() -> None:
    now = datetime.now(UTC)

    class BadCapacityProvider:
        source_name = "bad"

        async def get_capacity(self) -> CapacityReading:
            raise ProviderUnavailableError("no capacity")

    usage = DummyUsageProvider(
        UsageReading(
            requested_cpu_cores=1,
            requested_memory_gib=1,
            source="prom",
            observed_at=now,
        )
    )

    service = PlatformMetricsService(
        cluster_name="prod",
        capacity_provider=BadCapacityProvider(),
        usage_provider=usage,
        cache=InMemoryTTLCache[CachedMetrics](ttl_seconds=0),
    )

    with pytest.raises(AppError) as exc_info:
        await service.get_platform_metrics()

    assert exc_info.value.code == "capacity_unavailable"
    assert exc_info.value.status_code == 503


@pytest.mark.anyio
async def test_service_returns_user_and_session_metrics() -> None:
    now = datetime.now(UTC)
    capacity = DummyCapacityProvider(
        CapacityReading(cpu_cores=12, memory_gib=48, source="kueue", observed_at=now)
    )
    usage = DummyUsageProvider(
        UsageReading(
            requested_cpu_cores=3,
            requested_memory_gib=9,
            source="prometheus",
            observed_at=now,
        )
    )
    service = PlatformMetricsService(
        cluster_name="prod",
        capacity_provider=capacity,
        usage_provider=usage,
        cache=InMemoryTTLCache[CachedMetrics](ttl_seconds=60),
    )

    user_result = await service.get_user_metrics("alice")
    session_result = await service.get_session_metrics(
        user_id="alice",
        session_id="sess-1",
    )

    assert user_result.data.user_id == "alice"
    assert user_result.data.capacity.cpu == "12"
    assert user_result.data.usage.requested.memory == "9 GiB"
    assert user_result.data.usage.utilization.cpu == 0.25
    assert user_result.cached is False

    assert session_result.data.user_id == "alice"
    assert session_result.data.session_id == "sess-1"
    assert session_result.cached is False
