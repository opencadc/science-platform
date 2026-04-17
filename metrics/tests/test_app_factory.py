from __future__ import annotations

from datetime import UTC, datetime

from fastapi.testclient import TestClient

import metrics.app as app_module
from metrics.app import _build_cache_backend, create_app
from metrics.cache import InMemoryTTLCache, RedisJSONTTLCache
from metrics.config import Settings
from metrics.models import CapacityReading, UsageReading
from metrics.service import CachedMetrics, PlatformMetricsService
from metrics.telemetry import NoopMetricsRecorder, TelemetrySetup


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
        return await self.get_usage()

    async def get_usage_for_session(self, user_id: str, session_id: str) -> UsageReading:
        del user_id, session_id
        return await self.get_usage()


class FakeMeterProvider:
    def __init__(self) -> None:
        self.shutdown_calls = 0

    def shutdown(self) -> None:
        self.shutdown_calls += 1


def _service() -> PlatformMetricsService:
    return PlatformMetricsService(
        cluster_name="prod",
        capacity_provider=CapacityProvider(),
        usage_provider=UsageProvider(),
        cache=InMemoryTTLCache[CachedMetrics](ttl_seconds=30),
    )


def test_build_cache_backend_memory() -> None:
    cache, redis_client = _build_cache_backend(
        Settings(cache_backend="memory", cache_ttl_seconds=10)
    )
    assert isinstance(cache, InMemoryTTLCache)
    assert redis_client is None


def test_build_cache_backend_redis() -> None:
    cache, redis_client = _build_cache_backend(
        Settings(
            cache_backend="redis",
            cache_ttl_seconds=10,
            redis_url="redis://localhost:6379/0",
            redis_key_prefix="metrics:",
        )
    )
    assert isinstance(cache, RedisJSONTTLCache)
    assert redis_client is not None


def test_create_app_configures_otel_and_shutdown_hooks(monkeypatch) -> None:
    calls = {
        "fastapi_instrument": 0,
        "fastapi_uninstrument": 0,
        "httpx_instrument": 0,
        "httpx_uninstrument": 0,
    }
    meter_provider = FakeMeterProvider()

    def fake_setup_telemetry(settings: Settings) -> TelemetrySetup:
        del settings
        return TelemetrySetup(
            recorder=NoopMetricsRecorder(),
            meter_provider=meter_provider,
        )

    class FakeFastAPIInstrumentor:
        @classmethod
        def instrument_app(cls, app, **kwargs):
            del cls, app, kwargs
            calls["fastapi_instrument"] += 1

        @classmethod
        def uninstrument_app(cls, app):
            del cls, app
            calls["fastapi_uninstrument"] += 1

    class FakeHTTPXClientInstrumentor:
        def instrument(self):
            calls["httpx_instrument"] += 1

        def uninstrument(self):
            calls["httpx_uninstrument"] += 1

    monkeypatch.setattr(app_module, "setup_telemetry", fake_setup_telemetry)
    monkeypatch.setattr(app_module, "FastAPIInstrumentor", FakeFastAPIInstrumentor)
    monkeypatch.setattr(
        app_module,
        "HTTPXClientInstrumentor",
        FakeHTTPXClientInstrumentor,
    )

    with TestClient(
        create_app(
            settings=Settings(otel_metrics_enabled=True, cache_backend="memory"),
            platform_service=_service(),
        )
    ) as client:
        response = client.get("/api/v1/metrics/platform")
        assert response.status_code == 200

    assert calls["fastapi_instrument"] == 1
    assert calls["fastapi_uninstrument"] == 1
    assert calls["httpx_instrument"] == 1
    assert calls["httpx_uninstrument"] == 1
    assert meter_provider.shutdown_calls == 1


def test_create_app_static_provider_mode_without_live_dependencies() -> None:
    app = create_app(
        settings=Settings(
            provider_mode="static",
            cache_backend="memory",
            prometheus_url=None,
            kube_api_url=None,
        )
    )
    with TestClient(app) as client:
        response = client.get("/api/v1/metrics/platform")
        assert response.status_code == 200
        assert response.json()["data"]["sources"][0] == "static-capacity"
