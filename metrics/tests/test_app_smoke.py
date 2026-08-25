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
from metrics.core.settings import (
    CacheConfig,
    KueueProviderConfig,
    OTelConfig,
    ProviderConfigs,
    Settings,
)
from metrics.errors import RuntimeStartupError
from metrics.http_cache import metrics_success_cache_headers, remaining_freshness_seconds
from metrics.providers.kueue import KueueProvider
from metrics.services.metrics import MetricsService
from metrics.services.models import CachedSnapshot, PlatformObservation
from metrics.telemetry import NoopMetricsRecorder, Telemetry
from tests.fakes import FakeKueueApi, LifecycleProvider

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
        identity=lambda: CacheIdentity("platform", "canfar", "prod", "kueue", "test"),
    )
    return MetricsRuntime(settings, provider=active, metrics_service=service, cache=cache)


def test_platform_endpoint_serves_aggregated_kueue_data_with_cache_headers() -> None:
    with TestClient(factory_module.create_app(settings=_settings(), runtime=_runtime())) as client:
        response = client.get("/apis/canfar.net/v1alpha1/metrics/platform/canfar")
        assert response.status_code == 200

        assert response.headers["cache-control"] == "no-store"
        assert response.headers["age"] == "0"
        assert response.headers["cache-status"].startswith("metrics; fwd=uri-miss; ttl=")
        assert response.headers["last-modified"]
        payload = response.json()
        observed_at = payload["status"]["observedAt"]
        assert payload == {
            "apiVersion": "canfar.net/v1alpha1",
            "kind": "Metrics",
            "metadata": {"name": "platform-canfar"},
            "spec": {"platform": "canfar"},
            "status": {
                "observedAt": observed_at,
                "resources": [
                    {"name": "cpu", "capacity": "15.3", "allocated": "0.1"},
                    {"name": "ephemeral-storage", "capacity": "2Gi", "allocated": "0Gi"},
                    {"name": "memory", "capacity": "20Gi", "allocated": "2Gi"},
                    {"name": "nvidia.com/gpu", "capacity": "0.3", "allocated": "0.3"},
                ],
                "conditions": [
                    {
                        "type": "Ready",
                        "status": "True",
                        "reason": "Available",
                        "lastTransitionTime": observed_at,
                    },
                    {
                        "type": "Cached",
                        "status": "False",
                        "reason": "Refreshed",
                        "lastTransitionTime": observed_at,
                    },
                ],
            },
        }
        assert datetime.fromisoformat(observed_at).tzinfo is not None

        # Second read is served from the same snapshot.
        second = client.get("/apis/canfar.net/v1alpha1/metrics/platform/canfar")
        assert second.headers["last-modified"] == response.headers["last-modified"]
        assert second.json()["status"]["conditions"][1]["reason"] == "FreshHit"
        assert second.headers["cache-status"].startswith("metrics; hit; ttl=")

        conditional = client.get(
            "/apis/canfar.net/v1alpha1/metrics/platform/canfar",
            headers={"If-Modified-Since": second.headers["last-modified"]},
        )
        assert conditional.status_code == 304
        assert conditional.content == b""
        assert conditional.headers["last-modified"] == second.headers["last-modified"]
        assert conditional.headers["cache-control"] == "no-store"

        legacy = client.get("/api/v1/metrics/platform")
        assert legacy.status_code == 404
        assert legacy.json()["kind"] == "Status"


def test_stale_platform_report_has_exact_conditions_and_headers() -> None:
    settings = _settings()
    provider = KueueProvider(settings, api=FakeKueueApi({"cq-a": CQ_A, "cq-b": CQ_B}))
    identity = CacheIdentity("platform", "canfar", "prod", "kueue", "test")
    cache = InMemoryCoordinator[CachedSnapshot](
        policy=FRESHNESS_POLICIES["platform"],
        created=lambda snapshot: snapshot.created,
    )
    created = datetime.now(UTC) - timedelta(minutes=10)
    cache._values.put(  # noqa: SLF001 - deterministic stale cache fixture
        identity.canonical().decode(),
        CachedSnapshot(
            observation=PlatformObservation(
                cluster="prod",
                capacity={"cpu": "1"},
                allocated={"cpu": "0"},
            ),
            created=created,
        ),
    )
    service = MetricsService(
        platform=provider.read_platform,
        cache=cache,
        identity=lambda: identity,
    )
    runtime = MetricsRuntime(
        settings,
        provider=provider,
        metrics_service=service,
        cache=cache,
    )

    with TestClient(factory_module.create_app(settings=settings, runtime=runtime)) as client:
        response = client.get("/apis/canfar.net/v1alpha1/metrics/platform/canfar")

    assert response.status_code == 200
    assert response.headers["cache-control"] == "no-store"
    assert int(response.headers["age"]) >= 600
    assert "ttl=-" in response.headers["cache-status"]
    assert response.json()["status"]["conditions"] == [
        {
            "type": "Ready",
            "status": "False",
            "reason": "StaleData",
            "lastTransitionTime": created.isoformat().replace("+00:00", "Z"),
        },
        {
            "type": "Cached",
            "status": "True",
            "reason": "StaleHit",
            "lastTransitionTime": created.isoformat().replace("+00:00", "Z"),
        },
    ]


def test_openapi_contains_only_v1alpha1_metrics_contract() -> None:
    with TestClient(factory_module.create_app(settings=_settings(), runtime=_runtime())) as client:
        schema = client.get("/openapi.json").json()

    assert "/apis/canfar.net/v1alpha1/metrics/platform/canfar" in schema["paths"]
    assert "/apis/canfar.net/v1alpha1/metrics/user/{user}" in schema["paths"]
    assert "/apis/canfar.net/v1alpha1/metrics/community/{community}" in schema["paths"]
    assert "/api/v1/metrics/platform" not in schema["paths"]
    assert "Metrics" in schema["components"]["schemas"]
    assert "Status" in schema["components"]["schemas"]
    assert not any("PlatformMetrics" in name for name in schema["components"]["schemas"])


@pytest.mark.parametrize(
    "broken_doc",
    [
        httpx.ConnectError("secret at https://kubernetes.default.svc"),
        {"spec": {}},
    ],
)
def test_provider_errors_map_to_sanitized_error_envelopes(
    broken_doc: object,
) -> None:
    # Healthy at startup; the upstream breaks afterwards, on the request path.
    api = FakeKueueApi({"cq-a": CQ_A, "cq-b": CQ_B})
    provider = KueueProvider(_settings(), api=api)
    with TestClient(
        factory_module.create_app(settings=_settings(), runtime=_runtime(provider=provider))
    ) as client:
        api.docs = {"cq-a": broken_doc, "cq-b": broken_doc}
        response = client.get("/apis/canfar.net/v1alpha1/metrics/platform/canfar")

    assert response.status_code == 503
    assert response.headers["cache-control"] == "no-store"
    assert response.json() == {
        "apiVersion": "v1",
        "kind": "Status",
        "status": "Failure",
        "reason": "ServiceUnavailable",
        "message": "The requested metrics report could not be produced.",
        "code": 503,
    }
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
    assert response.json()["reason"] == "InternalError"
    assert "secret-token" not in response.text
    assert "ValueError" not in response.text


def test_http_cache_header_edge_cases() -> None:
    created = datetime(2026, 1, 1, 12, 0, 0, tzinfo=UTC)
    assert remaining_freshness_seconds(created, 60, now=created + timedelta(seconds=10)) == 50
    assert remaining_freshness_seconds(created, 5, now=created + timedelta(seconds=10)) == -5

    stale = metrics_success_cache_headers(
        snapshot_created=created,
        configured_ttl=30,
        cached=True,
        stale=True,
        cache_available=True,
        now=created + timedelta(seconds=40),
    )
    assert stale["Cache-Control"] == "no-store"
    assert stale["Age"] == "40"
    assert stale["Cache-Status"] == "metrics; hit; ttl=-10"

    redis_down = metrics_success_cache_headers(
        snapshot_created=created,
        configured_ttl=30,
        cached=True,
        stale=False,
        cache_available=False,
        now=created,
    )
    assert 'detail="redis-unavailable"' in redis_down["Cache-Status"]


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
        lambda settings: Telemetry(NoopMetricsRecorder(), meter_provider=meter_provider),
    )
    monkeypatch.setattr(factory_module, "FastAPIInstrumentor", fastapi_instrumentor)
    monkeypatch.setattr(factory_module, "HTTPXClientInstrumentor", httpx_instrumentor)

    settings = Settings(
        otel=OTelConfig(
            metrics_enabled=True,
            exporter_otlp_endpoint="http://collector:4318",
        ),
        cache=CacheConfig(backend="memory"),
    )
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
        access_log=False,
        workers=1,
    )


@pytest.mark.parametrize(
    "module_name",
    [
        "metrics",
        "metrics.core.settings",
        "metrics.core.factory",
        "metrics.providers.kueue",
        "metrics.api.v1alpha1.routes",
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
