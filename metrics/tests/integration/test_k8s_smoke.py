from __future__ import annotations

import os

import httpx
import pytest

from metrics.quantity import parse_cpu_to_cores, parse_memory_to_gib


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


def test_platform_endpoint_allocated_includes_kueue_smoke_workload() -> None:
    """Allocated map includes the borrowed Kueue smoke Workload total."""
    base_url = _base_url()
    if not base_url:
        pytest.skip("METRICS_BASE_URL not configured")

    response = httpx.get(f"{base_url}/api/v1/metrics/platform", timeout=30.0)
    assert response.status_code == 200
    allocated = response.json()["data"]["allocated"]
    cpu_cores = parse_cpu_to_cores(allocated.get("cpu", "0"))
    mem_gib = parse_memory_to_gib(allocated.get("memory", "0"))
    # scripts/test-setup.yaml: cq-electron has 100m/100Mi nominal quota, while
    # integration-idle requests 200m/200Mi. Admission proves borrowing, and
    # ClusterQueue status.flavorsUsage total must include that borrowed usage.
    assert cpu_cores >= 0.19, f"expected >=200m CPU in allocated, got {allocated!r}"
    assert mem_gib >= 0.19, (
        f"expected >=200Mi memory from smoke workload in allocated, got {allocated!r}"
    )
