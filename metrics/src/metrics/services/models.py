"""Transport-neutral Metrics subjects and observations (no FastAPI types)."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from typing import Literal


@dataclass(frozen=True, slots=True)
class MetricsSubject:
    """Subject selector for :meth:`metrics.services.metrics.MetricsService.get`.

    Only ``platform`` is served initially; user and community follow later.
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


@dataclass(slots=True)
class CachedSnapshot:
    """Versioned cache payload for a platform observation."""

    observation: PlatformObservation
    created: datetime


@dataclass(slots=True)
class MetricsResult:
    """Outcome of a Metrics get, including cache provenance for HTTP headers."""

    observation: PlatformObservation
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
