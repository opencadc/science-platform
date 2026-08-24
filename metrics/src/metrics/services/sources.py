"""Narrow asynchronous source contracts used by :class:`MetricsService`."""

from __future__ import annotations

from typing import Protocol

from metrics.services.models import PlatformObservation


class PlatformQuotaSource(Protocol):
    """Async platform quota/allocation source (Kueue in production)."""

    @property
    def name(self) -> str:
        """Stable provider key for telemetry."""

    def cache_fingerprint(self) -> str:
        """Non-secret fingerprint segment for cache key segregation."""

    async def read_platform(self) -> PlatformObservation:
        """Load a complete platform observation or raise a provider error."""
