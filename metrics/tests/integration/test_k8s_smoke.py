"""Live smoke tests for the queue-backed Metrics API."""

from __future__ import annotations

import os
from collections.abc import Iterator

import httpx
import pytest


pytestmark = [
    pytest.mark.integration,
    pytest.mark.skipif(
        not os.getenv("METRICS_BASE_URL"),
        reason="METRICS_BASE_URL not configured",
    ),
]

_BASE_URL = os.getenv("METRICS_BASE_URL", "")
_PLATFORM = os.getenv("METRICS_TEST_PLATFORM", "canfar")
_USER = os.getenv("METRICS_TEST_USER")
_COMMUNITY = os.getenv("METRICS_TEST_COMMUNITY")


@pytest.fixture
def client() -> Iterator[httpx.Client]:
    """Create one short-lived client for a live smoke test."""
    with httpx.Client(base_url=_BASE_URL, timeout=15.0) as current:
        yield current


def _assert_report(
    response: httpx.Response,
    *,
    kind: str,
    subject_key: str,
    subject: str,
    resource_key: str,
) -> dict[str, object]:
    """Validate the stable queue-only response contract."""
    assert response.status_code == 200, response.text
    payload = response.json()
    assert payload["apiVersion"] == "canfar.net/v1alpha1"
    assert payload["kind"] == "Metrics"
    assert payload["spec"] == {subject_key: subject}

    status = payload["status"]
    assert set(status) == {"observedAt", "reservingWorkloads", "resources", "conditions"}
    assert isinstance(status["reservingWorkloads"], int)
    assert {condition["type"] for condition in status["conditions"]} == {"Ready", "Cached"}
    for resource in status["resources"]:
        assert resource[resource_key] is not None
        assert "runningPods" not in resource
        assert "usageHours" not in resource
        assert "requestedHours" not in resource
        assert "accountingPeriod" not in resource
    return payload


def _assert_ready_efficiency(payload: dict[str, object]) -> None:
    """Require the disposable PromQL fixture to return CPU and memory efficiency."""
    conditions = payload["status"]["conditions"]
    ready = next(condition for condition in conditions if condition["type"] == "Ready")
    assert ready["status"] == "True"
    resources = {resource["name"]: resource for resource in payload["status"]["resources"]}
    assert resources["cpu"]["efficiency"] is not None
    assert resources["memory"]["efficiency"] is not None


def test_platform_report_has_successful_cpu_and_memory_efficiency(client: httpx.Client) -> None:
    """The disposable KSM/cAdvisor PromQL fixture proves one complete result."""
    response = client.get(f"/apis/canfar.net/v1alpha1/metrics/platform/{_PLATFORM}")
    payload = _assert_report(
        response,
        kind="platform",
        subject_key="platform",
        subject=_PLATFORM,
        resource_key="capacity",
    )
    _assert_ready_efficiency(payload)


def test_health_and_platform_report(client: httpx.Client) -> None:
    """The live service is healthy and exposes queue-derived platform data."""
    health = client.get("/livez")
    assert health.status_code == 200
    assert health.json() == {"status": "ok"}

    first = client.get(f"/apis/canfar.net/v1alpha1/metrics/platform/{_PLATFORM}")
    _assert_report(
        first,
        kind="platform",
        subject_key="platform",
        subject=_PLATFORM,
        resource_key="capacity",
    )
    assert first.headers["Cache-Control"] == "no-store"
    assert "metrics;" in first.headers["Cache-Status"]

    second = client.get(f"/apis/canfar.net/v1alpha1/metrics/platform/{_PLATFORM}")
    _assert_report(
        second,
        kind="platform",
        subject_key="platform",
        subject=_PLATFORM,
        resource_key="capacity",
    )


@pytest.mark.parametrize(
    ("subject", "subject_key", "kind", "resource_key", "path_kind"),
    [
        (_USER, "user", "user", "requests", "user"),
        (_COMMUNITY, "community", "community", "requests", "community"),
    ],
)
def test_configured_workload_report(
    client: httpx.Client,
    subject: str | None,
    subject_key: str,
    kind: str,
    resource_key: str,
    path_kind: str,
) -> None:
    """Configured workload surfaces expose LocalQueue or ClusterQueue state."""
    if subject is None:
        pytest.skip(f"set METRICS_TEST_{subject_key.upper()} to exercise this surface")
    response = client.get(f"/apis/canfar.net/v1alpha1/metrics/{path_kind}/{subject}")
    _assert_report(
        response,
        kind=kind,
        subject_key=subject_key,
        subject=subject,
        resource_key=resource_key,
    )


def test_unknown_subject_is_a_sanitized_not_found(client: httpx.Client) -> None:
    """A subject with no matching queue is a stable Kubernetes Status 404."""
    response = client.get(
        "/apis/canfar.net/v1alpha1/metrics/user/metrics-smoke-no-such-user",
    )
    assert response.status_code == 404
    assert response.headers["Cache-Control"] == "no-store"
    assert response.json() == {
        "apiVersion": "v1",
        "kind": "Status",
        "status": "Failure",
        "reason": "NotFound",
        "message": "The requested resource was not found.",
        "code": 404,
    }
