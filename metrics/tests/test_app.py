from __future__ import annotations

from datetime import UTC, datetime

from fastapi.testclient import TestClient

from metrics.app import create_app
from metrics.cache import InMemoryTTLCache
from metrics.models import CapacityReading, UsageReading
from metrics.service import CachedMetrics, PlatformMetricsService


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

    async def get_usage_for_session(self, user_id: str, session_id: str) -> UsageReading:
        del user_id, session_id
        return UsageReading(
            requested_cpu_cores=2,
            requested_memory_gib=4,
            source=f"{self.source_name}:session",
            observed_at=datetime.now(UTC),
        )


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
    assert response.headers["cache-control"] == "public, max-age=30"
    assert response.headers["x-metrics-cached"] == "false"
    payload = response.json()
    assert payload["version"] == "metrics.canfar.net/v1"
    assert payload["kind"] == "PlatformMetrics"
    assert payload["metadata"]["created"] is not None
    assert payload["metadata"]["cached"] is False
    assert payload["metadata"]["ttl"] == 30
    assert payload["data"]["cluster"] == "prod"
    assert payload["data"]["capacity"]["cpu"] == "100"
    assert payload["data"]["capacity"]["memory"] == "200 GiB"
    assert payload["data"]["capacity"]["ephemeral-memory"] == "0 GiB"
    assert payload["data"]["capacity"]["gpu"] == "0"
    assert payload["data"]["usage"]["requested"]["cpu"] == "25"
    assert payload["data"]["usage"]["requested"]["ephemeral-memory"] == "0 GiB"
    assert payload["data"]["usage"]["requested"]["gpu"] == "0"
    assert payload["data"]["usage"]["utilization"]["cpu"] == 0.25
    assert payload["data"]["usage"]["utilization"]["memory"] == 0.25
    assert payload["data"]["usage"]["utilization"]["ephemeral-memory"] == 0.0
    assert payload["data"]["usage"]["utilization"]["gpu"] == 0.0
    assert payload["data"]["sources"] == ["kueue", "prometheus"]

    cached_response = client.get("/api/v1/metrics/platform")
    assert cached_response.headers["x-metrics-cached"] == "true"

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
    assert user_payload["metadata"]["cached"] is False
    assert user_payload["metadata"]["ttl"] == 30
    assert user_payload["data"]["user_id"] == "user-123"
    assert user_payload["data"]["capacity"]["cpu"] == "100"
    assert user_payload["data"]["usage"]["requested"]["cpu"] == "5"
    assert user_response.headers["cache-control"] == "public, max-age=30"

    session_response = client.get("/api/v1/metrics/users/user-123/sessions/session-456")
    assert session_response.status_code == 200
    session_payload = session_response.json()
    assert session_payload["kind"] == "SessionMetrics"
    assert session_payload["status"] == "Success"
    assert session_payload["metadata"]["created"] is not None
    assert session_payload["metadata"]["cached"] is False
    assert session_payload["metadata"]["ttl"] == 30
    assert session_payload["data"]["session_id"] == "session-456"
    assert session_payload["data"]["usage"]["requested"]["cpu"] == "2"
