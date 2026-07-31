"""App factory, OpenTelemetry wiring, and HTTP route behavior (TestClient)."""

from __future__ import annotations

import time
from unittest.mock import AsyncMock, MagicMock

import pytest
from fastapi.testclient import TestClient
from opentelemetry.sdk.metrics import MeterProvider

import metrics.core.factory as factory_module
from metrics.cache import InMemoryTTLCache, RedisJSONTTLCache
from metrics.core.factory import create_app
from metrics.core.runtime import MetricsRuntime, build_cache_backend
from metrics.core.settings import CacheConfig, Settings
from metrics.errors import RuntimeStartupError
from metrics.schemas.metrics import PlatformMetricsData
from metrics.services.platform import CachedMetrics, PlatformMetricsService
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
        platform=stub.load,
        cache=InMemoryTTLCache[CachedMetrics](ttl_seconds=30),
        key=cache_key,
    )


class _Provider:
    @property
    def name(self) -> str:
        return "stub"

    async def startup(self) -> None:
        return

    async def shutdown(self) -> None:
        return

    def cache_fingerprint(self) -> str:
        return "stub"

    async def platform(self) -> PlatformMetricsData:
        return await StubPlatformMetrics().load()


def _runtime() -> MetricsRuntime:
    runtime = MetricsRuntime(Settings(cache=CacheConfig(backend="memory")))
    runtime.wire(
        provider=_Provider(),
        platform_service=_service(),
        redis=None,
    )
    return runtime


def test_platform_endpoint() -> None:
    with TestClient(create_app(settings=Settings(), runtime=_runtime())) as client:
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
        assert set(payload["data"].keys()) == {"scope", "cluster", "capacity", "allocated"}
        assert "borrowed" not in payload["data"]
        assert "lending" not in payload["data"]
        assert payload["data"]["cluster"] == "prod"
        assert isinstance(payload["data"]["capacity"], dict)
        assert isinstance(payload["data"]["allocated"], dict)
        assert payload["data"]["capacity"]["cpu"] == "100"
        assert payload["data"]["capacity"]["memory"] == "200Gi"
        assert payload["data"]["allocated"]["cpu"] == "25"
        assert payload["data"]["allocated"]["memory"] == "50Gi"

        time.sleep(1.1)
        cached_response = client.get("/api/v1/metrics/platform")
        ma2 = cache_control_max_age(cached_response.headers["cache-control"])
        assert ma2 < ma1


def test_user_and_session_routes_removed() -> None:
    with TestClient(create_app(settings=Settings(), runtime=_runtime())) as client:
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


def test_create_app_configures_otel_and_shutdown_hooks(monkeypatch) -> None:
    calls = {
        "fastapi_instrument": 0,
        "fastapi_uninstrument": 0,
        "httpx_instrument": 0,
        "httpx_uninstrument": 0,
    }
    meter_provider = MagicMock(spec=MeterProvider)

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
            runtime=_runtime(),
        )
    ) as client:
        response = client.get("/api/v1/metrics/platform")
        assert response.status_code == 200

    assert calls["fastapi_instrument"] == 1
    assert calls["fastapi_uninstrument"] == 1
    assert calls["httpx_instrument"] == 1
    assert calls["httpx_uninstrument"] == 1
    meter_provider.shutdown.assert_called_once_with()


def test_constructed_and_injected_runtimes_use_the_same_lifecycle(monkeypatch) -> None:
    settings = Settings(cache=CacheConfig(backend="memory"))
    for injected in (False, True):
        runtime = _runtime()
        start = AsyncMock()
        shutdown = AsyncMock()
        build_runtime = MagicMock(return_value=runtime)
        monkeypatch.setattr(runtime, "start", start)
        monkeypatch.setattr(runtime, "shutdown", shutdown)
        monkeypatch.setattr(MetricsRuntime, "from_settings", build_runtime)

        app = create_app(settings=settings, runtime=runtime if injected else None)
        with TestClient(app) as client:
            assert client.get("/healthz").status_code == 200
            assert app.state.runtime is runtime

        start.assert_awaited_once_with()
        shutdown.assert_awaited_once_with()
        if injected:
            build_runtime.assert_not_called()
        else:
            build_runtime.assert_called_once()
            assert build_runtime.call_args.args == (settings,)
            assert isinstance(
                build_runtime.call_args.kwargs["recorder"],
                NoopMetricsRecorder,
            )


def test_startup_failure_still_runs_application_cleanup(monkeypatch) -> None:
    runtime = _runtime()
    start = AsyncMock(side_effect=RuntimeStartupError("misconfigured"))
    shutdown = AsyncMock()
    monkeypatch.setattr(runtime, "start", start)
    monkeypatch.setattr(runtime, "shutdown", shutdown)

    with pytest.raises(RuntimeStartupError, match="misconfigured"):
        with TestClient(create_app(settings=Settings(), runtime=runtime)):
            pass

    start.assert_awaited_once_with()
    shutdown.assert_awaited_once_with()


def test_generic_500_response_exposes_no_exception_details() -> None:
    app = create_app(settings=Settings(), runtime=_runtime())

    @app.get("/boom")
    async def boom() -> None:
        raise ValueError("secret-token from https://internal.example")

    with TestClient(app, raise_server_exceptions=False) as client:
        response = client.get("/boom")

    assert response.status_code == 500
    payload = response.json()
    assert payload["error"] == {
        "code": "internal_error",
        "message": "Unexpected internal server error",
        "details": None,
    }
    assert "ValueError" not in response.text
    assert "secret-token" not in response.text
    assert "internal.example" not in response.text
