"""End-to-end smoke: FakeKueueApi -> KueueProvider -> service -> routes -> headers."""

from __future__ import annotations

import importlib
from datetime import UTC, datetime, timedelta
from unittest.mock import AsyncMock, MagicMock

import httpx
import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from opentelemetry.exporter.otlp.proto.http.metric_exporter import OTLPMetricExporter
from opentelemetry.sdk.metrics import MeterProvider

import metrics.core.factory as factory_module
import metrics.main as main_module
from metrics.cache import FRESHNESS_POLICIES, CacheIdentity, InMemoryCoordinator
from metrics.core.runtime import MetricsRuntime
from metrics.core.settings import CacheConfig, KueueProviderConfig, ProviderConfigs, Settings
from metrics.errors import RuntimeStartupError
from metrics.http_cache import metrics_success_cache_headers, remaining_freshness_seconds
from metrics.providers.kueue import KueueProvider
from metrics.services.metrics import MetricsService
from metrics.services.models import CachedSnapshot
from metrics.telemetry import NoopMetricsRecorder
from tests.fakes import FakeKueueApi, LifecycleProvider, cache_control_max_age

CQ_A = {
    "spec": {
        "resourceGroups": [
            {
                "flavors": [
                    {
                        "resources": [
                            {"name": "cpu", "nominalQuota": "10.1"},
                            {"name": "memory", "nominalQuota": "20Gi"},
                            {"name": "ephemeral-storage", "nominalQuota": "512Mi"},
                            {"name": "nvidia.com/gpu", "nominalQuota": "0.1"},
                        ]
                    }
                ]
            }
        ]
    },
    "status": {
        "flavorsUsage": [
            {
                "resources": [
                    {"name": "cpu", "total": "100m", "borrowed": "500m"},
                    {"name": "memory", "total": "2Gi", "borrowed": "1Gi"},
                    {"name": "nvidia.com/gpu", "total": "0.1"},
                ]
            }
        ]
    },
}
CQ_B = {
    "spec": {
        "resourceGroups": [
            {
                "flavors": [
                    {
                        "resources": [
                            {"name": "cpu", "nominalQuota": "5.2"},
                            {"name": "ephemeral-storage", "nominalQuota": "1.5Gi"},
                            {"name": "nvidia.com/gpu", "nominalQuota": "0.2"},
                        ]
                    }
                ]
            }
        ]
    },
    "status": {"flavorsUsage": [{"resources": [{"name": "nvidia.com/gpu", "total": "0.2"}]}]},
}


def _settings() -> Settings:
    return Settings(
        cluster_name="prod",
        cache=CacheConfig(backend="memory"),
        providers=ProviderConfigs(kueue=KueueProviderConfig(cluster_queues=["cq-a", "cq-b"])),
    )


def _runtime(
    *,
    provider: object | None = None,
    docs: dict[str, object] | None = None,
) -> MetricsRuntime:
    settings = _settings()
    active = provider or KueueProvider(
        settings, api=FakeKueueApi(docs or {"cq-a": CQ_A, "cq-b": CQ_B})
    )
    cache = InMemoryCoordinator[CachedSnapshot](
        policy=FRESHNESS_POLICIES["platform"],
        created=lambda snapshot: snapshot.created,
    )
    service = MetricsService(
        platform=active.read_platform,
        cache=cache,
        identity=lambda: CacheIdentity("platform", "", "prod", "kueue", "test"),
    )
    return MetricsRuntime(settings, provider=active, metrics_service=service, cache=cache)


def test_platform_endpoint_serves_aggregated_kueue_data_with_cache_headers() -> None:
    with TestClient(factory_module.create_app(settings=_settings(), runtime=_runtime())) as client:
        response = client.get("/api/v1/metrics/platform")
        assert response.status_code == 200

        # HTTP caching is header-based (ADR-0002): shared, bounded by TTL.
        cache_control = response.headers["cache-control"]
        assert "public" in cache_control
        assert 295 <= cache_control_max_age(cache_control) <= 300
        assert response.headers.get("date")
        assert response.headers.get("expires")
        created = datetime.fromisoformat(response.json()["metadata"]["created"])
        assert created.tzinfo is not None

        # Versioned envelope with no cache metadata in the body.
        payload = response.json()
        assert set(payload) == {"version", "kind", "metadata", "status", "data"}
        assert payload["version"] == "metrics.canfar.net/v1"
        assert payload["kind"] == "PlatformMetrics"
        assert payload["status"] == "Success"
        assert "ttl" not in payload["metadata"] and "cached" not in payload["metadata"]

        # Aggregation contract (ADR-0002): summed quotas, usage totals only,
        # unit parity, zero-alignment, sorted open-ended maps.
        data = payload["data"]
        assert set(data) == {"scope", "cluster", "capacity", "allocated"}
        assert data["cluster"] == "prod"
        assert data["capacity"]["cpu"] == "15.3"
        assert data["capacity"]["memory"] == "20Gi"
        assert data["capacity"]["ephemeral-storage"] == "2Gi"
        assert data["capacity"]["nvidia.com/gpu"] == "0.3"
        assert data["allocated"]["cpu"] == "0.1"
        assert data["allocated"]["memory"] == "2Gi"
        assert data["allocated"]["ephemeral-storage"] == "0Gi"
        assert data["allocated"]["nvidia.com/gpu"] == "0.3"
        assert list(data["capacity"]) == sorted(data["capacity"])
        assert set(data["allocated"]) == set(data["capacity"])

        # Second read is served from the same snapshot.
        second = client.get("/api/v1/metrics/platform")
        assert second.headers["last-modified"] == response.headers["last-modified"]

        # Removed route surface stays removed (ADR-0002).
        assert client.get("/api/v1/metrics/users/u1").status_code == 404
        assert client.get("/api/v1/metrics/users/u1/sessions/s1").status_code == 404
        assert client.get("/metrics").status_code == 404


@pytest.mark.parametrize(
    ("broken_doc", "status_code", "code", "message"),
    [
        # Upstream failure -> 502; unavailable data -> 503. Raw upstream text
        # and URLs must never reach the response body (ADR-0002).
        (
            httpx.ConnectError("secret at https://kubernetes.default.svc"),
            502,
            "platform_metrics_error",
            "Platform metrics collection failed",
        ),
        (
            {"spec": {}},
            503,
            "platform_metrics_unavailable",
            "Could not load platform metrics from Kubernetes",
        ),
    ],
)
def test_provider_errors_map_to_sanitized_error_envelopes(
    broken_doc: object,
    status_code: int,
    code: str,
    message: str,
) -> None:
    # Healthy at startup; the upstream breaks afterwards, on the request path.
    api = FakeKueueApi({"cq-a": CQ_A, "cq-b": CQ_B})
    provider = KueueProvider(_settings(), api=api)
    with TestClient(
        factory_module.create_app(settings=_settings(), runtime=_runtime(provider=provider))
    ) as client:
        api.docs = {"cq-a": broken_doc, "cq-b": broken_doc}
        response = client.get("/api/v1/metrics/platform")

    assert response.status_code == status_code
    assert response.headers["cache-control"] == "no-store"
    payload = response.json()
    assert set(payload) == {"version", "kind", "metadata", "status", "error"}
    assert payload["kind"] == "Status"
    assert payload["status"] == "Error"
    assert payload["error"] == {"code": code, "message": message}
    assert "secret" not in response.text
    assert "kubernetes.default.svc" not in response.text


def test_generic_500_response_exposes_no_exception_details() -> None:
    app = factory_module.create_app(settings=_settings(), runtime=_runtime())

    @app.get("/boom")
    async def boom() -> None:
        raise ValueError("secret-token from https://internal.example")

    with TestClient(app, raise_server_exceptions=False) as client:
        response = client.get("/boom")

    assert response.status_code == 500
    assert response.json()["error"]["code"] == "internal_error"
    assert "secret-token" not in response.text
    assert "ValueError" not in response.text


def test_http_cache_header_edge_cases() -> None:
    created = datetime(2026, 1, 1, 12, 0, 0, tzinfo=UTC)
    assert remaining_freshness_seconds(created, 60, now=created + timedelta(seconds=10)) == 50
    assert remaining_freshness_seconds(created, 5, now=created + timedelta(seconds=10)) == 0

    private = metrics_success_cache_headers(
        snapshot_created=created,
        configured_ttl=30,
        shared_cache_public=False,
        now=created,
    )
    assert "private" in private["Cache-Control"]

    no_store = metrics_success_cache_headers(
        snapshot_created=created,
        configured_ttl=0,
        shared_cache_public=True,
        now=created,
    )
    assert no_store["Cache-Control"] == "no-store"
    assert "Expires" not in no_store


def test_constructed_and_injected_runtimes_share_lifecycle_and_cleanup(monkeypatch) -> None:
    settings = _settings()
    for injected in (False, True):
        runtime = _runtime()
        start, shutdown = AsyncMock(), AsyncMock()
        build_runtime = MagicMock(return_value=runtime)
        monkeypatch.setattr(runtime, "start", start)
        monkeypatch.setattr(runtime, "shutdown", shutdown)
        monkeypatch.setattr(MetricsRuntime, "from_settings", build_runtime)

        app = factory_module.create_app(settings=settings, runtime=runtime if injected else None)
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

    # Startup failure still runs application cleanup.
    provider = LifecycleProvider(startup_error=RuntimeStartupError("misconfigured"))
    runtime = _runtime(provider=provider)
    with pytest.raises(RuntimeStartupError, match="misconfigured"):
        with TestClient(factory_module.create_app(settings=settings, runtime=runtime)):
            pass
    assert provider.events == ["startup", "provider shutdown"]


def test_otel_wiring_instruments_and_uninstruments(monkeypatch) -> None:
    meter_provider = MagicMock(spec=MeterProvider)
    fastapi_instrumentor = MagicMock()
    httpx_instrumentor = MagicMock()
    monkeypatch.setattr(
        factory_module,
        "setup_telemetry",
        lambda settings: (NoopMetricsRecorder(), meter_provider),
    )
    monkeypatch.setattr(factory_module, "FastAPIInstrumentor", fastapi_instrumentor)
    monkeypatch.setattr(factory_module, "HTTPXClientInstrumentor", httpx_instrumentor)

    settings = Settings(otel_metrics_enabled=True, cache=CacheConfig(backend="memory"))
    with TestClient(factory_module.create_app(settings=settings, runtime=_runtime())) as client:
        assert client.get("/healthz").status_code == 200

    fastapi_instrumentor.instrument_app.assert_called_once()
    fastapi_instrumentor.uninstrument_app.assert_called_once()
    httpx_instrumentor.return_value.instrument.assert_called_once()
    httpx_instrumentor.return_value.uninstrument.assert_called_once()
    meter_provider.shutdown.assert_called_once_with()


def test_main_run_wires_settings_logging_app_and_server(monkeypatch) -> None:
    settings = Settings(host="127.0.0.1", port=9000, cache=CacheConfig(backend="memory"))
    app = object()
    monkeypatch.setattr(main_module, "Settings", MagicMock(return_value=settings))
    monkeypatch.setattr(main_module, "create_app", MagicMock(return_value=app))
    run_server = MagicMock()
    monkeypatch.setattr(main_module.uvicorn, "run", run_server)

    main_module.run()

    run_server.assert_called_once_with(
        app,
        host="127.0.0.1",
        port=9000,
        log_level="info",
        workers=1,
    )


@pytest.mark.parametrize(
    "module_name",
    [
        "metrics",
        "metrics.core.settings",
        "metrics.core.factory",
        "metrics.providers.kueue",
        "metrics.api.v1.routes",
        "metrics.main",
    ],
)
def test_module_imports_construct_no_runtime_resources(module_name: str) -> None:
    module = importlib.import_module(module_name)
    resource_types = (
        Settings,
        FastAPI,
        httpx.AsyncClient,
        MetricsRuntime,
        MeterProvider,
        OTLPMetricExporter,
    )
    assert not any(isinstance(value, resource_types) for value in vars(module).values())
