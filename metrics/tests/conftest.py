"""Shared pytest fixtures for the metrics package."""

from __future__ import annotations

import pytest

from metrics.core.runtime import MetricsRuntime


@pytest.fixture(autouse=True)
def _noop_application_startup_for_unit_tests(
    request: pytest.FixtureRequest, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Avoid real cluster calls when constructing FastAPI apps in unit tests."""
    if request.node.get_closest_marker("integration"):
        return

    async def _async_noop(self: MetricsRuntime) -> None:  # noqa: ARG001
        return

    monkeypatch.setattr(MetricsRuntime, "start", _async_noop)
