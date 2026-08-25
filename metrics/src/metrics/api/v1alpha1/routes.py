"""HTTP routes for the CANFAR Metrics v1alpha1 API."""

from __future__ import annotations

import hashlib
import re
from datetime import UTC, datetime
from decimal import Decimal, ROUND_HALF_UP
from email.utils import parsedate_to_datetime

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
    AccountingState,
    CommunityObservation,
    MetricsResult,
    MetricsSubject,
    PlatformObservation,
    UserObservation,
)

_LABEL_VALUE = re.compile(r"^[A-Za-z0-9](?:[-A-Za-z0-9_.]{0,61}[A-Za-z0-9])?$")

router = APIRouter(tags=["metrics"])
_EFFICIENCY_QUANTUM = Decimal("0.000001")


def _cache_response(
    *,
    request: Request,
    response: Response,
    created: datetime,
    ttl: int,
    cached: bool,
    stale: bool,
    available: bool,
) -> Response | None:
    """Set snapshot headers and return an empty conditional response on a hit."""
    headers = metrics_success_cache_headers(
        snapshot_created=created,
        configured_ttl=ttl,
        cached=cached,
        stale=stale,
        cache_available=available,
        now=datetime.now(UTC),
    )
    response.headers.update(headers)
    validator = request.headers.get("if-modified-since")
    if validator:
        try:
            modified_since = parsedate_to_datetime(validator)
        except (TypeError, ValueError):
            return None
        snapshot_second = created.astimezone(UTC).replace(microsecond=0)
        if modified_since.astimezone(UTC) >= snapshot_second:
            return Response(status_code=304, headers=headers)
    return None


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
    request: Request,
    response: Response,
    runtime: MetricsRuntime = Depends(get_runtime),
) -> Metrics | Response:
    """Return Kueue-backed platform capacity and admitted allocation."""
    result = await runtime.metrics_service.get(PLATFORM_SUBJECT)
    conditional = _cache_response(
        request=request,
        response=response,
        created=result.created,
        ttl=runtime.metrics_service.cache_ttl_seconds,
        cached=result.cached,
        stale=result.stale,
        available=result.cache_available,
    )
    if conditional is not None:
        return conditional

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


def _decimal_string(value: Decimal) -> str:
    """Serialize a finite decimal without exponent notation or trailing zeros."""
    rendered = format(value, "f")
    return rendered.rstrip("0").rstrip(".") if "." in rendered else rendered


def _subject_resources(
    observation: UserObservation | CommunityObservation,
) -> list[ResourceMetrics]:
    """Combine current requests with complete additive lifetime accounting."""
    accounting = observation.accounting
    names = set(observation.requests)
    if accounting is not None:
        names.update(accounting.resources)
    resources = []
    for name in sorted(names):
        hours = accounting.resources.get(name) if accounting is not None else None
        efficiency = None
        if hours is not None and hours.requested != 0:
            efficiency = _decimal_string(
                (hours.usage / hours.requested).quantize(
                    _EFFICIENCY_QUANTUM,
                    rounding=ROUND_HALF_UP,
                )
            )
        resources.append(
            ResourceMetrics(
                name=name,
                requests=observation.requests.get(name),
                usage_hours=_decimal_string(hours.usage) if hours is not None else None,
                requested_hours=(_decimal_string(hours.requested) if hours is not None else None),
                efficiency=efficiency,
            )
        )
    return resources


def _subject_response(
    kind: str,
    value: str,
    observation: UserObservation | CommunityObservation,
    result: MetricsResult,
) -> Metrics:
    """Assemble the shared user/community response contract."""
    ready_status, ready_reason = result.ready_condition
    cached_status, cached_reason = result.cached_condition
    return Metrics(
        metadata=ObjectMetadata(name=_subject_name(kind, value)),
        spec=MetricsSpec(user=value) if kind == "user" else MetricsSpec(community=value),
        status=MetricsStatus(
            observed_at=result.created,
            accounting_period=(
                "ActiveWorkloadLifetime"
                if observation.accounting_state is not AccountingState.DISABLED
                else None
            ),
            running_pods=observation.running_pods,
            resources=_subject_resources(observation),
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
    request: Request,
    response: Response,
    runtime: MetricsRuntime = Depends(get_runtime),
) -> Metrics | Response:
    """Return scheduler-effective requests held by one user's Running Pods."""
    user = _subject_value(user, "user")
    result = await runtime.metrics_service.get(MetricsSubject(kind="user", value=user))
    observation = result.observation
    if not isinstance(observation, UserObservation):
        raise RuntimeError("User route received a non-user observation")
    conditional = _cache_response(
        request=request,
        response=response,
        created=result.created,
        ttl=runtime.metrics_service.user_cache_ttl_seconds,
        cached=result.cached,
        stale=result.stale,
        available=result.cache_available,
    )
    if conditional is not None:
        return conditional
    return _subject_response("user", user, observation, result)


@router.get(
    "/apis/canfar.net/v1alpha1/metrics/community/{community:path}",
    response_model=Metrics,
    response_model_exclude_none=True,
    responses={
        400: {"model": Status, "description": "Malformed community value."},
        503: {"model": Status, "description": "No serviceable report is available."},
    },
    summary="Get community requests and lifetime accounting",
)
async def get_community_metrics(
    community: str,
    request: Request,
    response: Response,
    runtime: MetricsRuntime = Depends(get_runtime),
) -> Metrics | Response:
    """Return requests and lifetime accounting for one community's Running Pods."""
    community = _subject_value(community, "community")
    result = await runtime.metrics_service.get(MetricsSubject(kind="community", value=community))
    observation = result.observation
    if not isinstance(observation, CommunityObservation):
        raise RuntimeError("Community route received a non-community observation")
    conditional = _cache_response(
        request=request,
        response=response,
        created=result.created,
        ttl=runtime.metrics_service.community_cache_ttl_seconds,
        cached=result.cached,
        stale=result.stale,
        available=result.cache_available,
    )
    if conditional is not None:
        return conditional
    return _subject_response("community", community, observation, result)
