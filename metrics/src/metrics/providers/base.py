"""Runtime provider and platform metrics interfaces."""

from __future__ import annotations

from typing import Protocol, runtime_checkable

from metrics.schemas.metrics import PlatformMetricsData


@runtime_checkable
class Provider(Protocol):
    """Lifecycle, identity, and cache contract for one upstream source."""

    async def startup(self) -> None:
        """Validate connectivity and configuration (no-op when inapplicable)."""

    async def shutdown(self) -> None:
        """Release resources held for this provider."""

    @property
    def name(self) -> str:
        """Configuration key for this provider (for example, ``kueue``)."""

    def cache_fingerprint(self) -> str:
        """Stable string for app-level cache keys when this source is active."""


@runtime_checkable
class PlatformMetrics(Protocol):
    """Capability for reading cluster-wide platform metrics."""

    async def platform(self) -> PlatformMetricsData:
        """Load cluster-level platform capacity and allocation."""
