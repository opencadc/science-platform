"""Small test-only cache doubles for application and lifecycle tests."""

from __future__ import annotations

from collections.abc import Awaitable, Callable
from datetime import UTC, datetime, timedelta
from typing import Generic, TypeVar

from metrics.cache import CacheIdentity, CacheResult, FreshnessPolicy

Value = TypeVar("Value")


class FakeCacheCoordinator(Generic[Value]):
    """Memoize successful fills without reproducing Redis semantics."""

    backend_name = "redis"

    def __init__(self, *, policy: FreshnessPolicy) -> None:
        """Create a deterministic successful-result double."""
        self.policy = policy
        self.available = True
        self._values: dict[bytes, Value] = {}

    def _result(self, value: Value, *, cached: bool) -> CacheResult[Value]:
        """Return one fresh result whose stale window starts now."""
        return CacheResult(
            value,
            cached=cached,
            stale=False,
            cache_available=True,
            source_reachable=None if cached else True,
            serviceable_until=datetime.now(UTC) + timedelta(seconds=self.policy.stale_seconds),
        )

    async def ping(self) -> None:
        """Satisfy the readiness seam."""

    async def get_or_fill(
        self,
        identity: CacheIdentity,
        fill: Callable[[], Awaitable[Value]],
    ) -> CacheResult[Value]:
        """Return one memoized successful fill for the test identity."""
        key = identity.canonical()
        value = self._values.get(key)
        if value is not None:
            return self._result(value, cached=True)
        value = await fill()
        self._values[key] = value
        return self._result(value, cached=False)

    async def shutdown(self) -> None:
        """Satisfy the runtime lifecycle seam."""
