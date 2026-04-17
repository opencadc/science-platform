"""API routes for metrics endpoints."""

from __future__ import annotations

from fastapi import APIRouter, Depends, Request, Response

from metrics.models import (
    PlatformMetricsResponse,
    ResponseMetadata,
    SessionMetricsResponse,
    UserMetricsResponse,
)
from metrics.service import PlatformMetricsService

router = APIRouter(tags=["metrics"])


def get_service(request: Request) -> PlatformMetricsService:
    return request.app.state.platform_service


def _version(request: Request) -> str:
    return request.app.state.api_version


@router.get(
    "/api/v1/metrics/platform",
    response_model=PlatformMetricsResponse,
    summary="Get cluster-level platform metrics",
    description=(
        "Returns platform capacity and requested utilization, including "
        "provider source provenance and metric creation metadata."
    ),
)
async def get_platform_metrics(
    request: Request,
    response: Response,
    service: PlatformMetricsService = Depends(get_service),
) -> PlatformMetricsResponse:
    result = await service.get_platform_metrics()
    _apply_cache_headers(
        response=response,
        ttl=service.cache_ttl_seconds,
        cached=result.cached,
        public=request.app.state.cache_control_public,
    )
    return PlatformMetricsResponse(
        version=_version(request),
        metadata=ResponseMetadata(
            created=result.created,
            cached=result.cached,
            ttl=service.cache_ttl_seconds,
        ),
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
    description="Returns user-scoped requested resource usage and utilization.",
)
async def get_user_metrics(
    request: Request,
    response: Response,
    user: str,
    service: PlatformMetricsService = Depends(get_service),
) -> UserMetricsResponse:
    result = await service.get_user_metrics(user_id=user)
    _apply_cache_headers(
        response=response,
        ttl=service.cache_ttl_seconds,
        cached=result.cached,
        public=request.app.state.cache_control_public,
    )
    return UserMetricsResponse(
        version=_version(request),
        metadata=ResponseMetadata(
            created=result.created,
            ttl=service.cache_ttl_seconds,
            cached=result.cached,
        ),
        data=result.data,
    )


@router.get(
    "/api/v1/metrics/users/{user}/sessions/{uuid}",
    response_model=SessionMetricsResponse,
    summary="Get session-level metrics",
    description="Returns session-scoped requested resource usage and utilization.",
)
async def get_session_metrics(
    request: Request,
    response: Response,
    user: str,
    uuid: str,
    service: PlatformMetricsService = Depends(get_service),
) -> SessionMetricsResponse:
    result = await service.get_session_metrics(user_id=user, session_id=uuid)
    _apply_cache_headers(
        response=response,
        ttl=service.cache_ttl_seconds,
        cached=result.cached,
        public=request.app.state.cache_control_public,
    )
    return SessionMetricsResponse(
        version=_version(request),
        metadata=ResponseMetadata(
            created=result.created,
            ttl=service.cache_ttl_seconds,
            cached=result.cached,
        ),
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


def _apply_cache_headers(
    *,
    response: Response,
    ttl: int,
    cached: bool,
    public: bool,
) -> None:
    visibility = "public" if public else "private"
    response.headers["Cache-Control"] = f"{visibility}, max-age={max(ttl, 0)}"
    response.headers["X-Metrics-Cached"] = "true" if cached else "false"
