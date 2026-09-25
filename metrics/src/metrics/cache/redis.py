"""Redis adapter for the two-key Metrics cache.

Each subject owns two keys (see ``CacheKeys``):

``value``
    ``b"v" + mac + json`` (snapshot) or ``b"n" + mac`` (not-found), written
    with ``PX`` equal to the remaining stale (or fresh) window. The MAC is
    HMAC-SHA256 over the key name, the kind byte, and the raw body, so the
    key binds identity and revisions and verification needs no canonical
    re-serialisation.
``lease``
    A refresher token (32 hex characters) while one owner performs source
    work, or a cooldown marker ``"!" + CacheFailureCategory`` after a failed
    fill. ``SET NX`` fails for everyone while either exists.

Two Lua scripts perform every state change. ``OBSERVE`` reads the value and
its remaining TTL and, only when the value is absent, unreadable, or stale,
claims the lease in the same atomic step. ``SETTLE`` is the token-fenced exit
of the owner: publish, cool down, or release.
"""

from __future__ import annotations

import asyncio
import hashlib
import hmac
from collections.abc import Awaitable
from dataclasses import dataclass
from time import perf_counter
from typing import Any, Generic, Protocol, TypeAlias, TypeVar, cast

from pydantic import TypeAdapter
from redis.asyncio import Redis
from redis.exceptions import NoScriptError, RedisError

from metrics.cache.models import CacheFailureCategory, CacheKeys
from metrics.telemetry import MetricsRecorder, NoopMetricsRecorder

Value = TypeVar("Value")
CommandResult = TypeVar("CommandResult")
_RedisArgument: TypeAlias = bytes | str | int | float

_SNAPSHOT = b"v"
_NOT_FOUND = b"n"
_MAC_LENGTH = 64
_COOLDOWN_PREFIX = b"!"

# KEYS: value, lease.
# ARGV: fresh floor ms, token, lease ms, claim (0|1), force (0|1).
# Reply: {value|nil, value pttl, claimed 0|1, lease|nil, lease pttl}.
# A live not-found or a fresh snapshot is never claimed over. ``force``
# claims over a value the caller could not authenticate.
_OBSERVE = """
local v = redis.call('GET', KEYS[1])
local ttl = -2
if v then ttl = redis.call('PTTL', KEYS[1]) end
local keep = false
if v and ttl > 0 and ARGV[5] ~= '1' then
  local kind = string.byte(v, 1)
  keep = kind == 110 or (kind == 118 and ttl >= tonumber(ARGV[1]))
end
if keep or ARGV[4] ~= '1' then
  return {v, ttl, 0, false, -2}
end
if redis.call('SET', KEYS[2], ARGV[2], 'NX', 'PX', ARGV[3]) then
  return {v, ttl, 1, false, -2}
end
return {v, ttl, 0, redis.call('GET', KEYS[2]), redis.call('PTTL', KEYS[2])}
"""

# KEYS: value, lease.
# ARGV: token, mode (publish|cooldown|release), payload or marker, px.
# Every mode acts only while ``token`` still owns the lease. A cooldown never
# outlives a value that still exists, so it cannot block the cold path after
# the stale snapshot it protected has expired.
_SETTLE = """
if redis.call('GET', KEYS[2]) ~= ARGV[1] then return 0 end
if ARGV[2] == 'publish' then
  redis.call('SET', KEYS[1], ARGV[3], 'PX', ARGV[4])
  redis.call('DEL', KEYS[2])
elseif ARGV[2] == 'cooldown' then
  local px = tonumber(ARGV[4])
  local remaining = redis.call('PTTL', KEYS[1])
  if remaining > 0 and remaining < px then px = remaining end
  redis.call('SET', KEYS[2], ARGV[3], 'PX', px)
else
  redis.call('DEL', KEYS[2])
end
return 1
"""

_OBSERVE_SHA = hashlib.sha1(_OBSERVE.encode()).hexdigest()
_SETTLE_SHA = hashlib.sha1(_SETTLE.encode()).hexdigest()


class _AsyncRedis(Protocol):
    """Minimal async Redis seam used by the adapter."""

    def ping(self) -> Awaitable[object]:
        """Check server reachability."""

    def eval(self, script: str, numkeys: int, /, *args: _RedisArgument) -> Awaitable[object]:
        """Execute one atomic Lua script."""

    def evalsha(self, sha: str, numkeys: int, /, *args: _RedisArgument) -> Awaitable[object]:
        """Execute one cached Lua script."""


class RedisUnavailable(RuntimeError):
    """Indicate a bounded Redis command failure."""


@dataclass(frozen=True, slots=True)
class StoredSnapshot(Generic[Value]):
    """Hold one authenticated positive snapshot."""

    value: Value


@dataclass(frozen=True, slots=True)
class StoredNotFound:
    """Mark one authenticated subject-level not-found."""


@dataclass(frozen=True, slots=True)
class Observation(Generic[Value]):
    """Describe one atomic ``OBSERVE`` reply.

    Attributes:
        stored: The authenticated value, or ``None`` when absent or unreadable.
        ttl_ms: Remaining value TTL; only meaningful when ``stored`` is set.
        claimed: Whether this call's token now owns the lease.
        unreadable: Whether a value existed but failed authentication or decoding.
        cooldown: The failure category of an active cooldown lease, if any.
    """

    stored: StoredSnapshot[Value] | StoredNotFound | None
    ttl_ms: int
    claimed: bool
    unreadable: bool = False
    cooldown: CacheFailureCategory | None = None


class RedisSnapshots(Generic[Value]):
    """Observe, claim, and fenced-settle typed snapshots in Redis."""

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
        """Configure validation, authentication, bounded commands, and key revisions."""
        # redis-py types commands as ``Awaitable[T] | T``; the async client
        # always returns awaitables.
        self._redis = cast(_AsyncRedis, redis)
        self._adapter: TypeAdapter[Value] = TypeAdapter(value_type)
        self._secret = secret
        self.command_timeout = command_timeout
        self.schema_revision = schema_revision
        self.source_revision = source_revision
        self.query_revision = query_revision
        self._telemetry = telemetry or NoopMetricsRecorder()

    async def _command(self, operation: str, awaitable: Awaitable[CommandResult]) -> CommandResult:
        """Run one Redis command under the command deadline."""
        started = perf_counter()
        outcome = "ok"
        try:
            async with asyncio.timeout(self.command_timeout):
                return await awaitable
        except asyncio.CancelledError:
            outcome = "cancelled"
            raise
        except (RedisError, TimeoutError) as exc:
            outcome = "error"
            raise RedisUnavailable(f"Redis {operation} failed") from exc
        finally:
            self._telemetry.record_redis(
                operation=operation, outcome=outcome, seconds=perf_counter() - started
            )

    async def _script(self, sha: str, source: str, *args: _RedisArgument) -> Any:
        """Run a cached script, loading it once after a Redis restart or failover."""
        try:
            return await self._redis.evalsha(sha, 2, *args)
        except NoScriptError:
            return await self._redis.eval(source, 2, *args)

    def _mac(self, key: str, kind: bytes, body: bytes) -> bytes:
        """Authenticate raw bytes bound to their Redis key name."""
        message = key.encode() + b"\0" + kind + body
        return hmac.new(self._secret, message, hashlib.sha256).hexdigest().encode()

    def encode(self, keys: CacheKeys, stored: StoredSnapshot[Value] | StoredNotFound) -> bytes:
        """Serialise and authenticate one value-key payload."""
        if isinstance(stored, StoredNotFound):
            kind, body = _NOT_FOUND, b""
        else:
            kind, body = _SNAPSHOT, self._adapter.dump_json(stored.value)
        return kind + self._mac(keys.value, kind, body) + body

    def decode(self, keys: CacheKeys, raw: bytes) -> StoredSnapshot[Value] | StoredNotFound | None:
        """Return the authenticated payload, or ``None`` for any unreadable value."""
        kind, mac, body = raw[:1], raw[1 : 1 + _MAC_LENGTH], raw[1 + _MAC_LENGTH :]
        if kind not in (_SNAPSHOT, _NOT_FOUND):
            return None
        if not hmac.compare_digest(mac, self._mac(keys.value, kind, body)):
            return None
        if kind == _NOT_FOUND:
            return StoredNotFound() if not body else None
        try:
            return StoredSnapshot(self._adapter.validate_json(body))
        except ValueError:  # includes pydantic's ValidationError
            return None

    async def ping(self) -> None:
        """Require a successful bounded Redis health check."""
        result = await self._command("ping", self._redis.ping())
        if result not in (True, b"PONG", "PONG"):
            raise RedisUnavailable("Redis ping returned an invalid result")

    async def observe(
        self,
        keys: CacheKeys,
        *,
        token: str,
        fresh_floor_ms: int,
        lease_ms: int,
        claim: bool,
        force: bool = False,
    ) -> Observation[Value]:
        """Read the value and its TTL, claiming the lease when refresh work is due."""
        reply = await self._command(
            "observe",
            self._script(
                _OBSERVE_SHA,
                _OBSERVE,
                keys.value,
                keys.lease,
                fresh_floor_ms,
                token,
                lease_ms,
                int(claim),
                int(force),
            ),
        )
        try:
            raw, ttl, claimed, lease, _lease_ttl = reply
            ttl, claimed = int(ttl), int(claimed) == 1
        except (TypeError, ValueError) as exc:
            raise RedisUnavailable("Redis observe returned an invalid reply") from exc
        # A client-side retry after a lost reply finds the lease already set
        # to this call's own token; that is still this call's claim.
        claimed = claimed or (claim and lease == token.encode())
        stored = self.decode(keys, raw) if isinstance(raw, bytes) and ttl > 0 else None
        cooldown = None
        if isinstance(lease, bytes) and lease.startswith(_COOLDOWN_PREFIX):
            try:
                cooldown = CacheFailureCategory(lease[1:].decode())
            except ValueError:
                cooldown = CacheFailureCategory.INTERNAL
        return Observation(
            stored=stored,
            ttl_ms=ttl,
            claimed=claimed,
            unreadable=raw is not None and stored is None,
            cooldown=cooldown,
        )

    async def _settle(
        self, operation: str, keys: CacheKeys, token: str, mode: str, arg: bytes, px: int
    ) -> bool:
        """Run one token-fenced lease exit."""
        result = await self._command(
            operation,
            self._script(
                _SETTLE_SHA, _SETTLE, keys.value, keys.lease, token, mode, arg, max(1, px)
            ),
        )
        if result not in (0, 1):
            raise RedisUnavailable(f"Redis {operation} returned an invalid result")
        return result == 1

    async def publish(
        self,
        keys: CacheKeys,
        *,
        token: str,
        stored: StoredSnapshot[Value] | StoredNotFound,
        ttl_ms: int,
    ) -> bool:
        """Publish one payload and free the lease; ``False`` means the token was fenced."""
        return await self._settle(
            "publish", keys, token, "publish", self.encode(keys, stored), ttl_ms
        )

    async def cool_down(
        self,
        keys: CacheKeys,
        *,
        token: str,
        category: CacheFailureCategory,
        cooldown_ms: int,
    ) -> bool:
        """Turn the owned lease into a failure cooldown capped by the value's TTL."""
        marker = _COOLDOWN_PREFIX + category.value.encode()
        return await self._settle("cooldown", keys, token, "cooldown", marker, cooldown_ms)

    async def release(self, keys: CacheKeys, *, token: str) -> bool:
        """Free the lease only while ``token`` still owns it."""
        return await self._settle("release", keys, token, "release", b"", 1)
