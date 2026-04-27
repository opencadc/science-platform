"""Provider scope contracts and async provider protocol."""

from __future__ import annotations

from enum import StrEnum
from typing import Protocol, runtime_checkable

from metrics.schemas.metrics import PlatformMetricsData


class UnsupportedMetricScope(RuntimeError):
    """Raised when a provider does not implement a requested metric scope."""

    def __init__(self, scope: "MetricScope") -> None:
        """Store the requested scope and build a user-facing error message.

        Args:
            scope: The :class:`MetricScope` value that the provider rejected.
        """
        self.scope = scope
        super().__init__(f"Provider does not support metric scope: {scope.value}")


class MetricScope(StrEnum):
    """Supported metric API scopes (``platform`` only for now)."""

    PLATFORM = "platform"


class ProviderMetrics:
    """Base for provider metric implementations; defaults reject every scope."""

    supported_scopes: frozenset[MetricScope] = frozenset()

    async def platform(self) -> PlatformMetricsData:
        """Load cluster-level platform capacity and allocation (default: unsupported)."""
        raise UnsupportedMetricScope(MetricScope.PLATFORM)


@runtime_checkable
class Provider(Protocol):
    """Async lifecycle and typed metrics for one upstream source."""

    async def startup(self) -> None:
        """Validate connectivity and configuration (no-op when inapplicable)."""

    async def shutdown(self) -> None:
        """Release resources held for this provider."""

    @property
    def name(self) -> str:
        """Configuration key for this provider (e.g. kueue, prometheus)."""

    def metrics(self) -> ProviderMetrics:
        """Return scope implementations for this provider."""

    def cache_fingerprint(self) -> str:
        """Stable string for app-level cache keys when this source is active."""
