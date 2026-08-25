"""Bounded process-local snapshot storage and test coordinator."""

from __future__ import annotations

from collections import OrderedDict
from collections.abc import Awaitable, Callable
from datetime import UTC, datetime
from typing import Generic, TypeVar

from metrics.cache.models import CacheIdentity, CacheResult, Freshness, FreshnessPolicy

Value = TypeVar("Value")


class MemorySnapshots(Generic[Value]):
    """Small LRU of last-known snapshots, bounded by count and collection age."""

    def __init__(self, max_entries: int = 128) -> None:
        """Create a cache that retains at most ``max_entries`` snapshots."""
        self._max_entries = max(1, max_entries)
        self._entries: OrderedDict[str, Value] = OrderedDict()

    def put(self, key: str, value: Value) -> None:
        """Store and mark a snapshot as most recently used."""
        self._entries[key] = value
        self._entries.move_to_end(key)
        while len(self._entries) > self._max_entries:
            self._entries.popitem(last=False)

    def get(self, key: str) -> Value | None:
        """Return a snapshot and mark it as most recently used."""
        value = self._entries.get(key)
        if value is not None:
            self._entries.move_to_end(key)
        return value


class InMemoryCoordinator(Generic[Value]):
    """Test-only get-or-fill implementation with freshness semantics."""

    backend_name = "memory"

    def __init__(
        self,
        *,
        policy: FreshnessPolicy,
        created: Callable[[Value], datetime],
        max_entries: int = 128,
    ) -> None:
        """Configure freshness and snapshot timestamp extraction."""
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
    ) -> CacheResult:
        """Return a serviceable value or fill synchronously."""
        key = identity.canonical().decode()
        cached = self._values.get(key)
        if cached is not None:
            state = self.policy.classify(self._created(cached), now=datetime.now(UTC))
            if state in {Freshness.FRESH, Freshness.STALE}:
                return CacheResult(cached, cached=True, stale=state is Freshness.STALE)
        value = await fill()
        self._values.put(key, value)
        return CacheResult(value, cached=False, stale=False)
