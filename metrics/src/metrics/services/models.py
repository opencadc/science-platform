"""Define transport-neutral Metrics observations and cache payloads."""

from __future__ import annotations

from collections.abc import Iterable
from dataclasses import dataclass
from datetime import UTC, datetime
from decimal import Decimal, InvalidOperation
import re
from typing import Literal


MetricsSurface = Literal["platform", "user", "community", "session"]

DEFAULT_PLATFORM_NAME = "canfar"
MAX_DECIMAL_INPUT_LENGTH = 4_096
MAX_DECIMAL_DIGITS = 2_048
MAX_DECIMAL_EXPONENT = 4_096
MAX_DECIMAL_ADJUSTED = 1_000
MAX_DECIMAL_PLAIN_LENGTH = 4_096
_DECIMAL_TEXT = re.compile(r"^[+]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][+-]?[0-9]+)?$")
_EFFICIENCY_RESOURCES = frozenset({"cpu", "memory"})


def _normalise_observed_at(value: datetime) -> datetime:
    """Require an aware timestamp and normalize it to UTC."""
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("observation timestamps must be timezone-aware")
    return value.astimezone(UTC)


def bounded_decimal(value: object) -> Decimal:
    """Parse one finite, non-negative decimal within the wire-size policy.

    Args:
        value: Decimal-like value from a provider or efficiency adapter.

    Returns:
        A validated Decimal with zero normalized to ``Decimal(0)``.

    Raises:
        ValueError: If the value is not finite, non-negative, or bounded.
    """
    result = _coerce_decimal(value)
    if not result.is_finite() or result < 0:
        raise ValueError("decimal values must be finite and non-negative")
    digits = result.as_tuple().digits
    exponent = result.as_tuple().exponent
    if not isinstance(exponent, int):
        raise ValueError("decimal value exceeds the bounded metric policy")
    if (
        len(digits) > MAX_DECIMAL_DIGITS
        or abs(exponent) > MAX_DECIMAL_EXPONENT
        or not -MAX_DECIMAL_ADJUSTED <= result.adjusted() <= MAX_DECIMAL_ADJUSTED
        or _decimal_plain_length(result, digits, exponent) > MAX_DECIMAL_PLAIN_LENGTH
    ):
        raise ValueError("decimal value exceeds the bounded metric policy")
    return Decimal(0) if result.is_zero() else result


def _coerce_decimal(value: object) -> Decimal:
    """Convert supported internal numeric values without binary expansion."""
    if isinstance(value, bool):
        raise ValueError("decimal values must be finite and non-negative")
    if isinstance(value, Decimal):
        result = value
    elif isinstance(value, str):
        if len(value) > MAX_DECIMAL_INPUT_LENGTH or _DECIMAL_TEXT.fullmatch(value) is None:
            raise ValueError("decimal value is invalid")
        try:
            result = Decimal(value)
        except (InvalidOperation, ValueError) as exc:
            raise ValueError("decimal value is invalid") from exc
    elif isinstance(value, (int, float)):
        try:
            result = Decimal(str(value))
        except (InvalidOperation, ValueError, OverflowError) as exc:
            raise ValueError("decimal value is invalid") from exc
    else:
        raise ValueError("decimal value is invalid")
    return result


def _decimal_plain_length(result: Decimal, digits: tuple[int, ...], exponent: int) -> int:
    """Calculate the bounded fixed-point output length for one Decimal."""
    decimal_position = len(digits) + exponent
    if exponent >= 0:
        plain_length = len(digits) + exponent
    elif decimal_position > 0:
        plain_length = len(digits) + 1
    else:
        plain_length = 2 - decimal_position + len(digits)
    return plain_length + int(bool(result.as_tuple().sign))


@dataclass(frozen=True, slots=True)
class MetricsSubject:
    """Select one platform, user, or community report."""

    kind: MetricsSurface
    value: str = ""


@dataclass(frozen=True, slots=True)
class EfficiencyObservation:
    """Represent an optional current efficiency result.

    The adapter seam intentionally supports only CPU and memory. It contains
    no query language or provider details, so any efficiency adapter can feed
    it without changing Metrics service or response assembly.
    """

    observed_at: datetime
    efficiencies: dict[str, Decimal]

    def __post_init__(self) -> None:
        """Validate timestamps, resource names, and bounded ratios."""
        object.__setattr__(self, "observed_at", _normalise_observed_at(self.observed_at))
        normalized: dict[str, Decimal] = {}
        for resource, value in self.efficiencies.items():
            if resource not in _EFFICIENCY_RESOURCES:
                raise ValueError("efficiency observations support only cpu and memory")
            normalized[resource] = bounded_decimal(value)
        if not normalized:
            raise ValueError("efficiency observations must contain at least one resource")
        object.__setattr__(self, "efficiencies", normalized)


@dataclass(frozen=True, slots=True)
class PlatformObservation:
    """Represent aggregate ClusterQueue capacity and allocation."""

    cluster: str
    capacity: dict[str, str]
    allocated: dict[str, str]
    reserving_workloads: int
    observed_at: datetime

    def __post_init__(self) -> None:
        """Validate the queue count and observation timestamp."""
        if self.reserving_workloads < 0:
            raise ValueError("reserving_workloads must be non-negative")
        object.__setattr__(self, "observed_at", _normalise_observed_at(self.observed_at))


@dataclass(frozen=True, slots=True)
class UserObservation:
    """Represent one user's LocalQueue reservations and active queue count."""

    user: str
    requests: dict[str, str]
    reserving_workloads: int
    observed_at: datetime

    def __post_init__(self) -> None:
        """Validate the queue count and observation timestamp."""
        if self.reserving_workloads < 0:
            raise ValueError("reserving_workloads must be non-negative")
        object.__setattr__(self, "observed_at", _normalise_observed_at(self.observed_at))


@dataclass(frozen=True, slots=True)
class SessionObservation:
    """Represent one session's Job reservations and timing inputs."""

    session: str
    requests: dict[str, str]
    reserving_workloads: int
    observed_at: datetime
    start_time: datetime | None
    window_end: datetime
    has_running_pods: bool

    def __post_init__(self) -> None:
        """Validate the queue count, timestamps, and observation time."""
        if self.reserving_workloads < 0:
            raise ValueError("reserving_workloads must be non-negative")
        object.__setattr__(self, "observed_at", _normalise_observed_at(self.observed_at))
        object.__setattr__(self, "window_end", _normalise_observed_at(self.window_end))
        if self.start_time is not None:
            object.__setattr__(self, "start_time", _normalise_observed_at(self.start_time))


@dataclass(frozen=True, slots=True)
class CommunityObservation:
    """Represent one community's configured ClusterQueue reservations."""

    community: str
    requests: dict[str, str]
    reserving_workloads: int
    observed_at: datetime

    def __post_init__(self) -> None:
        """Validate the queue count and observation timestamp."""
        if self.reserving_workloads < 0:
            raise ValueError("reserving_workloads must be non-negative")
        object.__setattr__(self, "observed_at", _normalise_observed_at(self.observed_at))


@dataclass(frozen=True, slots=True)
class CachedSnapshot:
    """Store one observation and optional usage or efficiency in one cache fill."""

    observation: PlatformObservation | UserObservation | CommunityObservation | SessionObservation
    created: datetime
    efficiency: EfficiencyObservation | None = None
    usage: dict[str, str] | None = None
    ready: bool = True
    ready_reason: Literal["Available", "PartialData"] = "Available"

    def __post_init__(self) -> None:
        """Validate cache timestamp and optional readiness state."""
        object.__setattr__(self, "created", _normalise_observed_at(self.created))
        if not self.ready and self.ready_reason != "PartialData":
            raise ValueError("unready cache snapshots must use PartialData")
        if self.ready and self.ready_reason != "Available":
            raise ValueError("ready cache snapshots must use Available")


@dataclass(slots=True)
class SurfaceReadiness:
    """Track source, snapshot, and cache availability for one surface."""

    source_reachable: bool = False
    snapshot_complete: bool = False
    snapshot_serviceable: bool = False
    cache_available: bool = True

    @property
    def ready(self) -> bool:
        """Return whether this surface has a safe serving path."""
        return self.cache_available and (
            self.source_reachable or (self.snapshot_complete and self.snapshot_serviceable)
        )


@dataclass(slots=True)
class ReadinessState:
    """Coordinate process readiness without probing dependencies on demand."""

    _surfaces: dict[MetricsSurface, SurfaceReadiness]
    _started: bool = False

    def __init__(self, surfaces: Iterable[MetricsSurface] = ("platform",)) -> None:
        """Create readiness state for the configured report surfaces."""
        self._surfaces = {surface: SurfaceReadiness() for surface in surfaces}

    @property
    def surfaces(self) -> tuple[MetricsSurface, ...]:
        """Return the tracked report surfaces."""
        return tuple(self._surfaces)

    @property
    def ready(self) -> bool:
        """Return Platform serviceability with every shared cache available."""
        platform = self._surfaces.get("platform")
        return self._started and platform is not None and platform.ready and self.cache_available

    @property
    def cache_available(self) -> bool:
        """Return whether every tracked shared cache is available."""
        return bool(self._surfaces) and all(
            surface.cache_available for surface in self._surfaces.values()
        )

    def start(self) -> None:
        """Mark the runtime as serving."""
        self._started = True

    def stop(self) -> None:
        """Mark the runtime stopped and clear dependency observations."""
        self._started = False
        for surface in self._surfaces.values():
            surface.source_reachable = False
            surface.snapshot_complete = False
            surface.snapshot_serviceable = False
            surface.cache_available = False

    def mark_source(self, surface: MetricsSurface, *, reachable: bool) -> None:
        """Record source reachability for one report surface."""
        self._surfaces[surface].source_reachable = reachable

    def mark_snapshot(
        self,
        surface: MetricsSurface,
        *,
        complete: bool,
        serviceable: bool,
    ) -> None:
        """Record whether a complete serviceable snapshot is available."""
        state = self._surfaces[surface]
        state.snapshot_complete = complete
        state.snapshot_serviceable = serviceable

    def mark_cache(self, surface: MetricsSurface, *, available: bool) -> None:
        """Record cache availability for one report surface."""
        self._surfaces[surface].cache_available = available


@dataclass(slots=True)
class MetricsResult:
    """Return an observation with cache and readiness provenance."""

    observation: PlatformObservation | UserObservation | CommunityObservation | SessionObservation
    created: datetime
    cached: bool
    stale: bool = False
    cache_available: bool = True
    efficiency: EfficiencyObservation | None = None
    usage: dict[str, str] | None = None
    ready: bool = True
    ready_reason: Literal["Available", "PartialData"] = "Available"

    @property
    def ready_condition(
        self,
    ) -> tuple[Literal["True", "False"], Literal["Available", "PartialData", "StaleData"]]:
        """Return the public Ready condition for this result."""
        if self.stale:
            return "False", "StaleData"
        return ("True", "Available") if self.ready else ("False", self.ready_reason)

    @property
    def cached_condition(
        self,
    ) -> tuple[
        Literal["True", "False", "Unknown"],
        Literal["FreshHit", "StaleHit", "Refreshed", "RedisUnavailable"],
    ]:
        """Return the public Cached condition for this result."""
        if not self.cache_available:
            return "Unknown", "RedisUnavailable"
        if self.stale:
            return "True", "StaleHit"
        return ("True", "FreshHit") if self.cached else ("False", "Refreshed")
