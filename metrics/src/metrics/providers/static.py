"""Static providers used for local and CI integration environments."""

from __future__ import annotations

from datetime import UTC, datetime
from hashlib import sha256

from metrics.config import Settings
from metrics.models import CapacityReading, UsageReading


class StaticCapacityProvider:
    """Return deterministic static capacity values from configuration."""

    source_name = "static-capacity"

    def __init__(self, settings: Settings) -> None:
        self._settings = settings

    async def get_capacity(self) -> CapacityReading:
        return CapacityReading(
            cpu_cores=self._settings.static_capacity_cpu_cores,
            memory_gib=self._settings.static_capacity_memory_gib,
            source=self.source_name,
            observed_at=datetime.now(UTC),
        )


class StaticUsageProvider:
    """Return deterministic static usage for platform, user, and session scopes."""

    source_name = "static-usage"

    def __init__(self, settings: Settings) -> None:
        self._settings = settings

    async def get_usage(self) -> UsageReading:
        return self._usage_reading(
            requested_cpu_cores=self._settings.static_usage_cpu_cores,
            requested_memory_gib=self._settings.static_usage_memory_gib,
            source=self.source_name,
        )

    async def get_usage_for_user(self, user_id: str) -> UsageReading:
        factor = self._settings.static_user_usage_fraction * _stable_fraction(user_id)
        return self._usage_reading(
            requested_cpu_cores=self._settings.static_usage_cpu_cores * factor,
            requested_memory_gib=self._settings.static_usage_memory_gib * factor,
            source=f"{self.source_name}:user",
        )

    async def get_usage_for_session(self, user_id: str, session_id: str) -> UsageReading:
        identity = f"{user_id}:{session_id}"
        factor = self._settings.static_session_usage_fraction * _stable_fraction(identity)
        return self._usage_reading(
            requested_cpu_cores=self._settings.static_usage_cpu_cores * factor,
            requested_memory_gib=self._settings.static_usage_memory_gib * factor,
            source=f"{self.source_name}:session",
        )

    def _usage_reading(
        self,
        *,
        requested_cpu_cores: float,
        requested_memory_gib: float,
        source: str,
    ) -> UsageReading:
        return UsageReading(
            requested_cpu_cores=max(requested_cpu_cores, 0.0),
            requested_memory_gib=max(requested_memory_gib, 0.0),
            source=source,
            observed_at=datetime.now(UTC),
        )


def _stable_fraction(value: str) -> float:
    """Return stable deterministic fraction in [0.5, 1.0] for identity fanout."""
    digest = sha256(value.encode("utf-8")).hexdigest()[:8]
    normalized = int(digest, 16) / 0xFFFFFFFF
    return 0.5 + (normalized * 0.5)
