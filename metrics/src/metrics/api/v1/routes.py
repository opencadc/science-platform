"""Versioned HTTP routes for platform, user, and session metrics."""

from __future__ import annotations

from datetime import UTC, datetime

from fastapi import APIRouter, Depends, Request, Response

from metrics.http_cache import metrics_success_cache_headers
from metrics.schemas.metrics import (
    PlatformMetricsResponse,
    ResponseMetadata,
    SessionMetricsResponse,
    UserMetricsResponse,
)
from metrics.services.platform_metrics import PlatformMetricsService

router = APIRouter(tags=["metrics"])


def get_service(request: Request) -> PlatformMetricsService:
    """FastAPI dependency that returns the process-wide metrics service instance."""
    return request.app.state.platform_service


def _version(request: Request) -> str:
    """API group/version string embedded in JSON envelopes."""
    return request.app.state.api_version


@router.get(
    "/api/v1/metrics/platform",
    response_model=PlatformMetricsResponse,
    summary="Get cluster-level platform metrics",
    description=(
        "Returns platform capacity and allocated resource maps from configured "
        "Kueue sources. HTTP caching is expressed via response headers, not JSON."
    ),
)
async def get_platform_metrics(
    request: Request,
    response: Response,
    service: PlatformMetricsService = Depends(get_service),
) -> PlatformMetricsResponse:
    result = await service.get_platform_metrics()
    request.state.metrics_cache_hit = result.cached
    now = datetime.now(UTC)
    for key, value in metrics_success_cache_headers(
        snapshot_created=result.created,
        configured_ttl=service.cache_ttl_seconds,
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


@router.get(
    "/metrics",
    response_model=PlatformMetricsResponse,
    include_in_schema=False,
)
async def get_platform_metrics_alias(
    request: Request,
    response: Response,
    service: PlatformMetricsService = Depends(get_service),
) -> PlatformMetricsResponse:
    return await get_platform_metrics(
        request=request, response=response, service=service
    )


@router.get(
    "/api/v1/metrics/users/{user}",
    response_model=UserMetricsResponse,
    summary="Get user-level metrics",
    description=(
        "Returns user-scoped requested resource usage and utilization. "
        "Responses use ``Cache-Control: private`` (not for shared caches)."
    ),
)
async def get_user_metrics(
    request: Request,
    response: Response,
    user: str,
    service: PlatformMetricsService = Depends(get_service),
) -> UserMetricsResponse:
    result = await service.get_user_metrics(user_id=user)
    request.state.metrics_cache_hit = result.cached
    now = datetime.now(UTC)
    for key, value in metrics_success_cache_headers(
        snapshot_created=result.created,
        configured_ttl=service.cache_ttl_seconds,
        shared_cache_public=request.app.state.cache_control_public,
        user_scoped=True,
        now=now,
    ).items():
        response.headers[key] = value
    return UserMetricsResponse(
        version=_version(request),
        metadata=ResponseMetadata(created=result.created),
        data=result.data,
    )


@router.get(
    "/api/v1/metrics/users/{user}/sessions/{uuid}",
    response_model=SessionMetricsResponse,
    summary="Get session-level metrics",
    description=(
        "Returns session-scoped requested resource usage and utilization. "
        "Responses use ``Cache-Control: private`` (not for shared caches)."
    ),
)
async def get_session_metrics(
    request: Request,
    response: Response,
    user: str,
    uuid: str,
    service: PlatformMetricsService = Depends(get_service),
) -> SessionMetricsResponse:
    result = await service.get_session_metrics(user_id=user, session_id=uuid)
    request.state.metrics_cache_hit = result.cached
    now = datetime.now(UTC)
    for key, value in metrics_success_cache_headers(
        snapshot_created=result.created,
        configured_ttl=service.cache_ttl_seconds,
        shared_cache_public=request.app.state.cache_control_public,
        user_scoped=True,
        now=now,
    ).items():
        response.headers[key] = value
    return SessionMetricsResponse(
        version=_version(request),
        metadata=ResponseMetadata(created=result.created),
        data=result.data,
    )


@router.get("/metrics/{user}", include_in_schema=False)
async def get_user_metrics_alias(
    request: Request,
    response: Response,
    user: str,
    service: PlatformMetricsService = Depends(get_service),
) -> UserMetricsResponse:
    return await get_user_metrics(
        request=request,
        response=response,
        user=user,
        service=service,
    )


@router.get("/metrics/{user}/{session_id}", include_in_schema=False)
async def get_session_metrics_alias(
    request: Request,
    response: Response,
    user: str,
    session_id: str,
    service: PlatformMetricsService = Depends(get_service),
) -> SessionMetricsResponse:
    return await get_session_metrics(
        request=request,
        response=response,
        user=user,
        uuid=session_id,
        service=service,
    )
