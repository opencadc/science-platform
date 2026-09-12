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
