"""Focused tests for the Metrics process entrypoint."""

from __future__ import annotations

from copy import deepcopy
from types import SimpleNamespace

import pytest

import metrics.main as main_module


@pytest.mark.parametrize(
    ("configured_level", "expected_level"),
    [("info", "INFO"), ("trace", "DEBUG")],
)
def test_run_configures_application_logging_before_uvicorn(
    monkeypatch: pytest.MonkeyPatch,
    configured_level: str,
    expected_level: str,
) -> None:
    """Pass an application logger handler to Uvicorn before starting the worker."""
    settings = SimpleNamespace(
        host="127.0.0.1",
        port=8000,
        log_level=configured_level,
    )
    default_log_config = deepcopy(main_module.uvicorn.config.LOGGING_CONFIG)
    monkeypatch.setattr(main_module, "Settings", lambda: settings)
    monkeypatch.setattr(main_module, "create_app", lambda settings: object())

    def fake_uvicorn_run(_app: object, **kwargs: object) -> None:
        """Observe application logging configuration at Uvicorn startup."""
        log_config = kwargs["log_config"]
        assert isinstance(log_config, dict)
        assert log_config["loggers"]["metrics"] == {
            "handlers": ["default"],
            "level": expected_level,
            "propagate": False,
        }
        assert "default" in log_config["handlers"]
        assert log_config["handlers"] is not main_module.uvicorn.config.LOGGING_CONFIG["handlers"]
        assert kwargs["log_level"] == configured_level
        assert kwargs["workers"] == 1

    monkeypatch.setattr(main_module.uvicorn, "run", fake_uvicorn_run)

    main_module.run()

    assert main_module.uvicorn.config.LOGGING_CONFIG == default_log_config


_VALID = {
    "METRICS_CLUSTER_NAME": "cluster-a",
    "METRICS_REDIS_URL": "redis://localhost:6379/0",
    "METRICS_CACHE__KEY_SECRET": "test-cache-integrity-key-32-bytes",
    "METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES": '["cq-a"]',
    "METRICS_PROVIDERS__KUEUE__NAMESPACES": '["work-a"]',
}


def _environ(monkeypatch: pytest.MonkeyPatch, values: dict[str, str]) -> dict[str, str]:
    for name in list(main_module.os.environ):
        if name.startswith("METRICS_"):
            monkeypatch.delenv(name)
    for name, value in values.items():
        monkeypatch.setenv(name, value)
    return dict(main_module.os.environ)


def test_valid_environment_has_no_configuration_errors(monkeypatch: pytest.MonkeyPatch) -> None:
    assert main_module.configuration_errors(_environ(monkeypatch, _VALID)) == []


def test_retired_names_are_rejected_with_their_replacement(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    environ = _environ(
        monkeypatch,
        _VALID
        | {
            "METRICS_OTEL_METRICS_ENABLED": "true",
            "METRICS_ENVIRONMENT": "prod",
            "METRICS_CACHE__BACKEND": "redis",
            "METRICS_OTEL_COLLECTOR_SERVICE_HOST": "10.0.0.1",  # a Kubernetes service link
        },
    )
    assert main_module.configuration_errors(environ) == [
        "METRICS_CACHE__BACKEND was removed",
        "METRICS_ENVIRONMENT is no longer read; use METRICS_OTEL__DEPLOYMENT_ENVIRONMENT",
        "METRICS_OTEL_METRICS_ENABLED is no longer read; use METRICS_OTEL__METRICS_ENABLED",
    ]


def test_invalid_settings_name_each_variable_without_its_value(
    monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    values = {name: value for name, value in _VALID.items() if "KEY_SECRET" not in name}
    values["METRICS_REDIS_URL"] = "http://user:hunter2@example"
    values["METRICS_PROVIDERS__KUEUE__EXTRA"] = "x"
    _environ(monkeypatch, values)

    with pytest.raises(SystemExit) as exit_:
        main_module.run()

    assert exit_.value.code == 2
    stderr = capsys.readouterr().err
    assert "METRICS_CACHE__KEY_SECRET: Field required" in stderr
    assert "METRICS_REDIS_URL:" in stderr
    assert "METRICS_PROVIDERS__KUEUE__EXTRA: Extra inputs are not permitted" in stderr
    assert "hunter2" not in stderr
