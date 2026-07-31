"""Shared test doubles for route and service tests (not shipped)."""

from __future__ import annotations

import re
from dataclasses import dataclass
from types import SimpleNamespace

import kr8s

from metrics.schemas.metrics import PlatformMetricsData


class FakeKueueApi:
    """kr8s-shaped fake: ``get(cls, name)`` yields objects with ``.raw`` dicts.

    ``docs`` maps ClusterQueue names to raw dicts, to exceptions (raised on
    access), or to zero-argument async callables (awaited, then their return
    value is treated the same way) for concurrency coordination in tests.
    Missing names raise :class:`kr8s.NotFoundError` like the real client.
    """

    def __init__(self, docs: dict[str, object] | None = None) -> None:
        self.docs = docs or {}
        self.requested: list[str] = []

    def get(self, _cls: type, name: str):
        async def generate():
            self.requested.append(name)
            value = self.docs.get(name, kr8s.NotFoundError(name))
            if callable(value):
                value = await value()
            if isinstance(value, BaseException):
                raise value
            yield SimpleNamespace(raw=value)

        return generate()


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
    gpu_cap: str = "4"
    cpu_alloc: str = "25"
    mem_alloc: str = "50Gi"
    gpu_alloc: str = "1"

    async def load(self) -> PlatformMetricsData:
        return PlatformMetricsData(
            cluster=self.cluster,
            capacity={
                "cpu": self.cpu_cap,
                "memory": self.mem_cap,
                "nvidia.com/gpu": self.gpu_cap,
            },
            allocated={
                "cpu": self.cpu_alloc,
                "memory": self.mem_alloc,
                "nvidia.com/gpu": self.gpu_alloc,
            },
        )


class LifecycleProvider:
    """Provider double that records lifecycle events and can fail either hook."""

    def __init__(
        self,
        events: list[str] | None = None,
        *,
        startup_error: BaseException | None = None,
        shutdown_error: BaseException | None = None,
    ) -> None:
        self.events = events if events is not None else []
        self._startup_error = startup_error
        self._shutdown_error = shutdown_error

    @property
    def name(self) -> str:
        return "stub"

    async def startup(self) -> None:
        self.events.append("startup")
        if self._startup_error is not None:
            raise self._startup_error

    async def shutdown(self) -> None:
        self.events.append("provider shutdown")
        if self._shutdown_error is not None:
            raise self._shutdown_error

    def cache_fingerprint(self) -> str:
        return "stub"

    async def platform(self) -> PlatformMetricsData:
        return PlatformMetricsData(cluster="c", capacity={}, allocated={})
