"""HTTP routes for the CANFAR Metrics v1alpha1 API."""

from __future__ import annotations

import hashlib
import re
from datetime import UTC, datetime

from fastapi import APIRouter, Depends, Request, Response

from metrics.core.runtime import MetricsRuntime
from metrics.errors import AppError
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
from metrics.services.models import (
    PLATFORM_SUBJECT,
    CommunityObservation,
    MetricsSubject,
    PlatformObservation,
    UserObservation,
)

_LABEL_VALUE = re.compile(r"^[A-Za-z0-9](?:[-A-Za-z0-9_.]{0,61}[A-Za-z0-9])?$")

router = APIRouter(tags=["metrics"])


def get_runtime(request: Request) -> MetricsRuntime:
    """Return the runtime owned by the application lifespan."""
    return request.app.state.runtime


@router.get(
    "/apis/canfar.net/v1alpha1/metrics/platform/canfar",
    response_model=Metrics,
    response_model_exclude_none=True,
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
    if not isinstance(observation, PlatformObservation):
        raise RuntimeError("Platform route received a non-platform observation")
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


def _subject_value(value: str, kind: str) -> str:
    """Validate one decoded canonical Kubernetes label value."""
    if (
        not value
        or value in {".", ".."}
        or "/" in value
        or "\\" in value
        or "%" in value
        or not _LABEL_VALUE.fullmatch(value)
    ):
        raise AppError(
            code=f"invalid_{kind}",
            message=f"The requested {kind} is invalid",
            status_code=400,
        )
    return value


def _subject_name(kind: str, value: str) -> str:
    """Build a deterministic DNS-safe presentation name."""
    candidate = f"{kind}-{value.lower()}"
    if re.fullmatch(r"[a-z0-9](?:[-a-z0-9.]{0,61}[a-z0-9])?", candidate):
        return candidate
    slug = re.sub(r"[^a-z0-9]+", "-", value.lower()).strip("-") or "subject"
    digest = hashlib.sha256(value.encode()).hexdigest()[:8]
    return f"{kind}-{slug[:48].rstrip('-')}-{digest}"


@router.get(
    "/apis/canfar.net/v1alpha1/metrics/user/{user:path}",
    response_model=Metrics,
    response_model_exclude_none=True,
    responses={
        400: {"model": Status, "description": "Malformed user value."},
        503: {"model": Status, "description": "No serviceable report is available."},
    },
    summary="Get current user requests",
)
async def get_user_metrics(
    user: str,
    response: Response,
    runtime: MetricsRuntime = Depends(get_runtime),
) -> Metrics:
    """Return scheduler-effective requests held by one user's Running Pods."""
    user = _subject_value(user, "user")
    result = await runtime.metrics_service.get(MetricsSubject(kind="user", value=user))
    observation = result.observation
    if not isinstance(observation, UserObservation):
        raise RuntimeError("User route received a non-user observation")
    for key, value in metrics_success_cache_headers(
        snapshot_created=result.created,
        configured_ttl=runtime.metrics_service.user_cache_ttl_seconds,
        cached=result.cached,
        stale=result.stale,
        cache_available=result.cache_available,
        now=datetime.now(UTC),
    ).items():
        response.headers[key] = value
    ready_status, ready_reason = result.ready_condition
    cached_status, cached_reason = result.cached_condition
    return Metrics(
        metadata=ObjectMetadata(name=_subject_name("user", user)),
        spec=MetricsSpec(user=user),
        status=MetricsStatus(
            observed_at=result.created,
            running_pods=observation.running_pods,
            resources=[
                ResourceMetrics(name=name, requests=observation.requests[name])
                for name in sorted(observation.requests)
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


@router.get(
    "/apis/canfar.net/v1alpha1/metrics/community/{community:path}",
    response_model=Metrics,
    response_model_exclude_none=True,
    responses={
        400: {"model": Status, "description": "Malformed community value."},
        503: {"model": Status, "description": "No serviceable report is available."},
    },
    summary="Get current community requests",
)
async def get_community_metrics(
    community: str,
    response: Response,
    runtime: MetricsRuntime = Depends(get_runtime),
) -> Metrics:
    """Return requests held by one community's Running Pods."""
    community = _subject_value(community, "community")
    result = await runtime.metrics_service.get(MetricsSubject(kind="community", value=community))
    observation = result.observation
    if not isinstance(observation, CommunityObservation):
        raise RuntimeError("Community route received a non-community observation")
    for key, value in metrics_success_cache_headers(
        snapshot_created=result.created,
        configured_ttl=runtime.metrics_service.community_cache_ttl_seconds,
        cached=result.cached,
        stale=result.stale,
        cache_available=result.cache_available,
        now=datetime.now(UTC),
    ).items():
        response.headers[key] = value
    ready_status, ready_reason = result.ready_condition
    cached_status, cached_reason = result.cached_condition
    return Metrics(
        metadata=ObjectMetadata(name=_subject_name("community", community)),
        spec=MetricsSpec(community=community),
        status=MetricsStatus(
            observed_at=result.created,
            running_pods=observation.running_pods,
            resources=[
                ResourceMetrics(name=name, requests=observation.requests[name])
                for name in sorted(observation.requests)
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
