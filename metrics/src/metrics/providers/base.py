"""Provider protocols for metrics data collection."""

from __future__ import annotations

from typing import Protocol

from metrics.models import CapacityReading, UsageReading


class CapacityProvider(Protocol):
    """Protocol for a capacity data source."""

    source_name: str

    async def get_capacity(self) -> CapacityReading:
        """Return platform capacity readings."""


class UsageProvider(Protocol):
    """Protocol for a utilization data source."""

    source_name: str

    async def get_usage(self) -> UsageReading:
        """Return requested resource usage readings."""

    async def get_usage_for_user(self, user_id: str) -> UsageReading:
        """Return requested usage scoped to one user."""

    async def get_usage_for_session(
        self, user_id: str, session_id: str
    ) -> UsageReading:
        """Return requested usage scoped to one user session."""
