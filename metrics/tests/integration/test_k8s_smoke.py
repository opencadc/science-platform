from __future__ import annotations

import os

import httpx
import pytest

from metrics.providers.kueue import parse_resource_amount


pytestmark = [
    pytest.mark.integration,
    pytest.mark.skipif(not os.getenv("METRICS_BASE_URL"), reason="METRICS_BASE_URL not configured"),
]
base_url = os.getenv("METRICS_BASE_URL", "")


def test_health_endpoint() -> None:
    response = httpx.get(f"{base_url}/healthz", timeout=10)
    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


def test_platform_endpoint_shape() -> None:
    response = httpx.get(
        f"{base_url}/apis/canfar.net/v1alpha1/metrics/platform/canfar", timeout=10
    )
    assert response.status_code == 200
    payload = response.json()
    assert payload["apiVersion"] == "canfar.net/v1alpha1"
    assert payload["kind"] == "Metrics"
    assert payload["spec"] == {"platform": "canfar"}
    resources = {resource["name"]: resource for resource in payload["status"]["resources"]}
    assert {"cpu", "memory"} <= resources.keys()
    assert [condition["type"] for condition in payload["status"]["conditions"]] == [
        "Ready",
        "Cached",
    ]
    assert response.headers["cache-control"] == "no-store"
    assert {"last-modified", "age", "cache-status"} <= response.headers.keys()


def test_platform_endpoint_allocated_includes_kueue_smoke_workload() -> None:
    """Allocated map includes the borrowed Kueue smoke Workload total."""
    response = httpx.get(
        f"{base_url}/apis/canfar.net/v1alpha1/metrics/platform/canfar", timeout=30.0
    )
    assert response.status_code == 200
    allocated = {
        resource["name"]: resource["allocated"]
        for resource in response.json()["status"]["resources"]
    }
    cpu_cores = parse_resource_amount("cpu", allocated.get("cpu", "0"))
    mem_gib = parse_resource_amount("memory", allocated.get("memory", "0Gi"))
    # scripts/test-setup.yaml: cq-electron has 100m/100Mi nominal quota, while
    # integration-idle requests 200m/200Mi. Admission proves borrowing, and
    # ClusterQueue status.flavorsUsage total must include that borrowed usage.
    assert cpu_cores >= 0.19, f"expected >=200m CPU in allocated, got {allocated!r}"
    assert mem_gib >= 0.19, (
        f"expected >=200Mi memory from smoke workload in allocated, got {allocated!r}"
    )


def test_user_endpoint_matches_running_resource_shape_fixture() -> None:
    """Bob includes resource-shapes but excludes the independent Pending control."""
    response = httpx.get(
        f"{base_url}/apis/canfar.net/v1alpha1/metrics/user/bob",
        timeout=30.0,
    )
    assert response.status_code == 200
    payload = response.json()
    assert payload["spec"] == {"user": "bob"}
    assert payload["status"]["runningPods"] == 1
    assert payload["status"]["resources"] == [
        {"name": "cpu", "requests": "0.21"},
        {"name": "memory", "requests": "0.101562Gi"},
    ]
    assert "pending-demand" not in response.text
    assert "resource-shapes" not in response.text
