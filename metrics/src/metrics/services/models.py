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


PLATFORM_SUBJECT = MetricsSubject(kind="platform")


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
