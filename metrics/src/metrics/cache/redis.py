"""Redis adapter for the two-key signed Metrics cache."""

from __future__ import annotations

import asyncio
import json
import re
from collections.abc import Awaitable
from dataclasses import dataclass
from datetime import UTC, datetime
from time import perf_counter
from typing import Generic, Literal, Protocol, TypeAlias, TypeVar, cast

from pydantic import TypeAdapter, ValidationError
from redis.asyncio import Redis
from redis.exceptions import RedisError

from metrics.cache.models import CacheEnvelope, CacheFailureCategory, CacheKeys
from metrics.telemetry import MetricsRecorder, NoopMetricsRecorder

Value = TypeVar("Value")
CommandResult = TypeVar("CommandResult")
_RedisArgument: TypeAlias = bytes | bytearray | memoryview | str | int | float
_TOKEN_PATTERN = re.compile(r"^[A-Za-z0-9_-]{1,128}$")


class _AsyncRedis(Protocol):
    """Minimal async Redis seam used by the adapter."""

    def ping(self) -> Awaitable[object]:
        """Check server reachability."""

    def get(self, name: str, /) -> Awaitable[object]:
        """Read one value."""

    def set(
        self,
        name: str,
        value: _RedisArgument,
        /,
        *,
        nx: bool = False,
        px: int | None = None,
    ) -> Awaitable[object]:
        """Set one value, optionally only when absent."""

    def eval(
        self,
        script: str,
        numkeys: int,
        /,
        *keys_and_args: _RedisArgument,
    ) -> Awaitable[object]:
        """Execute one atomic Lua operation."""


_COMMIT = """
if redis.call('GET', KEYS[2]) ~= ARGV[2] then
  return 0
end
redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[3])
redis.call('DEL', KEYS[2])
return 1
"""

_RELEASE = """
if redis.call('GET', KEYS[1]) == ARGV[1] then
  return redis.call('DEL', KEYS[1])
end
return 0
"""


class RedisUnavailable(RuntimeError):
    """Indicate a bounded Redis command failure."""


@dataclass(frozen=True, slots=True)
class StoredSnapshot(Generic[Value]):
    """Hold one verified positive envelope."""

    value: Value
    created: datetime


@dataclass(frozen=True, slots=True)
class StoredNotFound:
    """Hold one verified negative envelope and its creation time."""

    created: datetime


@dataclass(frozen=True, slots=True)
class StoredFailure:
    """Hold one verified failed-fill outcome and its cooldown start time."""

    created: datetime
    category: CacheFailureCategory


class RedisSnapshots(Generic[Value]):
    """Read and fenced-write typed signed envelopes in Redis."""

    def __init__(
        self,
        *,
        redis: Redis | _AsyncRedis,
        value_type: type[Value],
        secret: bytes,
        command_timeout: float,
        schema_revision: str,
        source_revision: str,
        query_revision: str,
        telemetry: MetricsRecorder | None = None,
    ) -> None:
        """Configure validation, signing, bounded commands, and revisions."""
        self._redis = cast(_AsyncRedis, redis)
        self._adapter = TypeAdapter(value_type)
        self._secret = secret
        self._command_timeout = command_timeout
        self.schema_revision = schema_revision
        self.source_revision = source_revision
        self.query_revision = query_revision
        self._telemetry = telemetry or NoopMetricsRecorder()

    async def _command(
        self,
        operation: str,
        awaitable: Awaitable[CommandResult],
    ) -> CommandResult:
        """Run a Redis command with a deadline and non-fatal telemetry."""
        started = perf_counter()
        outcome = "ok"
        try:
            async with asyncio.timeout(self._command_timeout):
                return await awaitable
        except asyncio.CancelledError:
            outcome = "cancelled"
            raise
        except (RedisError, TimeoutError) as exc:
            outcome = "error"
            raise RedisUnavailable("Redis command failed") from exc
        finally:
            try:
                self._telemetry.record_redis(
                    operation=operation,
                    outcome=outcome,
                    seconds=perf_counter() - started,
                )
            except Exception:
                pass

    @staticmethod
    def _text(raw: object) -> str:
        """Decode one strict Redis text response."""
        if isinstance(raw, bytes):
            return raw.decode("utf-8", errors="strict")
        if isinstance(raw, str):
            return raw
        raise ValueError("Redis value was not UTF-8 text")

    @staticmethod
    def _valid_token(token: str) -> bool:
        """Bound lease tokens before passing them to Redis."""
        return bool(_TOKEN_PATTERN.fullmatch(token))

    @staticmethod
    def _normalise_created(created: datetime) -> datetime:
        """Normalise source timestamps for signed payloads."""
        if created.tzinfo is None:
            return created.replace(tzinfo=UTC)
        return created.astimezone(UTC)

    def _sign(
        self,
        *,
        keys: CacheKeys,
        kind: Literal["value", "not_found", "failure"],
        created: datetime,
        value: Value | None,
        failure_category: CacheFailureCategory | None = None,
    ) -> str:
        """Build and sign one positive or negative envelope."""
        encoded = None if value is None else self._adapter.dump_python(value, mode="json")
        envelope = CacheEnvelope(
            format="metrics-cache-v2",
            identity_digest=keys.identity_digest,
            schema_revision=self.schema_revision,
            source_revision=self.source_revision,
            query_revision=self.query_revision,
            kind=kind,
            created=self._normalise_created(created),
            value=encoded,
            failure_category=failure_category,
            integrity="",
        ).sign(self._secret)
        return envelope.model_dump_json()

    async def ping(self) -> None:
        """Require a successful bounded Redis health check."""
        result = await self._command("ping", self._redis.ping())
        if result not in (True, b"PONG", "PONG"):
            raise RedisUnavailable("Redis ping returned an invalid result")

    async def read(
        self, keys: CacheKeys
    ) -> StoredSnapshot[Value] | StoredNotFound | StoredFailure | None:
        """Read and verify one envelope; malformed state is a cache miss."""
        raw = await self._command("get", self._redis.get(keys.value))
        if raw is None:
            return None
        try:
            envelope = CacheEnvelope.model_validate_json(self._text(raw))
            if (
                envelope.identity_digest != keys.identity_digest
                or envelope.schema_revision != self.schema_revision
                or envelope.source_revision != self.source_revision
                or envelope.query_revision != self.query_revision
                or not envelope.verify(self._secret)
            ):
                return None
            if envelope.kind == "not_found":
                if envelope.value is not None:
                    return None
                return StoredNotFound(envelope.created)
            if envelope.kind == "failure":
                if envelope.value is not None or envelope.failure_category is None:
                    return None
                return StoredFailure(envelope.created, envelope.failure_category)
            if envelope.failure_category is not None:
                return None
            if envelope.value is None:
                return None
            return StoredSnapshot(
                self._adapter.validate_python(envelope.value),
                envelope.created,
            )
        except (UnicodeDecodeError, ValueError, ValidationError, json.JSONDecodeError):
            return None

    async def commit(
        self,
        *,
        keys: CacheKeys,
        token: str,
        created: datetime,
        value: Value | None,
        ttl_seconds: float,
        not_found: bool = False,
        failure_category: CacheFailureCategory | None = None,
    ) -> bool:
        """Atomically write a signed envelope only for the exact lease owner."""
        if not self._valid_token(token):
            raise ValueError("token must be a bounded ASCII value")
        if ttl_seconds <= 0:
            return False
        if not_found and failure_category is not None:
            raise ValueError("a cache commit cannot be both not-found and failure")
        kind: Literal["value", "not_found", "failure"] = (
            "not_found" if not_found else "failure" if failure_category is not None else "value"
        )
        if (not_found or failure_category is not None) and value is not None:
            raise ValueError("negative cache commits cannot carry a value")
        if not not_found and failure_category is None and value is None:
            raise ValueError("positive commits require a value")
        payload = self._sign(
            keys=keys,
            kind=kind,
            created=created,
            value=value,
            failure_category=failure_category,
        )
        result = await self._command(
            "commit",
            self._redis.eval(
                _COMMIT,
                2,
                keys.value,
                keys.lease,
                payload,
                token,
                max(1, int(ttl_seconds * 1000)),
            ),
        )
        if type(result) is not int or result not in {0, 1}:
            raise RedisUnavailable("Redis commit returned an invalid result")
        return bool(result)

    async def acquire_lease(
        self,
        *,
        keys: CacheKeys,
        token: str,
        lease_seconds: float,
    ) -> bool:
        """Acquire the stable lease with Redis SET NX PX."""
        if not self._valid_token(token):
            raise ValueError("token must be a bounded ASCII value")
        result = await self._command(
            "lease_acquire",
            self._redis.set(
                keys.lease,
                token,
                nx=True,
                px=max(1, int(lease_seconds * 1000)),
            ),
        )
        if result in (True, b"OK", "OK"):
            return True
        if result is None or result is False:
            return False
        raise RedisUnavailable("Redis lease acquire returned an invalid result")

    async def release_lease(self, *, keys: CacheKeys, token: str) -> None:
        """Release only the lease still owned by this token."""
        if not self._valid_token(token):
            raise ValueError("token must be a bounded ASCII value")
        result = await self._command(
            "lease_release",
            self._redis.eval(_RELEASE, 1, keys.lease, token),
        )
        if type(result) is not int or result not in {0, 1}:
            raise RedisUnavailable("Redis lease release returned an invalid result")
