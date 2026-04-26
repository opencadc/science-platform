"""Versioned HTTP routes for platform metrics."""

from __future__ import annotations

from datetime import UTC, datetime

from fastapi import APIRouter, Depends, Request, Response

from metrics.core.runtime import MetricsRuntime
from metrics.http_cache import metrics_success_cache_headers
from metrics.schemas.metrics import PlatformMetricsResponse, ResponseMetadata

router = APIRouter(tags=["metrics"])


def get_runtime(request: Request) -> MetricsRuntime:
    """Return the :class:`MetricsRuntime` stored on the app during lifespan."""
    return request.app.state.runtime


def _version(request: Request) -> str:
    return request.app.state.api_version


@router.get(
    "/api/v1/metrics/platform",
    response_model=PlatformMetricsResponse,
    summary="Get cluster-level platform metrics",
    description=(
        "Returns platform capacity and allocated resource maps from the configured "
        "Kueue source. HTTP caching is expressed via response headers, not JSON."
    ),
)
async def get_platform_metrics(
    request: Request,
    response: Response,
    runtime: MetricsRuntime = Depends(get_runtime),
) -> PlatformMetricsResponse:
    """Return cached or fresh platform metrics and set HTTP cache headers.

    Response freshness (``Date``, ``Cache-Control``, etc.) is carried in headers, not
    the JSON body, per the API contract.
    """
    result = await runtime.get_platform_metrics()
    request.state.metrics_cache_hit = result.cached
    now = datetime.now(UTC)
    for key, value in metrics_success_cache_headers(
        snapshot_created=result.created,
        configured_ttl=runtime.cache_ttl_seconds,
        shared_cache_public=request.app.state.cache_control_public,
        user_scoped=False,
        now=now,
    ).items():
        response.headers[key] = value
    return PlatformMetricsResponse(
        version=_version(request),
        metadata=ResponseMetadata(created=result.created),
        data=result.data,
    )
