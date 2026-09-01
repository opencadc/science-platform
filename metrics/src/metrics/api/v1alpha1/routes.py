"""HTTP routes for the CANFAR Metrics v1alpha1 API."""

from __future__ import annotations

import hashlib
import re
from datetime import UTC, datetime
from decimal import Decimal
from typing import Annotated, Literal

from fastapi import APIRouter, Depends, Path, Request, Response

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
    CommunityObservation,
    EfficiencyObservation,
    MetricsResult,
    MetricsSubject,
    PlatformObservation,
    SessionObservation,
    UserObservation,
    bounded_decimal,
)


_LABEL_VALUE_PATTERN = r"^[A-Za-z0-9](?:[-A-Za-z0-9_.]{0,61}[A-Za-z0-9])?$"
_LABEL_VALUE = re.compile(_LABEL_VALUE_PATTERN)
_DNS_LABEL = re.compile(r"^[a-z0-9](?:[-a-z0-9]{0,61}[a-z0-9])?$")
_METADATA_NAME_MAX_LENGTH = 63
_SUBJECT_DIGEST_LENGTH = 12

SubjectPath = Annotated[
    str,
    Path(
        min_length=1,
        max_length=63,
        pattern=_LABEL_VALUE_PATTERN,
        description="A Kubernetes label value identifying the requested subject.",
    ),
]

router = APIRouter(tags=["metrics"])


def _set_cache_headers(
    *,
    response: Response,
    created: datetime,
    ttl: int,
    cached: bool,
    stale: bool,
    available: bool,
) -> None:
    """Attach internal snapshot metadata to a successful response."""
    headers = metrics_success_cache_headers(
        snapshot_created=created,
        configured_ttl=ttl,
        cached=cached,
        stale=stale,
        cache_available=available,
        now=datetime.now(UTC),
    )
    response.headers.update(headers)


def get_runtime(request: Request) -> MetricsRuntime:
    """Resolve the lifespan-owned runtime for dependency injection."""
    return request.app.state.runtime


RuntimeDependency = Annotated[MetricsRuntime, Depends(get_runtime)]


def _subject_value(value: str, kind: str) -> str:
    """Validate one exact decoded path value as a Kubernetes label value."""
    if (
        not value
        or value in {".", ".."}
        or "/" in value
        or "\\" in value
        or "%" in value
        or _LABEL_VALUE.fullmatch(value) is None
    ):
        raise AppError(code=f"invalid_{kind}", status_code=400)
    return value


def _subject_name(kind: str, value: str) -> str:
    """Build a deterministic DNS-safe report metadata name."""
    normalized = value.lower()
    candidate = f"{kind}-{normalized}"
    if (
        value == normalized
        and len(candidate) <= _METADATA_NAME_MAX_LENGTH
        and _DNS_LABEL.fullmatch(candidate)
    ):
        return candidate
    slug = re.sub(r"[^a-z0-9]+", "-", normalized).strip("-") or "subject"
    digest = hashlib.sha256(value.encode()).hexdigest()[:_SUBJECT_DIGEST_LENGTH]
    available = _METADATA_NAME_MAX_LENGTH - len(kind) - 2 - len(digest)
    return f"{kind}-{slug[:available].rstrip('-') or 'subject'}-{digest}"


def _decimal_string(value: Decimal) -> str:
    """Serialize a bounded Decimal without exponent notation."""
    rendered = format(bounded_decimal(value), "f")
    return rendered.rstrip("0").rstrip(".") if "." in rendered else rendered


def _efficiency_value(efficiency: EfficiencyObservation | None, name: str) -> str | None:
    """Return one optional attributed efficiency value."""
    if efficiency is None or name not in efficiency.efficiencies:
        return None
    try:
        return _decimal_string(efficiency.efficiencies[name])
    except ValueError as exc:
        raise AppError(code="invalid_efficiency_data", status_code=503) from exc


def _workload_resources(
    observation: UserObservation | CommunityObservation | SessionObservation,
    efficiency: EfficiencyObservation | None,
    *,
    usage: dict[str, str] | None = None,
) -> list[ResourceMetrics]:
    """Build workload resource entries from queue or session reservations."""
    return [
        ResourceMetrics(
            name=name,
            requests=observation.requests[name],
            usage=usage.get(name) if usage else None,
            efficiency=_efficiency_value(efficiency, name),
        )
        for name in sorted(observation.requests)
    ]


def _platform_resources(
    observation: PlatformObservation,
    efficiency: EfficiencyObservation | None,
) -> list[ResourceMetrics]:
    """Build Platform resource entries from capacity and allocation."""
    return [
        ResourceMetrics(
            name=name,
            capacity=observation.capacity[name],
            allocated=observation.allocated[name],
            efficiency=_efficiency_value(efficiency, name),
        )
        for name in sorted(observation.capacity)
    ]


def _conditions(result: MetricsResult) -> list[Condition]:
    """Build exactly the Ready and Cached conditions for one result."""
    ready_status, ready_reason = result.ready_condition
    cached_status, cached_reason = result.cached_condition
    return [
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
    ]


def _subject_response(
    kind: str,
    value: str,
    observation: UserObservation | CommunityObservation | SessionObservation,
    result: MetricsResult,
) -> Metrics:
    """Assemble one User, Community, or Session response envelope."""
    spec: MetricsSpec
    if kind == "user":
        spec = MetricsSpec(user=value)
    elif kind == "community":
        spec = MetricsSpec(community=value)
    else:
        spec = MetricsSpec(session=value)
    return Metrics(
        metadata=ObjectMetadata(name=_subject_name(kind, value)),
        spec=spec,
        status=MetricsStatus(
            observed_at=result.created,
            reserving_workloads=observation.reserving_workloads,
            resources=_workload_resources(observation, result.efficiency, usage=result.usage),
            conditions=_conditions(result),
        ),
    )


def _ttl_seconds(runtime: MetricsRuntime, kind: str) -> int:
    """Return the fresh-cache window for one report surface."""
    if kind == "platform":
        return runtime.metrics_service.cache_ttl_seconds
    if kind == "user":
        return runtime.metrics_service.user_cache_ttl_seconds
    if kind == "community":
        return runtime.metrics_service.community_cache_ttl_seconds
    return runtime.metrics_service.session_cache_ttl_seconds


async def _serve(
    kind: Literal["platform", "user", "community", "session"],
    value: str,
    response: Response,
    runtime: MetricsRuntime,
) -> Metrics:
    """Load one subject report and attach cache metadata headers."""
    value = _subject_value(value, kind)
    result = await runtime.metrics_service.get(MetricsSubject(kind=kind, value=value))
    _set_cache_headers(
        response=response,
        created=result.created,
        ttl=_ttl_seconds(runtime, kind),
        cached=result.cached,
        stale=result.stale,
        available=result.cache_available,
    )
    observation = result.observation
    if kind == "platform":
        if not isinstance(observation, PlatformObservation):
            raise RuntimeError("Platform route received a non-platform observation")
        return Metrics(
            metadata=ObjectMetadata(name=_subject_name("platform", value)),
            spec=MetricsSpec(platform=value),
            status=MetricsStatus(
                observed_at=result.created,
                reserving_workloads=observation.reserving_workloads,
                resources=_platform_resources(observation, result.efficiency),
                conditions=_conditions(result),
            ),
        )
    if kind == "user":
        if not isinstance(observation, UserObservation):
            raise RuntimeError("User route received a non-user observation")
        return _subject_response("user", value, observation, result)
    if kind == "community":
        if not isinstance(observation, CommunityObservation):
            raise RuntimeError("Community route received a non-community observation")
        return _subject_response("community", value, observation, result)
    if not isinstance(observation, SessionObservation):
        raise RuntimeError("Session route received a non-session observation")
    return _subject_response("session", value, observation, result)


@router.get(
    "/apis/canfar.net/v1alpha1/metrics/session/{session_id:path}",
    response_model=Metrics,
    response_model_exclude_none=True,
    responses={
        400: {"model": Status, "description": "Malformed session id value."},
        404: {"model": Status, "description": "No matching Job exists."},
        405: {"model": Status, "description": "The HTTP method is not allowed."},
        500: {"model": Status, "description": "The metrics report could not be produced."},
        503: {"model": Status, "description": "No serviceable report is available."},
    },
    summary="Get session metrics",
)
async def get_session_metrics(
    session_id: SubjectPath,
    response: Response,
    runtime: RuntimeDependency,
) -> Metrics:
    """Return Job reservations, optional usage, and optional duration efficiency."""
    return await _serve("session", session_id, response, runtime)


@router.get(
    "/apis/canfar.net/v1alpha1/metrics/platform/{platform:path}",
    response_model=Metrics,
    response_model_exclude_none=True,
    responses={
        400: {"model": Status, "description": "Malformed platform value."},
        404: {"model": Status, "description": "Platform is not configured."},
        405: {"model": Status, "description": "The HTTP method is not allowed."},
        500: {"model": Status, "description": "The metrics report could not be produced."},
        503: {"model": Status, "description": "No serviceable report is available."},
    },
    summary="Get platform metrics",
)
async def get_platform_metrics(
    platform: SubjectPath,
    response: Response,
    runtime: RuntimeDependency,
) -> Metrics:
    """Return configured ClusterQueue capacity, allocation, and queue state."""
    return await _serve("platform", platform, response, runtime)


@router.get(
    "/apis/canfar.net/v1alpha1/metrics/user/{user:path}",
    response_model=Metrics,
    response_model_exclude_none=True,
    responses={
        400: {"model": Status, "description": "Malformed user value."},
        404: {"model": Status, "description": "No matching LocalQueue exists."},
        405: {"model": Status, "description": "The HTTP method is not allowed."},
        500: {"model": Status, "description": "The metrics report could not be produced."},
        503: {"model": Status, "description": "No serviceable report is available."},
    },
    summary="Get current user queue metrics",
)
async def get_user_metrics(
    user: SubjectPath,
    response: Response,
    runtime: RuntimeDependency,
) -> Metrics:
    """Return LocalQueue reservations for one user."""
    return await _serve("user", user, response, runtime)


@router.get(
    "/apis/canfar.net/v1alpha1/metrics/community/{community:path}",
    response_model=Metrics,
    response_model_exclude_none=True,
    responses={
        400: {"model": Status, "description": "Malformed community value."},
        404: {"model": Status, "description": "No matching ClusterQueue exists."},
        405: {"model": Status, "description": "The HTTP method is not allowed."},
        500: {"model": Status, "description": "The metrics report could not be produced."},
        503: {"model": Status, "description": "No serviceable report is available."},
    },
    summary="Get current community queue metrics",
)
async def get_community_metrics(
    community: SubjectPath,
    response: Response,
    runtime: RuntimeDependency,
) -> Metrics:
    """Return reservation and reserving counts for one community."""
    return await _serve("community", community, response, runtime)
