"""App factory, OpenTelemetry wiring, and HTTP route behavior (TestClient)."""

from __future__ import annotations

import time

from fastapi.testclient import TestClient

import metrics.core.factory as factory_module
from metrics.cache import InMemoryTTLCache, RedisJSONTTLCache
from metrics.core.factory import create_app
from metrics.core.runtime import build_cache_backend
from metrics.core.settings import CacheConfig, Settings
from metrics.services.platform_metrics import CachedMetrics, PlatformMetricsService
from metrics.telemetry import NoopMetricsRecorder, TelemetrySetup

from tests.fakes import (
    StubPlatformMetrics,
    cache_control_max_age,
)


def _service() -> PlatformMetricsService:
    stub = StubPlatformMetrics()

    def cache_key() -> str:
        return f"platform:4:{stub.cluster}:"

    return PlatformMetricsService(
        load_platform=stub.load,
        cache=InMemoryTTLCache[CachedMetrics](ttl_seconds=30),
        cache_key=cache_key,
    )


def test_platform_endpoint() -> None:
    service = _service()
    with TestClient(create_app(platform_service=service)) as client:
        response = client.get("/api/v1/metrics/platform")
        assert response.status_code == 200
        cc1 = response.headers["cache-control"]
        assert "public" in cc1
        ma1 = cache_control_max_age(cc1)
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
        ma2 = cache_control_max_age(cached_response.headers["cache-control"])
        assert ma2 < ma1


def test_user_and_session_routes_removed() -> None:
    with TestClient(create_app(platform_service=_service())) as client:
        assert client.get("/api/v1/metrics/users/u1").status_code == 404
        assert client.get("/api/v1/metrics/users/u1/sessions/s1").status_code == 404
        assert client.get("/metrics").status_code == 404
        assert client.get("/metrics/u1").status_code == 404


def test_build_cache_backend_memory() -> None:
    cache, redis_client = build_cache_backend(
        Settings(cache=CacheConfig(backend="memory", ttl_seconds=10))
    )
    assert isinstance(cache, InMemoryTTLCache)
    assert redis_client is None


def test_build_cache_backend_redis() -> None:
    cache, redis_client = build_cache_backend(
        Settings(
            cache=CacheConfig(backend="redis", ttl_seconds=10),
            redis_url="redis://localhost:6379/0",
            redis_key_prefix="metrics:",
        )
    )
    assert isinstance(cache, RedisJSONTTLCache)
    assert redis_client is not None


class FakeMeterProvider:
    def __init__(self) -> None:
        self.shutdown_calls = 0

    def shutdown(self) -> None:
        self.shutdown_calls += 1


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

    monkeypatch.setattr(factory_module, "setup_telemetry", fake_setup_telemetry)
    monkeypatch.setattr(factory_module, "FastAPIInstrumentor", FakeFastAPIInstrumentor)
    monkeypatch.setattr(
        factory_module,
        "HTTPXClientInstrumentor",
        FakeHTTPXClientInstrumentor,
    )

    with TestClient(
        create_app(
            settings=Settings(otel_metrics_enabled=True, cache=CacheConfig(backend="memory")),
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


def test_create_app_uses_injected_platform_service_for_platform_route() -> None:
    app = create_app(
        settings=Settings(cache=CacheConfig(backend="memory")),
        platform_service=_service(),
    )
    with TestClient(app) as client:
        response = client.get("/api/v1/metrics/platform")
        assert response.status_code == 200
        assert "capacity" in response.json()["data"]
