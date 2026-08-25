"""Distributed cache load boundary."""

from __future__ import annotations

from collections.abc import Awaitable, Callable
from typing import Protocol, TypeVar

from metrics.cache.coordination import RedisCoordinator
from metrics.cache.memory import InMemoryCoordinator, MemorySnapshots
from metrics.cache.models import (
    FRESHNESS_POLICIES,
    CacheIdentity,
    CacheResult,
    CacheUnavailable,
    Freshness,
    FreshnessPolicy,
    SnapshotEnvelope,
    cache_keys,
)
from metrics.cache.redis import RedisSnapshots, RedisUnavailable, StoredSnapshot

Value = TypeVar("Value")


class CacheCoordinator(Protocol[Value]):
    """Deep cache interface consumed by Metrics services."""

    backend_name: str
    policy: FreshnessPolicy

    @property
    def available(self) -> bool:
        """Whether the required cache dependency is currently available."""

    async def get_or_fill(
        self,
        identity: CacheIdentity,
        fill: Callable[[], Awaitable[Value]],
    ) -> CacheResult[Value]:
        """Return a serviceable snapshot or coordinate one fill."""


__all__ = [
    "FRESHNESS_POLICIES",
    "CacheCoordinator",
    "CacheIdentity",
    "CacheResult",
    "CacheUnavailable",
    "Freshness",
    "FreshnessPolicy",
    "InMemoryCoordinator",
    "MemorySnapshots",
    "RedisCoordinator",
    "RedisSnapshots",
    "RedisUnavailable",
    "SnapshotEnvelope",
    "StoredSnapshot",
    "cache_keys",
]
