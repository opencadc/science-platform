"""Provide bounded process-local snapshots and a deterministic coordinator.

The coordinator mirrors serviceable freshness behavior without Redis, making it
appropriate for explicitly configured local runs and focused tests.
"""

from __future__ import annotations

from collections import OrderedDict
from collections.abc import Awaitable, Callable
from datetime import UTC, datetime
from typing import Generic, TypeVar

from metrics.cache.models import CacheIdentity, CacheResult, Freshness, FreshnessPolicy

Value = TypeVar("Value")


class MemorySnapshots(Generic[Value]):
    """Keep a count-bounded least-recently-used set of arbitrary snapshots."""

    def __init__(self, max_entries: int = 128) -> None:
        """Create a cache with a positive effective entry limit.

        Args:
            max_entries: Maximum retained keys; values below one become one.
        """
        self._max_entries = max(1, max_entries)
        self._entries: OrderedDict[str, Value] = OrderedDict()

    def put(self, key: str, value: Value) -> None:
        """Store a value, mark it recently used, and evict the oldest key.

        Args:
            key: Stable cache key.
            value: Snapshot value to retain.
        """
        self._entries[key] = value
        self._entries.move_to_end(key)
        while len(self._entries) > self._max_entries:
            self._entries.popitem(last=False)

    def get(self, key: str) -> Value | None:
        """Read a value and move a hit to the most-recently-used position.

        Args:
            key: Stable cache key.

        Returns:
            Stored value, or ``None`` on a miss.
        """
        value = self._entries.get(key)
        if value is not None:
            self._entries.move_to_end(key)
        return value


class InMemoryCoordinator(Generic[Value]):
    """Coordinate local get-or-fill calls using production freshness semantics.

    Unlike the Redis coordinator, this implementation has no distributed lease
    or outage mode and fills synchronously after a miss or unusable snapshot.
    """

    backend_name = "memory"

    def __init__(
        self,
        *,
        policy: FreshnessPolicy,
        created: Callable[[Value], datetime],
        max_entries: int = 128,
    ) -> None:
        """Configure freshness and snapshot timestamp extraction.

        Args:
            policy: Age boundaries used to classify cached values.
            created: Callable extracting source collection time from a value.
            max_entries: Maximum process-local identities to retain.
        """
        self.policy = policy
        self._created = created
        self._values = MemorySnapshots[Value](max_entries)

    @property
    def available(self) -> bool:
        """The in-memory test implementation has no external dependency."""
        return True

    async def get_or_fill(
        self,
        identity: CacheIdentity,
        fill: Callable[[], Awaitable[Value]],
    ) -> CacheResult[Value]:
        """Return a fresh or stale local value, otherwise fill synchronously.

        Args:
            identity: Complete source stream identity.
            fill: Async callable producing a replacement value.

        Returns:
            Value and cache provenance.
        """
        key = identity.canonical().decode()
        cached = self._values.get(key)
        if cached is not None:
            state = self.policy.classify(self._created(cached), now=datetime.now(UTC))
            if state in {Freshness.FRESH, Freshness.STALE}:
                return CacheResult(cached, cached=True, stale=state is Freshness.STALE)
        value = await fill()
        self._values.put(key, value)
        return CacheResult(value, cached=False, stale=False)
