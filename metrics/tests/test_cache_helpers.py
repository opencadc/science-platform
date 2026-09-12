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

    def __init__(self, *, policy: FreshnessPolicy, created: Callable[[Value], datetime]) -> None:
        """Create a deterministic successful-result double."""
        self.policy = policy
        self.available = True
        self._created = created
        self._values: dict[bytes, Value] = {}

    def _serviceable_until(self, value: Value) -> datetime:
        """Return the positive observation's serviceability deadline."""
        created = self._created(value)
        if created.tzinfo is None:
            created = created.replace(tzinfo=UTC)
        else:
            created = created.astimezone(UTC)
        return created + timedelta(seconds=self.policy.stale_seconds)

    async def get_or_fill(
        self,
        identity: CacheIdentity,
        fill: Callable[[], Awaitable[Value]],
    ) -> CacheResult[Value]:
        """Return one memoized successful fill for the test identity."""
        key = identity.canonical()
        value = self._values.get(key)
        if value is not None:
            return CacheResult(
                value,
                cached=True,
                stale=False,
                cache_available=True,
                source_reachable=None,
                serviceable_until=self._serviceable_until(value),
            )
        value = await fill()
        self._values[key] = value
        return CacheResult(
            value,
            cached=False,
            stale=False,
            cache_available=True,
            source_reachable=True,
            serviceable_until=self._serviceable_until(value),
        )

    async def shutdown(self) -> None:
        """Satisfy the runtime lifecycle seam."""
