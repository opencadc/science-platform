"""Define transport-neutral subjects, observations, and cache payloads.

Providers and services exchange these types without depending on FastAPI or
public wire schemas, keeping collection and cache logic reusable and testable.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime
from decimal import Decimal
from enum import StrEnum
from typing import Literal


@dataclass(frozen=True, slots=True)
class MetricsSubject:
    """Select the report requested from :class:`MetricsService`.

    Attributes:
        kind: Supported report scope.
        value: Exact platform, username, or community identifier.
    """

    kind: Literal["platform", "user", "community"]
    value: str = ""


DEFAULT_PLATFORM_NAME = "canfar"


def platform_subject(name: str = DEFAULT_PLATFORM_NAME) -> MetricsSubject:
    """Build the platform subject for the configured public platform name.

    Args:
        name: Platform path segment and ``spec.platform`` value. Defaults to
            :data:`DEFAULT_PLATFORM_NAME` (``canfar``).

    Returns:
        A platform :class:`MetricsSubject` for :meth:`MetricsService.get`.
    """
    return MetricsSubject(kind="platform", value=name)


PLATFORM_SUBJECT = platform_subject()


@dataclass(frozen=True, slots=True)
class PlatformObservation:
    """Represent Kueue capacity and admitted allocation for one cluster.

    Resource values use the public quantity format so the service does not need
    to reinterpret provider-specific units.
    """

    cluster: str
    capacity: dict[str, str]
    allocated: dict[str, str]


class AccountingState(StrEnum):
    """Describe whether lifetime accounting accompanies a workload report."""

    DISABLED = "disabled"
    COMPLETE = "complete"
    INCOMPLETE = "incomplete"
    UNAVAILABLE = "unavailable"


@dataclass(frozen=True, slots=True)
class UserObservation:
    """Capture scheduler-effective requests for one user's Running Pods.

    Immutable Pod UIDs tie optional lifetime accounting to the same observed
    workload population and prevent mismatched data from appearing complete.
    """

    user: str
    running_pods: int
    requests: dict[str, str]
    observed_at: datetime
    pod_uids: frozenset[str] = frozenset()
    accounting: ActiveWorkloadLifetime | None = None
    accounting_state: AccountingState = AccountingState.DISABLED
    accounting_stale: bool = False


@dataclass(frozen=True, slots=True)
class CommunityObservation:
    """Capture requests and optional accounting for a community's Running Pods.

    Immutable Pod UIDs identify the population observed at ``observed_at`` so
    separately collected accounting can be checked before it is merged.
    """

    community: str
    running_pods: int
    requests: dict[str, str]
    observed_at: datetime
    pod_uids: frozenset[str] = frozenset()
    accounting: ActiveWorkloadLifetime | None = None
    accounting_state: AccountingState = AccountingState.DISABLED
    accounting_stale: bool = False


class LifetimeIssue(StrEnum):
    """Enumerate bounded reasons a resource lifetime cannot be reported."""

    CORRUPT_STATE = "corrupt-state"
    COUNTER_RESET = "counter-reset"
    MISSING_SERIES = "missing-series"
    POD_DISAPPEARED = "pod-disappeared"
    PROCESS_RESTART = "process-restart"
    SAMPLING_GAP = "sampling-gap"
    SCRAPE_GAP = "scrape-gap"


@dataclass(frozen=True, slots=True)
class ResourceInterval:
    """Represent constant usage and request rates over a covered interval.

    Integrating these rates over the interval yields additive resource-hours.
    """

    started_at: datetime
    ended_at: datetime
    usage_rate: Decimal
    requested_rate: Decimal


@dataclass(frozen=True, slots=True)
class PodResourceLifetime:
    """Collect covered intervals for one Running Pod and resource.

    ``issues`` carries producer-known gaps; integration may add further issues
    when the intervals do not cover the complete Running lifetime.
    """

    pod_uid: str
    resource: str
    running_since: datetime
    observed_at: datetime
    intervals: tuple[ResourceInterval, ...] = ()
    issues: frozenset[LifetimeIssue] = frozenset()


@dataclass(frozen=True, slots=True)
class ResourceHours:
    """Hold additive usage and requested time in a resource-specific unit."""

    unit: Literal["core-hours", "GiB-hours", "GPU-hours"]
    usage: Decimal
    requested: Decimal


@dataclass(frozen=True, slots=True)
class ActiveWorkloadLifetime:
    """Aggregate complete resource-hours for the currently active workload.

    A resource with incomplete coverage is listed in ``incomplete`` and omitted
    from ``resources`` so callers cannot mistake a partial total for a complete
    lifetime.
    """

    resources: dict[str, ResourceHours]
    incomplete: dict[str, frozenset[LifetimeIssue]]
    pod_uids: frozenset[str] = frozenset()
    coverage: dict[str, frozenset[str]] = field(default_factory=dict)

    @property
    def ready(self) -> bool:
        """Return whether every represented resource has complete coverage."""
        return not self.incomplete


@dataclass(slots=True)
class AccountingSnapshot:
    """Store validated accounting and its source observation time atomically."""

    lifetime: ActiveWorkloadLifetime
    created: datetime


@dataclass(slots=True)
class CachedSnapshot:
    """Store one provider observation with the time used for freshness."""

    observation: PlatformObservation | UserObservation | CommunityObservation
    created: datetime


@dataclass(slots=True)
class MetricsResult:
    """Return an observation with cache provenance needed by HTTP adapters."""

    observation: PlatformObservation | UserObservation | CommunityObservation
    created: datetime
    cached: bool
    stale: bool = False
    cache_available: bool = True

    @property
    def ready_condition(
        self,
    ) -> tuple[
        Literal["True", "False"],
        Literal["Available", "PartialData", "AccountingIncomplete", "StaleData"],
    ]:
        """Derive the public Ready condition from freshness and accounting.

        Returns:
            Kubernetes-style condition status and a bounded reason.
        """
        if self.stale:
            return "False", "StaleData"
        if isinstance(self.observation, (UserObservation, CommunityObservation)):
            if self.observation.accounting_state is AccountingState.UNAVAILABLE:
                return "False", "PartialData"
            if self.observation.accounting_state is AccountingState.INCOMPLETE:
                return "False", "AccountingIncomplete"
        return "True", "Available"

    @property
    def cached_condition(
        self,
    ) -> tuple[
        Literal["True", "False", "Unknown"],
        Literal["FreshHit", "StaleHit", "Refreshed", "RedisUnavailable"],
    ]:
        """Derive the public Cached condition from coordinator provenance.

        Returns:
            Kubernetes-style condition status and a bounded cache reason.
        """
        if not self.cache_available:
            return "Unknown", "RedisUnavailable"
        if self.stale:
            return "True", "StaleHit"
        return ("True", "FreshHit") if self.cached else ("False", "Refreshed")
