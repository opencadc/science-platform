"""Shared pytest fixtures for the metrics package."""

from __future__ import annotations

import pytest


@pytest.fixture(autouse=True)
def _noop_application_startup_for_unit_tests(
    request: pytest.FixtureRequest, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Avoid real cluster and Prometheus calls when constructing FastAPI apps."""
    if request.node.get_closest_marker("integration"):
        return

    async def _noop(_settings):
        return None

    monkeypatch.setattr(
        "metrics.core.factory.validate_application_startup",
        _noop,
    )
