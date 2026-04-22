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
    assert isinstance(payload["data"]["capacity"], dict)
    assert isinstance(payload["data"]["allocated"], dict)
    assert "cpu" in payload["data"]["capacity"]
    assert "memory" in payload["data"]["capacity"]
    assert "created" in payload["metadata"]
    assert "ttl" not in payload["metadata"]
    assert "cached" not in payload["metadata"]
    assert "Cache-Control" in response.headers
    assert "Date" in response.headers or "date" in response.headers
