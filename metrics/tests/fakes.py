"""Shared test doubles for route and service tests (not shipped)."""

from __future__ import annotations

import re
from dataclasses import dataclass

from metrics.schemas.metrics import PlatformMetricsData


def cache_control_max_age(cache_control: str) -> int:
    m = re.search(r"max-age=(\d+)", cache_control.lower())
    assert m is not None
    return int(m.group(1))


@dataclass
class StubPlatformMetrics:
    """Fixed platform payload for HTTP tests."""

    cluster: str = "prod"
    cpu_cap: str = "100"
    mem_cap: str = "200Gi"
    cpu_alloc: str = "25"
    mem_alloc: str = "50Gi"

    async def load(self) -> PlatformMetricsData:
        return PlatformMetricsData(
            cluster=self.cluster,
            capacity={"cpu": self.cpu_cap, "memory": self.mem_cap},
            allocated={"cpu": self.cpu_alloc, "memory": self.mem_alloc},
        )
