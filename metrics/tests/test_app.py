from __future__ import annotations

import re
import time
from datetime import UTC, datetime

from fastapi.testclient import TestClient

from metrics.cache import InMemoryTTLCache
from metrics.core.factory import create_app
from metrics.schemas.metrics import CapacityReading, UsageReading
from metrics.services.platform_metrics import CachedMetrics, PlatformMetricsService


class CapacityProvider:
    source_name = "kueue"

    async def get_capacity(self) -> CapacityReading:
        return CapacityReading(
            cpu_cores=100,
            memory_gib=200,
            source=self.source_name,
            observed_at=datetime.now(UTC),
        )


class UsageProvider:
    source_name = "prometheus"

    async def get_usage(self) -> UsageReading:
        return UsageReading(
            requested_cpu_cores=25,
            requested_memory_gib=50,
            source=self.source_name,
            observed_at=datetime.now(UTC),
        )

    async def get_usage_for_user(self, user_id: str) -> UsageReading:
        del user_id
        return UsageReading(
            requested_cpu_cores=5,
            requested_memory_gib=10,
            source=f"{self.source_name}:user",
            observed_at=datetime.now(UTC),
        )

    async def get_usage_for_session(
        self, user_id: str, session_id: str
    ) -> UsageReading:
        del user_id, session_id
        return UsageReading(
            requested_cpu_cores=2,
            requested_memory_gib=4,
            source=f"{self.source_name}:session",
            observed_at=datetime.now(UTC),
        )


def _max_age(cache_control: str) -> int:
    m = re.search(r"max-age=(\d+)", cache_control.lower())
    assert m is not None
    return int(m.group(1))


def test_platform_endpoint_and_alias() -> None:
    service = PlatformMetricsService(
        cluster_name="prod",
        capacity_provider=CapacityProvider(),
        usage_provider=UsageProvider(),
        cache=InMemoryTTLCache[CachedMetrics](ttl_seconds=30),
    )
    client = TestClient(create_app(platform_service=service))

    response = client.get("/api/v1/metrics/platform")
    assert response.status_code == 200
    cc1 = response.headers["cache-control"]
    assert "public" in cc1
    ma1 = _max_age(cc1)
    assert 25 <= ma1 <= 30
    assert response.headers.get("date")
    assert response.headers.get("last-modified")
    assert response.headers.get("expires")
    assert "x-metrics-cached" not in {h.lower() for h in response.headers}
    payload = response.json()
    assert payload["version"] == "metrics.canfar.net/v1"
    assert payload["kind"] == "PlatformMetrics"
    assert payload["metadata"]["created"] is not None
    assert "cached" not in payload["metadata"]
    assert "ttl" not in payload["metadata"]
    assert payload["data"]["cluster"] == "prod"
    assert payload["data"]["capacity"]["cpu"] == "100"
    assert payload["data"]["capacity"]["memory"] == "200Gi"
    assert payload["data"]["allocated"]["cpu"] == "25"
    assert payload["data"]["allocated"]["memory"] == "50Gi"

    time.sleep(1.1)
    cached_response = client.get("/api/v1/metrics/platform")
    ma2 = _max_age(cached_response.headers["cache-control"])
    assert ma2 < ma1

    alias_response = client.get("/metrics")
    assert alias_response.status_code == 200


def test_user_and_session_routes() -> None:
    service = PlatformMetricsService(
        cluster_name="prod",
        capacity_provider=CapacityProvider(),
        usage_provider=UsageProvider(),
        cache=InMemoryTTLCache[CachedMetrics](ttl_seconds=30),
    )
    client = TestClient(create_app(platform_service=service))

    user_response = client.get("/api/v1/metrics/users/user-123")
    assert user_response.status_code == 200
    user_payload = user_response.json()
    assert user_payload["kind"] == "UserMetrics"
    assert user_payload["status"] == "Success"
    assert user_payload["metadata"]["created"] is not None
    assert "cached" not in user_payload["metadata"]
    assert "ttl" not in user_payload["metadata"]
    assert user_payload["data"]["user_id"] == "user-123"
    assert user_payload["data"]["capacity"]["cpu"] == "100"
    assert user_payload["data"]["usage"]["requested"]["cpu"] == "5"
    ucc = user_response.headers["cache-control"]
    assert "private" in ucc
    assert 25 <= _max_age(ucc) <= 30

    session_response = client.get("/api/v1/metrics/users/user-123/sessions/session-456")
    assert session_response.status_code == 200
    session_payload = session_response.json()
    assert session_payload["kind"] == "SessionMetrics"
    assert session_payload["status"] == "Success"
    assert session_payload["metadata"]["created"] is not None
    assert "cached" not in session_payload["metadata"]
    assert "ttl" not in session_payload["metadata"]
    assert session_payload["data"]["session_id"] == "session-456"
    assert session_payload["data"]["usage"]["requested"]["cpu"] == "2"
    scc = session_response.headers["cache-control"]
    assert "private" in scc
    assert 25 <= _max_age(scc) <= 30
