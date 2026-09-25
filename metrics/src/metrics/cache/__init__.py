"""Public cache seam used by the Metrics runtime and service."""

from __future__ import annotations

from collections.abc import Awaitable, Callable
from typing import Protocol, TypeVar

from metrics.cache.coordination import RedisCoordinator, describe_failure
from metrics.cache.memory import MemorySnapshots
from metrics.cache.models import (
    FRESHNESS_POLICIES,
    CacheFailureCategory,
    CacheFillTimeout,
    CacheIdentity,
    CacheInternalError,
    CacheKeys,
    CacheNotFound,
    CacheResult,
    CacheUnavailable,
    Freshness,
    FreshnessPolicy,
    cache_keys,
)
from metrics.cache.redis import (
    Observation,
    RedisSnapshots,
    RedisUnavailable,
    StoredNotFound,
    StoredSnapshot,
)

Value = TypeVar("Value")


class CacheCoordinator(Protocol[Value]):
    """Cache coordinator consumed by Metrics services."""

    backend_name: str
    policy: FreshnessPolicy

    @property
    def available(self) -> bool:
        """Return whether the latest Redis command succeeded."""

    async def ping(self) -> None:
        """Run one bounded Redis health check."""

    async def get_or_fill(
        self,
        identity: CacheIdentity,
        fill: Callable[[], Awaitable[Value]],
    ) -> CacheResult[Value]:
        """Return a serviceable value or coordinate one source fill."""

    async def shutdown(self) -> None:
        """Stop new work and await cleanup."""


__all__ = [
    "FRESHNESS_POLICIES",
    "CacheCoordinator",
    "CacheFailureCategory",
    "CacheFillTimeout",
    "CacheIdentity",
    "CacheInternalError",
    "CacheKeys",
    "CacheNotFound",
    "CacheResult",
    "CacheUnavailable",
    "Freshness",
    "FreshnessPolicy",
    "MemorySnapshots",
    "Observation",
    "RedisCoordinator",
    "RedisSnapshots",
    "RedisUnavailable",
    "StoredNotFound",
    "StoredSnapshot",
    "cache_keys",
    "describe_failure",
]
