from __future__ import annotations

import importlib
from unittest.mock import MagicMock

import httpx
import metrics.main as main_module
import pytest
from fastapi import FastAPI
from opentelemetry.exporter.otlp.proto.http.metric_exporter import OTLPMetricExporter
from opentelemetry.sdk.metrics import MeterProvider

from metrics.core.runtime import MetricsRuntime
from metrics.core.settings import Settings


def test_run_constructs_settings_and_app_once(monkeypatch) -> None:
    settings = Settings(host="127.0.0.1", port=9000)
    app = object()
    load_settings = MagicMock(return_value=settings)
    configure_logging = MagicMock()
    build_app = MagicMock(return_value=app)
    run_server = MagicMock()

    monkeypatch.setattr(main_module, "Settings", load_settings)
    monkeypatch.setattr(main_module, "apply_metrics_package_log_level", configure_logging)
    monkeypatch.setattr(main_module, "create_app", build_app)
    monkeypatch.setattr(main_module.uvicorn, "run", run_server)

    main_module.run()

    load_settings.assert_called_once_with()
    configure_logging.assert_called_once_with(settings)
    build_app.assert_called_once_with(settings=settings)
    run_server.assert_called_once_with(
        app,
        host="127.0.0.1",
        port=9000,
        log_level="info",
    )


@pytest.mark.parametrize(
    "module_name",
    [
        "metrics",
        "metrics.core",
        "metrics.core.settings",
        "metrics.core.factory",
        "metrics.providers",
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
