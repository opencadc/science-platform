"""Redis snapshot storage, pointer publication, and token-owned leases."""

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
    """Raised when a bounded Redis command cannot complete."""


@dataclass(frozen=True, slots=True)
class StoredSnapshot(Generic[Value]):
    """Decoded immutable snapshot and its pointer ID."""

    snapshot_id: str
    value: Value
    created: datetime


class RedisSnapshots(Generic[Value]):
    """Strict JSON snapshot repository over one Redis client."""

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
        """Configure revisions, integrity key, deadlines, and retention."""
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
        """Read the latest immutable snapshot ID."""
        raw = await self._command("get", self._redis.get(keys.latest))
        if raw is None:
            return None
        try:
            return self._text(raw)
        except (UnicodeDecodeError, ValueError):
            return None

    async def read(self, keys: CacheKeys) -> StoredSnapshot[Value] | None:
        """Read and verify the snapshot named by the latest pointer."""
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
        """Atomically persist an immutable snapshot and advance its pointer."""
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
        """Acquire one refresh-bucket lease with a unique owner token."""
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
        """Delete a lease only when ``token`` still owns it."""
        await self._command(
            "lease_release",
            self._redis.eval(_RELEASE, 1, keys.lease(bucket), token),
        )
