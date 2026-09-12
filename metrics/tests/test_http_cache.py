"""Focused tests for Metrics snapshot HTTP cache metadata."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from types import SimpleNamespace

from fastapi import FastAPI
from fastapi.testclient import TestClient

from metrics.api.v1alpha1.routes import router
from metrics.http_cache import metrics_success_cache_headers, remaining_freshness_seconds
from metrics.services.models import MetricsResult, PlatformObservation


def test_remaining_freshness_preserves_negative_stale_ttl() -> None:
    created = datetime(2026, 1, 1, 12, 0, 0, tzinfo=UTC)

    assert remaining_freshness_seconds(created, 60, now=created + timedelta(seconds=10)) == 50
    assert remaining_freshness_seconds(created, 5, now=created + timedelta(seconds=10)) == -5


def test_success_headers_identify_stale_and_unavailable_snapshots() -> None:
    created = datetime(2026, 1, 1, 12, 0, 0, tzinfo=UTC)

    stale = metrics_success_cache_headers(
        snapshot_created=created,
        configured_ttl=30,
        cached=True,
        stale=True,
        cache_available=True,
        now=created + timedelta(seconds=40),
    )
    unavailable = metrics_success_cache_headers(
        snapshot_created=created,
        configured_ttl=30,
        cached=True,
        stale=False,
        cache_available=False,
        now=created,
    )

    assert stale == {
        "Date": "Thu, 01 Jan 2026 12:00:40 GMT",
        "Cache-Control": "no-store",
        "Age": "40",
        "Cache-Status": "metrics; hit; ttl=-10",
    }
    assert unavailable["Cache-Control"] == "no-store"
    assert 'detail="redis-unavailable"' in unavailable["Cache-Status"]


def test_conditional_header_does_not_suppress_metrics_body() -> None:
    """A conditional request still receives the complete current report."""
    created = datetime(2026, 1, 1, 12, 0, 0, tzinfo=UTC)

    async def get_platform(_subject) -> MetricsResult:
        return MetricsResult(
            observation=PlatformObservation(
                cluster="cluster-a",
                capacity={"cpu": "4"},
                allocated={"cpu": "2"},
                reserving_workloads=3,
                observed_at=created,
            ),
            created=created,
            cached=True,
        )

    runtime = SimpleNamespace(
        metrics_service=SimpleNamespace(
            get=get_platform,
            cache_ttl_seconds=300,
        )
    )
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
    assert "last-modified" not in response.headers
    assert "etag" not in response.headers
