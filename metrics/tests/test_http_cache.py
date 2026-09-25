"""Focused tests for Metrics snapshot HTTP cache metadata."""

from __future__ import annotations

from datetime import UTC, datetime
from types import SimpleNamespace

from fastapi import FastAPI
from fastapi.testclient import TestClient

from metrics.api.v1alpha1.routes import router
from metrics.http_cache import metrics_success_cache_headers
from metrics.services.models import CachedSnapshot, PlatformObservation, Report


def test_success_headers_report_age_and_remaining_fresh_time() -> None:
    assert metrics_success_cache_headers(
        age_seconds=0.2, fresh_seconds=300, cached=False, cache_available=True
    ) == {
        "Cache-Control": "no-store",
        "Age": "0",
        "Cache-Status": "metrics; fwd=uri-miss; ttl=299",
    }
    assert (
        metrics_success_cache_headers(
            age_seconds=40.9, fresh_seconds=300, cached=True, cache_available=True
        )["Cache-Status"]
        == "metrics; hit; ttl=259"
    )


def test_ttl_turns_negative_as_soon_as_the_fresh_window_ends() -> None:
    headers = metrics_success_cache_headers(
        age_seconds=300.2, fresh_seconds=300, cached=True, cache_available=True
    )
    assert headers["Age"] == "300" and headers["Cache-Status"] == "metrics; hit; ttl=-1"


def test_success_headers_identify_stale_and_unavailable_snapshots() -> None:
    stale = metrics_success_cache_headers(
        age_seconds=40, fresh_seconds=30, cached=True, cache_available=True
    )
    unavailable = metrics_success_cache_headers(
        age_seconds=5, fresh_seconds=30, cached=True, cache_available=False
    )

    assert stale == {
        "Cache-Control": "no-store",
        "Age": "40",
        "Cache-Status": "metrics; hit; ttl=-10",
    }
    assert "Date" not in stale  # the ASGI server owns the single Date header
    assert unavailable["Cache-Status"] == 'metrics; hit; ttl=25; detail="redis-unavailable"'


def test_conditional_header_does_not_suppress_metrics_body() -> None:
    """A conditional request still receives the complete current report."""
    created = datetime(2026, 1, 1, 12, 0, 0, tzinfo=UTC)

    async def get_platform(_subject) -> Report:
        observation = PlatformObservation(
            cluster="cluster-a",
            capacity={"cpu": "4"},
            allocated={"cpu": "2"},
            reserving_workloads=3,
            observed_at=created,
        )
        return Report(
            snapshot=CachedSnapshot(observation=observation, created=created),
            cached=True,
            stale=False,
            cache_available=True,
            age_seconds=12,
            fresh_seconds=300,
        )

    runtime = SimpleNamespace(metrics_service=SimpleNamespace(get=get_platform))
    app = FastAPI()
    app.state.runtime = runtime
    app.include_router(router)

    with TestClient(app) as client:
        response = client.get(
            "/apis/canfar.net/v1alpha1/metrics/platform/canfar",
            headers={"If-Modified-Since": "Thu, 01 Jan 2099 00:00:00 GMT"},
        )

    assert response.status_code == 200
    assert response.json()["kind"] == "Metrics"
    assert response.json()["status"]["reservingWorkloads"] == 3
    assert response.headers["cache-control"] == "no-store"
    assert response.headers["age"] == "12"
    assert "last-modified" not in response.headers
    assert "etag" not in response.headers
