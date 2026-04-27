"""TTL cache primitives with in-memory and Redis backends."""

from __future__ import annotations

import json
import threading
import time
from dataclasses import dataclass
from typing import Callable, Generic, Protocol, TypeVar

from redis.asyncio import Redis
from redis.exceptions import RedisError

V = TypeVar("V")


class TTLCacheBackend(Protocol, Generic[V]):
    """Async cache backend contract used by service code paths."""

    @property
    def backend_name(self) -> str:
        """Backend identifier used for telemetry attributes."""

    @property
    def ttl_seconds(self) -> int:
        """Configured TTL in seconds."""

    async def get(self, key: str) -> V | None:
        """Return cached value or ``None`` for miss."""

    async def set(self, key: str, value: V) -> None:
        """Store value with configured TTL semantics."""


@dataclass(slots=True)
class _CacheEntry(Generic[V]):
    value: V
    expires_at: float


class InMemoryTTLCache(Generic[V]):
    """Thread-safe in-memory TTL cache with monotonic clock semantics."""

    def __init__(self, ttl_seconds: int, clock: Callable[[], float] | None = None) -> None:
        """Create an in-memory store with a fixed positive TTL in seconds.

        Args:
            ttl_seconds: Time-to-live for each entry; clamped to ``0`` or above.
            clock: Optional monotonic time source; defaults to :func:`time.monotonic`.
        """
        self._ttl_seconds = max(ttl_seconds, 0)
        self._clock = clock or time.monotonic
        self._lock = threading.Lock()
        self._entries: dict[str, _CacheEntry[V]] = {}

    @property
    def ttl_seconds(self) -> int:
        """Configured non-negative TTL for new entries."""
        return self._ttl_seconds

    @property
    def backend_name(self) -> str:
        """Telemetry label for the in-process backend."""
        return "memory"

    async def get(self, key: str) -> V | None:
        """Return a live value or remove and return ``None`` when expired."""
        now = self._clock()
        with self._lock:
            entry = self._entries.get(key)
            if entry is None:
                return None
            if entry.expires_at < now:
                del self._entries[key]
                return None
            return entry.value

    async def set(self, key: str, value: V) -> None:
        """Store a value with an expiry of ``now + ttl_seconds``."""
        expires_at = self._clock() + self._ttl_seconds
        with self._lock:
            self._entries[key] = _CacheEntry(value=value, expires_at=expires_at)


class RedisJSONTTLCache(Generic[V]):
    """Redis-backed TTL cache that stores JSON payloads."""

    def __init__(
        self,
        *,
        ttl_seconds: int,
        redis: Redis,
        key_prefix: str,
        serializer: Callable[[V], str],
        deserializer: Callable[[str], V],
    ) -> None:
        """Configure Redis key prefix and JSON serialization for cached values.

        Args:
            ttl_seconds: ``EX`` expiry in seconds (non-positive values skip ``SET``).
            redis: Async Redis client; not owned (caller may share a pool).
            key_prefix: Prepended to each logical cache key.
            serializer: Model-to-JSON-string for storage.
            deserializer: JSON-string to model, used on cache hits.
        """
        self._ttl_seconds = max(ttl_seconds, 0)
        self._redis = redis
        self._key_prefix = key_prefix
        self._serializer = serializer
        self._deserializer = deserializer

    @property
    def ttl_seconds(self) -> int:
        """Configured TTL passed to ``SET ... EX``."""
        return self._ttl_seconds

    @property
    def backend_name(self) -> str:
        """Telemetry label for Redis-backed storage."""
        return "redis"

    def _full_key(self, key: str) -> str:
        return f"{self._key_prefix}{key}"

    async def get(self, key: str) -> V | None:
        """Fetch and deserialize a value, returning ``None`` on miss or error."""
        try:
            payload = await self._redis.get(self._full_key(key))
        except RedisError:
            return None

        if payload is None:
            return None

        if isinstance(payload, bytes):
            payload = payload.decode("utf-8")

        try:
            return self._deserializer(payload)
        except (TypeError, ValueError, json.JSONDecodeError):
            return None

    async def set(self, key: str, value: V) -> None:
        """Serialize and store a value; no-op when TTL is non-positive."""
        if self._ttl_seconds <= 0:
            return

        try:
            await self._redis.set(
                self._full_key(key),
                self._serializer(value),
                ex=self._ttl_seconds,
            )
        except RedisError:
            return
