from __future__ import annotations

import os

import httpx
import pytest


pytestmark = pytest.mark.integration


def _base_url() -> str | None:
    return os.getenv("METRICS_BASE_URL")


def test_health_endpoint() -> None:
    base_url = _base_url()
    if not base_url:
        pytest.skip("METRICS_BASE_URL not configured")

    response = httpx.get(f"{base_url}/healthz", timeout=10)
    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


def test_platform_endpoint_shape() -> None:
    base_url = _base_url()
    if not base_url:
        pytest.skip("METRICS_BASE_URL not configured")

    response = httpx.get(f"{base_url}/api/v1/metrics/platform", timeout=10)
    assert response.status_code == 200
    payload = response.json()
    assert payload["kind"] == "PlatformMetrics"
    assert payload["data"]["scope"] == "platform"
    assert "Cache-Control" in response.headers
