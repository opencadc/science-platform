"""HTTP routes for the CANFAR Metrics v1alpha1 API."""

from __future__ import annotations

from datetime import UTC, datetime

from fastapi import APIRouter, Depends, Request, Response

from metrics.core.runtime import MetricsRuntime
from metrics.http_cache import metrics_success_cache_headers
from metrics.schemas.metrics import (
    Condition,
    Metrics,
    MetricsSpec,
    MetricsStatus,
    ObjectMetadata,
    ResourceMetrics,
)
from metrics.schemas.status import Status
from metrics.services.models import PLATFORM_SUBJECT

router = APIRouter(tags=["metrics"])


def get_runtime(request: Request) -> MetricsRuntime:
    """Return the runtime owned by the application lifespan."""
    return request.app.state.runtime


@router.get(
    "/apis/canfar.net/v1alpha1/metrics/platform/canfar",
    response_model=Metrics,
    responses={503: {"model": Status, "description": "No serviceable report is available."}},
    summary="Get CANFAR platform metrics",
)
async def get_platform_metrics(
    response: Response,
    runtime: MetricsRuntime = Depends(get_runtime),
) -> Metrics:
    """Return Kueue-backed platform capacity and admitted allocation."""
    result = await runtime.metrics_service.get(PLATFORM_SUBJECT)
    for key, value in metrics_success_cache_headers(
        snapshot_created=result.created,
        configured_ttl=runtime.metrics_service.cache_ttl_seconds,
        cached=result.cached,
        stale=result.stale,
        cache_available=result.cache_available,
        now=datetime.now(UTC),
    ).items():
        response.headers[key] = value

    ready_status, ready_reason = result.ready_condition
    cached_status, cached_reason = result.cached_condition
    observation = result.observation
    return Metrics(
        metadata=ObjectMetadata(name="platform-canfar"),
        spec=MetricsSpec(platform="canfar"),
        status=MetricsStatus(
            observed_at=result.created,
            resources=[
                ResourceMetrics(
                    name=name,
                    capacity=observation.capacity[name],
                    allocated=observation.allocated[name],
                )
                for name in sorted(observation.capacity)
            ],
            conditions=[
                Condition(
                    type="Ready",
                    status=ready_status,
                    reason=ready_reason,
                    last_transition_time=result.created,
                ),
                Condition(
                    type="Cached",
                    status=cached_status,
                    reason=cached_reason,
                    last_transition_time=result.created,
                ),
            ],
        ),
    )
