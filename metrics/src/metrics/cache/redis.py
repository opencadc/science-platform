"""Persist signed immutable snapshots and token-owned leases in Redis.

All commands have finite deadlines. Invalid, incompatible, or tampered payloads
are treated as misses so they never cross the cache trust boundary.
"""

from __future__ import annotations

import asyncio
import hashlib
import hmac
import json
from dataclasses import dataclass
from datetime import datetime
from time import perf_counter
from typing import Any, Generic, TypeVar

from pydantic import TypeAdapter, ValidationError
from redis.exceptions import RedisError

from metrics.cache.models import CacheKeys, SnapshotEnvelope
from metrics.telemetry import MetricsRecorder, NoopMetricsRecorder

Value = TypeVar("Value")

_PUBLISH = """
if redis.call('EXISTS', KEYS[1]) == 0 then
  redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[3])
end
redis.call('SET', KEYS[2], ARGV[2], 'EX', ARGV[3])
return 1
"""

_RELEASE = """
if redis.call('GET', KEYS[1]) == ARGV[1] then
  return redis.call('DEL', KEYS[1])
end
return 0
"""


class RedisUnavailable(RuntimeError):
    """Indicate that a bounded Redis command failed or timed out."""


@dataclass(frozen=True, slots=True)
class StoredSnapshot(Generic[Value]):
    """Hold a verified decoded snapshot, its pointer ID, and collection time."""

    snapshot_id: str
    value: Value
    created: datetime


class RedisSnapshots(Generic[Value]):
    """Store typed, signed snapshots behind an atomically advanced pointer."""

    def __init__(
        self,
        *,
        redis: Any,
        value_type: type[Value],
        secret: bytes,
        command_timeout: float,
        retention_seconds: int,
        schema_revision: str,
        source_revision: str,
        query_revision: str,
        telemetry: MetricsRecorder | None = None,
    ) -> None:
        """Configure revisions, integrity key, deadlines, and retention.

        Args:
            redis: Async Redis-compatible client.
            value_type: Runtime type used to validate decoded snapshot values.
            secret: HMAC key protecting envelope integrity.
            command_timeout: Maximum duration of each Redis command.
            retention_seconds: Expiry applied to snapshots and pointers.
            schema_revision: Cached value schema revision.
            source_revision: Provider data contract revision.
            query_revision: Source query revision.
            telemetry: Optional bounded Redis telemetry recorder.
        """
        self._redis = redis
        self._adapter = TypeAdapter(value_type)
        self._secret = secret
        self._command_timeout = command_timeout
        self._retention_seconds = retention_seconds
        self.schema_revision = schema_revision
        self.source_revision = source_revision
        self.query_revision = query_revision
        self._telemetry = telemetry or NoopMetricsRecorder()

    async def _command(self, operation: str, awaitable: Any) -> Any:
        """Run one Redis awaitable with a deadline and bounded telemetry.

        Args:
            operation: Bounded operation label for telemetry.
            awaitable: Redis operation to await.

        Returns:
            Result returned by the Redis client.

        Raises:
            RedisUnavailable: If Redis fails or the command times out.
        """
        started = perf_counter()
        outcome = "ok"
        try:
            async with asyncio.timeout(self._command_timeout):
                return await awaitable
        except (RedisError, TimeoutError) as exc:
            outcome = "error"
            raise RedisUnavailable("Redis command failed") from exc
        finally:
            self._telemetry.record_redis(
                operation=operation,
                outcome=outcome,
                seconds=perf_counter() - started,
            )

    @staticmethod
    def _text(raw: Any) -> str:
        """Decode a Redis response as strict UTF-8 text.

        Args:
            raw: Bytes or string returned by Redis.

        Returns:
            Decoded text.

        Raises:
            ValueError: If the response is not text-like.
            UnicodeDecodeError: If bytes are not valid UTF-8.
        """
        if isinstance(raw, bytes):
            return raw.decode("utf-8", errors="strict")
        if isinstance(raw, str):
            return raw
        raise ValueError("Redis value was not UTF-8 text")

    async def ping(self) -> None:
        """Require a successful bounded Redis health check."""
        if not await self._command("ping", self._redis.ping()):
            raise RedisUnavailable("Redis ping failed")

    async def pointer(self, keys: CacheKeys) -> str | None:
        """Read the latest immutable snapshot ID, treating invalid text as a miss."""
        raw = await self._command("get", self._redis.get(keys.latest))
        if raw is None:
            return None
        try:
            return self._text(raw)
        except (UnicodeDecodeError, ValueError):
            return None

    async def read(self, keys: CacheKeys) -> StoredSnapshot[Value] | None:
        """Read, authenticate, revision-check, and decode the latest snapshot.

        Args:
            keys: Derived Redis keys for one cache identity.

        Returns:
            A verified typed snapshot, or ``None`` for any unusable payload.
        """
        snapshot_id = await self.pointer(keys)
        if snapshot_id is None:
            return None
        raw = await self._command("get", self._redis.get(keys.snapshot(snapshot_id)))
        if raw is None:
            return None
        try:
            envelope = SnapshotEnvelope.model_validate_json(self._text(raw))
            if (
                envelope.schema_revision != self.schema_revision
                or envelope.source_revision != self.source_revision
                or envelope.query_revision != self.query_revision
                or envelope.snapshot_id != snapshot_id
                or not envelope.verify(self._secret)
            ):
                return None
            value = self._adapter.validate_python(envelope.value)
        except (UnicodeDecodeError, ValueError, ValidationError, json.JSONDecodeError):
            return None
        return StoredSnapshot(snapshot_id, value, envelope.created)

    async def publish(
        self,
        *,
        keys: CacheKeys,
        snapshot_id: str,
        created: datetime,
        value: Value,
    ) -> None:
        """Persist a signed immutable snapshot and atomically advance its pointer.

        Args:
            keys: Derived Redis keys for one cache identity.
            snapshot_id: Unique immutable snapshot ID.
            created: Source collection time.
            value: Typed value to encode inside the envelope.
        """
        envelope = SnapshotEnvelope(
            format="metrics-cache-v1",
            schema_revision=self.schema_revision,
            source_revision=self.source_revision,
            query_revision=self.query_revision,
            snapshot_id=snapshot_id,
            created=created,
            value=self._adapter.dump_python(value, mode="json"),
            integrity="",
        )
        envelope.integrity = hmac.new(
            self._secret,
            envelope.signed_bytes(),
            hashlib.sha256,
        ).hexdigest()
        payload = envelope.model_dump_json()
        await self._command(
            "publish",
            self._redis.eval(
                _PUBLISH,
                2,
                keys.snapshot(snapshot_id),
                keys.latest,
                payload,
                snapshot_id,
                self._retention_seconds,
            ),
        )

    async def acquire_lease(
        self,
        *,
        keys: CacheKeys,
        bucket: int,
        token: str,
        lease_seconds: float,
    ) -> bool:
        """Acquire one refresh-bucket lease with a unique owner token.

        Args:
            keys: Derived Redis keys for one cache identity.
            bucket: Shared freshness time bucket.
            token: Unique owner token.
            lease_seconds: Positive lease lifetime.

        Returns:
            Whether this caller acquired the lease.
        """
        result = await self._command(
            "lease_acquire",
            self._redis.set(
                keys.lease(bucket),
                token,
                nx=True,
                px=max(1, int(lease_seconds * 1000)),
            ),
        )
        return bool(result)

    async def release_lease(self, *, keys: CacheKeys, bucket: int, token: str) -> None:
        """Delete a lease only when the caller's token still owns it.

        Args:
            keys: Derived Redis keys for one cache identity.
            bucket: Shared freshness time bucket.
            token: Unique owner token used during acquisition.
        """
        await self._command(
            "lease_release",
            self._redis.eval(_RELEASE, 1, keys.lease(bucket), token),
        )
