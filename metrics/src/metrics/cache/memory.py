"""Provide bounded local snapshots for Redis outage fallback."""

from __future__ import annotations

from collections import OrderedDict
from typing import Generic, TypeVar

Value = TypeVar("Value")


class MemorySnapshots(Generic[Value]):
    """Keep a count-bounded positive-only least-recently-used set."""

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

    def evict(self, key: str) -> None:
        """Remove one positive entry without creating a negative cache entry."""
        self._entries.pop(key, None)

    def clear(self) -> None:
        """Remove every positive entry."""
        self._entries.clear()
