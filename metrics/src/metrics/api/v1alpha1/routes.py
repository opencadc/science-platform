"""HTTP routes for the CANFAR Metrics v1alpha1 API."""

from __future__ import annotations

import hashlib
import re
from typing import Annotated, Literal

from fastapi import APIRouter, Depends, Path, Request, Response

from metrics.core.runtime import MetricsRuntime
from metrics.http_cache import metrics_success_cache_headers
from metrics.names import DNS_LABEL, LABEL_VALUE_PATTERN
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
    CachedSnapshot,
    CommunityObservation,
    MetricsSubject,
    PlatformObservation,
    Report,
    SessionObservation,
    UserObservation,
)
from metrics.services.resources import plain_decimal

_METADATA_NAME_MAX_LENGTH = 63
_SUBJECT_DIGEST_LENGTH = 12

SubjectPath = Annotated[
    str,
    Path(
        min_length=1,
        max_length=63,
        pattern=LABEL_VALUE_PATTERN,
        description="A Kubernetes label value identifying the requested subject.",
    ),
]

router = APIRouter(tags=["metrics"])


async def get_runtime(request: Request) -> MetricsRuntime:
    """Resolve the lifespan-owned runtime for dependency injection."""
    return request.app.state.runtime


RuntimeDependency = Annotated[MetricsRuntime, Depends(get_runtime)]


def _subject_name(kind: str, value: str) -> str:
    """Build a deterministic DNS-safe report metadata name."""
    normalized = value.lower()
    candidate = f"{kind}-{normalized}"
    if (
        value == normalized
        and len(candidate) <= _METADATA_NAME_MAX_LENGTH
        and DNS_LABEL.fullmatch(candidate)
    ):
        return candidate
    slug = re.sub(r"[^a-z0-9]+", "-", normalized).strip("-") or "subject"
    digest = hashlib.sha256(value.encode()).hexdigest()[:_SUBJECT_DIGEST_LENGTH]
    available = _METADATA_NAME_MAX_LENGTH - len(kind) - 2 - len(digest)
    return f"{kind}-{slug[:available].rstrip('-') or 'subject'}-{digest}"


def _efficiency(snapshot: CachedSnapshot, name: str) -> str | None:
    """Return one optional efficiency ratio as a plain decimal."""
    if snapshot.efficiency is None or name not in snapshot.efficiency.efficiencies:
        return None
    return plain_decimal(snapshot.efficiency.efficiencies[name])


def _resources(snapshot: CachedSnapshot) -> list[ResourceMetrics]:
    """Build the resource entries for any surface's observation."""
    observation = snapshot.observation
    if isinstance(observation, PlatformObservation):
        return [
            ResourceMetrics(
                name=name,
                capacity=observation.capacity[name],
                allocated=observation.allocated[name],
                efficiency=_efficiency(snapshot, name),
            )
            for name in sorted(observation.capacity)
        ]
    usage = snapshot.usage or {}
    return [
        ResourceMetrics(
            name=name,
            requests=observation.requests[name],
            usage=usage.get(name),
            efficiency=_efficiency(snapshot, name),
        )
        for name in sorted(observation.requests)
    ]


def _conditions(report: Report) -> list[Condition]:
    """Build exactly the Ready and Cached conditions for one report.

    Ready is ``False`` for a stale snapshot (``StaleData``) or when an
    optional source failed (``PartialData``); stale wins. Cached reports
    whether the snapshot was reused, refreshed, or served while Redis was
    unavailable.
    """
    if report.stale:
        ready = ("False", "StaleData")
    elif report.snapshot.partial:
        ready = ("False", "PartialData")
    else:
        ready = ("True", "Available")
    if not report.cache_available:
        cached = ("Unknown", "RedisUnavailable")
    elif report.stale:
        cached = ("True", "StaleHit")
    elif report.cached:
        cached = ("True", "FreshHit")
    else:
        cached = ("False", "Refreshed")
    created = report.snapshot.created
    return [
        Condition(type="Ready", status=ready[0], reason=ready[1], last_transition_time=created),
        Condition(type="Cached", status=cached[0], reason=cached[1], last_transition_time=created),
    ]


_SURFACE_TYPES = {
    "platform": PlatformObservation,
    "user": UserObservation,
    "community": CommunityObservation,
    "session": SessionObservation,
}


async def _serve(
    kind: Literal["platform", "user", "community", "session"],
    value: str,
    response: Response,
    runtime: MetricsRuntime,
) -> Metrics:
    """Load one subject report and attach cache metadata headers."""
    report = await runtime.metrics_service.get(MetricsSubject(kind=kind, value=value))
    snapshot = report.snapshot
    if not isinstance(snapshot.observation, _SURFACE_TYPES[kind]):
        raise RuntimeError(f"{kind} route received another surface's observation")
    response.headers.update(
        metrics_success_cache_headers(
            age_seconds=report.age_seconds,
            fresh_seconds=report.fresh_seconds,
            cached=report.cached,
            cache_available=report.cache_available,
        )
    )
    return Metrics(
        metadata=ObjectMetadata(name=_subject_name(kind, value)),
        spec=MetricsSpec(**{kind: value}),
        status=MetricsStatus(
            observed_at=snapshot.created,
            reserving_workloads=snapshot.observation.reserving_workloads,
            resources=_resources(snapshot),
            conditions=_conditions(report),
        ),
    )


def _responses(subject: str, not_found: str) -> dict[int | str, dict[str, object]]:
    """Describe the sanitized failure responses shared by every subject route."""
    return {
        400: {"model": Status, "description": f"Malformed {subject} value."},
        404: {"model": Status, "description": not_found},
        405: {"model": Status, "description": "The HTTP method is not allowed."},
        500: {"model": Status, "description": "The metrics report could not be produced."},
        503: {"model": Status, "description": "No serviceable report is available."},
    }


@router.get(
    "/apis/canfar.net/v1alpha1/metrics/session/{session_id:path}",
    response_model=Metrics,
    response_model_exclude_none=True,
    responses=_responses("session id", "No Job carries this canfar.net/id."),
    summary="Get session metrics",
)
async def get_session_metrics(
    session_id: SubjectPath,
    response: Response,
    runtime: RuntimeDependency,
) -> Metrics:
    """Return one session's Job reservations, live usage, and optional efficiency."""
    return await _serve("session", session_id, response, runtime)


@router.get(
    "/apis/canfar.net/v1alpha1/metrics/platform/{platform:path}",
    response_model=Metrics,
    response_model_exclude_none=True,
    responses=_responses("platform", "The path platform is not METRICS_PLATFORM_NAME."),
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
    responses=_responses("user", "No LocalQueue carries this canfar.net/username."),
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
    responses=_responses("community", "No configured ClusterQueue carries this community."),
    summary="Get current community queue metrics",
)
async def get_community_metrics(
    community: SubjectPath,
    response: Response,
    runtime: RuntimeDependency,
) -> Metrics:
    """Return reservation and reserving counts for one community."""
    return await _serve("community", community, response, runtime)
