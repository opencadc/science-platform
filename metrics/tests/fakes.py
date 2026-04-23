"""Shared test doubles for HTTP and service layer tests (not shipped)."""

from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import UTC, datetime

from metrics.schemas.metrics import CapacityReading, UsageReading


def cache_control_max_age(cache_control: str) -> int:
    """Return Cache-Control max-age=… seconds."""
    m = re.search(r"max-age=(\d+)", cache_control.lower())
    assert m is not None
    return int(m.group(1))


@dataclass
class StubCapacityProvider:
    """Fixed Kueue capacity for route tests."""

    source_name: str = "kueue"
    cpu_cores: float = 100.0
    memory_gib: float = 200.0

    async def get_capacity(self) -> CapacityReading:
        return CapacityReading(
            cpu_cores=self.cpu_cores,
            memory_gib=self.memory_gib,
            source=self.source_name,
            observed_at=datetime.now(UTC),
        )


@dataclass
class StubUsageProvider:
    """Prometheus-style usage. When user_cpu/session_cpu are None, delegate to get_usage()."""

    source_name: str = "prometheus"
    platform_cpu: float = 25.0
    platform_mem: float = 50.0
    # Split responses for user/session routes; None → same as get_usage() (see test_create_app).
    user_cpu: float | None = 5.0
    user_mem: float | None = 10.0
    session_cpu: float | None = 2.0
    session_mem: float | None = 4.0

    def _usage(self, cpu: float, mem: float, source: str | None = None) -> UsageReading:
        return UsageReading(
            requested_cpu_cores=cpu,
            requested_memory_gib=mem,
            source=source or self.source_name,
            observed_at=datetime.now(UTC),
        )

    async def get_usage(self) -> UsageReading:
        return self._usage(self.platform_cpu, self.platform_mem)

    async def get_usage_for_user(self, user_id: str) -> UsageReading:
        del user_id
        if self.user_cpu is None:
            return await self.get_usage()
        assert self.user_mem is not None
        return self._usage(self.user_cpu, self.user_mem, f"{self.source_name}:user")

    async def get_usage_for_session(
        self, user_id: str, session_id: str
    ) -> UsageReading:
        del user_id, session_id
        if self.session_cpu is None:
            return await self.get_usage()
        assert self.session_mem is not None
        return self._usage(
            self.session_cpu, self.session_mem, f"{self.source_name}:session"
        )
