"""Transport-neutral Metrics subjects and observations (no FastAPI types)."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from decimal import Decimal
from enum import StrEnum
from typing import Literal


@dataclass(frozen=True, slots=True)
class MetricsSubject:
    """Subject selector for :meth:`metrics.services.metrics.MetricsService.get`.

    Platform, user, and community are served.
    """

    kind: Literal["platform", "user", "community"]
    value: str = ""


PLATFORM_SUBJECT = MetricsSubject(kind="platform", value="canfar")


@dataclass(frozen=True, slots=True)
class PlatformObservation:
    """Scheduler-facing platform capacity and admitted allocation."""

    cluster: str
    capacity: dict[str, str]
    allocated: dict[str, str]


@dataclass(frozen=True, slots=True)
class UserObservation:
    """Scheduler-effective requests held by one user's Running Pods."""

    user: str
    running_pods: int
    requests: dict[str, str]


@dataclass(frozen=True, slots=True)
class CommunityObservation:
    """Scheduler-effective requests held by one community's Running Pods."""

    community: str
    running_pods: int
    requests: dict[str, str]


class LifetimeIssue(StrEnum):
    """Machine-readable reasons that lifetime accounting is incomplete."""

    CORRUPT_STATE = "corrupt-state"
    COUNTER_RESET = "counter-reset"
    MISSING_SERIES = "missing-series"
    POD_DISAPPEARED = "pod-disappeared"
    PROCESS_RESTART = "process-restart"
    SAMPLING_GAP = "sampling-gap"
    SCRAPE_GAP = "scrape-gap"


@dataclass(frozen=True, slots=True)
class ResourceInterval:
    """Constant resource rates over one covered Running interval."""

    started_at: datetime
    ended_at: datetime
    usage_rate: Decimal
    requested_rate: Decimal


@dataclass(frozen=True, slots=True)
class PodResourceLifetime:
    """Internal lifetime series for one currently Running Pod UID and resource."""

    pod_uid: str
    resource: str
    running_since: datetime
    observed_at: datetime
    intervals: tuple[ResourceInterval, ...] = ()
    issues: frozenset[LifetimeIssue] = frozenset()


@dataclass(frozen=True, slots=True)
class ResourceHours:
    """Additive observed and requested resource-time in the named unit."""

    unit: Literal["core-hours", "GiB-hours", "GPU-hours"]
    usage: Decimal
    requested: Decimal


@dataclass(frozen=True, slots=True)
class ActiveWorkloadLifetime:
    """Complete totals and per-resource incompleteness for active Pods."""

    resources: dict[str, ResourceHours]
    incomplete: dict[str, frozenset[LifetimeIssue]]

    @property
    def ready(self) -> bool:
        """Whether every resource has complete lifetime coverage."""
        return not self.incomplete


@dataclass(slots=True)
class CachedSnapshot:
    """Versioned cache payload for a supported observation."""

    observation: PlatformObservation | UserObservation | CommunityObservation
    created: datetime


@dataclass(slots=True)
class MetricsResult:
    """Outcome of a Metrics get, including cache provenance for HTTP headers."""

    observation: PlatformObservation | UserObservation | CommunityObservation
    created: datetime
    cached: bool
    stale: bool = False
    cache_available: bool = True

    @property
    def ready_condition(
        self,
    ) -> tuple[Literal["True", "False"], Literal["Available", "StaleData"]]:
        """Return the public Ready status and reason."""
        return ("False", "StaleData") if self.stale else ("True", "Available")

    @property
    def cached_condition(
        self,
    ) -> tuple[
        Literal["True", "False", "Unknown"],
        Literal["FreshHit", "StaleHit", "Refreshed", "RedisUnavailable"],
    ]:
        """Return the public Cached status and reason."""
        if not self.cache_available:
            return "Unknown", "RedisUnavailable"
        if self.stale:
            return "True", "StaleHit"
        return ("True", "FreshHit") if self.cached else ("False", "Refreshed")
