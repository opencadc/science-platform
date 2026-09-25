"""Coordinate two-stage Redis snapshots with one refresher per subject.

Stages come only from the value key's remaining Redis TTL (``PTTL``):

* FRESH: ``PTTL >= stale - fresh``. Serve it.
* STALE: ``0 < PTTL < stale - fresh``. Serve it; the one request whose
  ``OBSERVE`` claimed the lease starts a detached refresh.
* absent: the stale window has ended and Redis deleted the key. One claimant
  fills; every other caller waits for its publication.

Source work starts only in ``_own``, and ``_own`` starts only with a token for
which ``OBSERVE`` performed ``SET NX`` on the lease. Every exit of the owner
(publish, cooldown, release) is token-fenced in Lua. A failed fill turns the
lease into a short cooldown, so no replica retries the source until it ends.

Process-local structures only save round trips: ``_flights`` coalesces
concurrent requests onto one observation, and ``_owners`` holds at most one
source fill per subject. Owners are detached from requests, so a
disconnecting client never cancels work other replicas are waiting for.
"""

from __future__ import annotations

import asyncio
import logging
import uuid
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, replace
from datetime import UTC, datetime, timedelta
from typing import Generic, Protocol, TypeVar

from metrics.cache.memory import MemorySnapshots
from metrics.cache.models import (
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
from metrics.cache.redis import Observation, RedisUnavailable, StoredNotFound, StoredSnapshot
from metrics.telemetry import MetricsRecorder, NoopMetricsRecorder

Value = TypeVar("Value")

_logger = logging.getLogger(__name__)
_MAX_CHAIN = 6


class _CoordinatorStore(Protocol[Value]):
    """Durable-store seam used by the coordinator and test fakes."""

    schema_revision: str
    source_revision: str
    query_revision: str
    command_timeout: float

    async def ping(self) -> None:
        """Check durable-store reachability."""

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
        """Read the value and TTL, claiming the lease when work is due."""

    async def publish(
        self,
        keys: CacheKeys,
        *,
        token: str,
        stored: StoredSnapshot[Value] | StoredNotFound,
        ttl_ms: int,
    ) -> bool:
        """Publish and free the lease while ``token`` owns it."""

    async def cool_down(
        self,
        keys: CacheKeys,
        *,
        token: str,
        category: CacheFailureCategory,
        cooldown_ms: int,
    ) -> bool:
        """Turn the owned lease into a failure cooldown."""

    async def release(self, keys: CacheKeys, *, token: str) -> bool:
        """Free the lease while ``token`` owns it."""


@dataclass(frozen=True, slots=True)
class _Remembered(Generic[Value]):
    """Hold one positive observation with loop-clock stage deadlines."""

    value: Value
    fresh_until: float
    expires: float


@dataclass(frozen=True, slots=True)
class _Outcome(Generic[Value]):
    """Carry one flight result and whether a source fill produced it."""

    result: CacheResult[Value]
    filled: bool


def describe_failure(exc: BaseException) -> str:
    """Summarise an exception chain by type and HTTP status, never by message.

    Messages can carry subject values, so only exception types and the HTTP
    status of any upstream response are rendered.
    """
    parts: list[str] = []
    seen: set[int] = set()
    current: BaseException | None = exc
    while current is not None and id(current) not in seen and len(parts) < _MAX_CHAIN:
        seen.add(id(current))
        status = getattr(getattr(current, "response", None), "status_code", None)
        name = type(current).__name__
        parts.append(f"{name}({status})" if isinstance(status, int) else name)
        current = current.__cause__ or current.__context__
    return " <- ".join(parts)


class RedisCoordinator(Generic[Value]):
    """Serve fresh, serve-and-refresh stale, and single-flight cold fills."""

    backend_name = "redis"

    def __init__(
        self,
        *,
        store: _CoordinatorStore[Value],
        key_prefix: str,
        key_secret: bytes,
        policy: FreshnessPolicy,
        fill_timeout: float = 10.0,
        cold_timeout: float = 15.0,
        lease_margin: float = 2.0,
        failure_cooldown: float = 5.0,
        poll_min: float = 0.025,
        poll_max: float = 0.25,
        max_l1_entries: int = 128,
        wall_clock: Callable[[], datetime] | None = None,
        telemetry: MetricsRecorder | None = None,
    ) -> None:
        """Configure deadlines, the lease and cooldown lengths, and local bounds.

        The lease covers the owner's whole hold time: the ``OBSERVE`` reply
        that granted it, the source call (cancelled at ``fill_timeout``), and
        one ``SETTLE``, plus ``lease_margin`` for event-loop stalls.
        """
        self.policy = policy
        self._store = store
        self._key_prefix = key_prefix
        self._key_secret = key_secret
        self._fill_timeout = fill_timeout
        self._cold_timeout = cold_timeout
        self._lease_ms = round((fill_timeout + 2 * store.command_timeout + lease_margin) * 1000)
        self._cooldown_ms = round(min(failure_cooldown, policy.fresh_seconds) * 1000)
        self._poll_min = poll_min
        self._poll_max = max(poll_min, poll_max)
        self._l1 = MemorySnapshots[_Remembered[Value]](max_l1_entries)
        self._wall = wall_clock or (lambda: datetime.now(UTC))
        self._telemetry = telemetry or NoopMetricsRecorder()
        self._flights: dict[str, asyncio.Task[_Outcome[Value]]] = {}
        self._owners: dict[str, asyncio.Task[CacheResult[Value]]] = {}
        self._cleanup: set[asyncio.Task[bool]] = set()
        self._closed = False
        self._available = True

    @property
    def available(self) -> bool:
        """Return whether the latest Redis command succeeded."""
        return self._available

    def _keys(self, identity: CacheIdentity) -> CacheKeys:
        """Derive the two opaque keys for one identity."""
        return cache_keys(
            prefix=self._key_prefix,
            identity=identity,
            secret=self._key_secret,
            schema_revision=self._store.schema_revision,
            source_revision=self._store.source_revision,
            query_revision=self._store.query_revision,
        )

    @staticmethod
    def _now() -> float:
        """Return the event-loop monotonic clock."""
        return asyncio.get_running_loop().time()

    async def ping(self) -> None:
        """Verify the durable cache and update the health bit."""
        try:
            await self._store.ping()
        except RedisUnavailable:
            self._available = False
            raise
        self._available = True

    # ----------------------------------------------------------------- request

    async def get_or_fill(
        self,
        identity: CacheIdentity,
        fill: Callable[[], Awaitable[Value]],
    ) -> CacheResult[Value]:
        """Return a serviceable value or wait, within ``cold_timeout``, for one fill."""
        if self._closed:
            raise CacheUnavailable("Redis cache coordinator is shut down")
        keys = self._keys(identity)
        flight = self._flights.get(keys.base)
        leader = flight is None
        if flight is None:
            flight = self._start_flight(keys, fill, identity.subject_kind)
        try:
            async with asyncio.timeout(self._cold_timeout):
                outcome = await asyncio.shield(flight)
        except TimeoutError as exc:
            raise CacheUnavailable(
                "Cold cache fill timed out", cache_available=self._available
            ) from exc
        except asyncio.CancelledError:
            current = asyncio.current_task()
            if flight.cancelled() and current is not None and not current.cancelling():
                raise CacheUnavailable("Redis cache coordinator is shut down") from None
            raise
        if outcome.filled and not leader:
            return replace(outcome.result, cached=True, source_reachable=None)
        return outcome.result

    def _start_flight(
        self, keys: CacheKeys, fill: Callable[[], Awaitable[Value]], scope: str
    ) -> asyncio.Task[_Outcome[Value]]:
        """Start the one local observation shared by concurrent requests."""
        task = asyncio.create_task(self._resolve(keys, fill, scope))
        self._flights[keys.base] = task

        def finish(done: asyncio.Task[_Outcome[Value]]) -> None:
            if self._flights.get(keys.base) is done:
                del self._flights[keys.base]
            if not done.cancelled():
                done.exception()

        task.add_done_callback(finish)
        return task

    async def _resolve(
        self, keys: CacheKeys, fill: Callable[[], Awaitable[Value]], scope: str
    ) -> _Outcome[Value]:
        """Observe until a snapshot, a not-found, a cooldown, or a fill resolves the key."""
        deadline = self._now() + self._cold_timeout
        delay = self._poll_min
        force = False
        first = True
        while True:
            token = uuid.uuid4().hex
            # A local owner already holds (or just settled) the lease; never race it.
            claim = keys.base not in self._owners and not self._closed
            sent = self._now()
            try:
                seen = await self._store.observe(
                    keys,
                    token=token,
                    fresh_floor_ms=self.policy.fresh_floor_ms,
                    lease_ms=self._lease_ms,
                    claim=claim,
                    force=force,
                )
            except RedisUnavailable as exc:
                self._available = False
                self._telemetry.record_lease(outcome="error", scope=scope)
                if claim:
                    self._release_later(keys, token)  # SET NX may have applied
                return self._fallback(keys, scope, exc)
            except asyncio.CancelledError:
                if claim:
                    self._release_later(keys, token)
                raise
            self._available = True
            if seen.claimed and self._closed:  # shutdown began during the round trip
                self._release_later(keys, token)
                seen = replace(seen, claimed=False)
            if seen.claimed:
                self._telemetry.record_lease(outcome="acquired", scope=scope)
            if seen.unreadable and not seen.claimed and not force:
                force = True  # overwrite through one owner; never spin on it
                continue
            force = False

            # Telemetry counts one lookup per flight: its first observation.
            if isinstance(seen.stored, StoredNotFound):
                self._l1.evict(keys.base)
                if first:
                    self._telemetry.record_cache_lookup(
                        backend=self.backend_name, result="hit", scope=scope, age_seconds=None
                    )
                raise CacheNotFound()

            if isinstance(seen.stored, StoredSnapshot):
                state = self.policy.classify(seen.ttl_ms) or Freshness.STALE
                self._remember(keys, seen.stored.value, sent, seen.ttl_ms)
                if first:
                    self._telemetry.record_cache_lookup(
                        backend=self.backend_name,
                        result="stale" if state is Freshness.STALE else "hit",
                        scope=scope,
                        age_seconds=self.policy.age_seconds(seen.ttl_ms),
                    )
                if seen.claimed:  # stale: this request alone starts the refresh
                    self._start_owner(keys, fill, scope, token)
                return _Outcome(
                    self._result(seen.stored.value, state, seen.ttl_ms, cached=True), filled=False
                )

            if first:
                self._telemetry.record_cache_lookup(
                    backend=self.backend_name, result="miss", scope=scope, age_seconds=None
                )
            if seen.claimed:
                owner = self._start_owner(keys, fill, scope, token)
                return await self._await_owner(owner, deadline)
            local = self._owners.get(keys.base)
            if local is not None:
                return await self._await_owner(local, deadline)
            if seen.cooldown is not None:
                if first:
                    self._telemetry.record_lease(outcome="cooldown", scope=scope)
                raise self._failed_fill(seen.cooldown)
            if first:
                self._telemetry.record_lease(outcome="contended", scope=scope)
            first = False
            remaining = deadline - self._now()
            if remaining <= 0:
                raise CacheUnavailable("Cold cache fill timed out", cache_available=True)
            await asyncio.sleep(min(delay, remaining))
            delay = min(delay * 2, self._poll_max)

    async def _await_owner(
        self, owner: asyncio.Task[CacheResult[Value]], deadline: float
    ) -> _Outcome[Value]:
        """Wait for a detached owner without letting the wait cancel it."""
        try:
            async with asyncio.timeout_at(deadline):
                result = await asyncio.shield(owner)
        except TimeoutError as exc:
            raise CacheUnavailable("Cold cache fill timed out", cache_available=True) from exc
        return _Outcome(result, filled=True)

    # ------------------------------------------------------------------- owner

    def _start_owner(
        self,
        keys: CacheKeys,
        fill: Callable[[], Awaitable[Value]],
        scope: str,
        token: str,
    ) -> asyncio.Task[CacheResult[Value]]:
        """Run the one source fill this lease admits, detached from every request."""
        task = asyncio.create_task(self._own(keys, fill, scope, token))
        self._owners[keys.base] = task

        def finish(done: asyncio.Task[CacheResult[Value]]) -> None:
            if self._owners.get(keys.base) is done:
                del self._owners[keys.base]
            if not done.cancelled():
                done.exception()

        task.add_done_callback(finish)
        return task

    async def _own(
        self,
        keys: CacheKeys,
        fill: Callable[[], Awaitable[Value]],
        scope: str,
        token: str,
    ) -> CacheResult[Value]:
        """Fill once under the lease and leave through exactly one fenced exit."""
        started = self._now()
        outcome = "ok"
        try:
            try:
                async with asyncio.timeout(self._fill_timeout):
                    value = await fill()
            except TimeoutError as exc:
                raise CacheFillTimeout() from exc
        except asyncio.CancelledError:
            outcome = "cancelled"
            self._release_later(keys, token)
            raise
        except CacheNotFound:
            outcome = "not_found"
            ttl_ms = round(self.policy.fresh_seconds * 1000 - (self._now() - started) * 1000)
            await self._settle_quietly(
                self._store.publish(keys, token=token, stored=StoredNotFound(), ttl_ms=ttl_ms)
            )
            self._l1.evict(keys.base)
            raise CacheNotFound() from None
        except Exception as exc:
            outcome = "timeout" if isinstance(exc, CacheFillTimeout) else "error"
            category = self._failure_category(exc)
            await self._settle_quietly(
                self._store.cool_down(
                    keys, token=token, category=category, cooldown_ms=self._cooldown_ms
                )
            )
            _logger.warning(
                "cache fill failed scope=%s category=%s error=%s",
                scope,
                category.value,
                describe_failure(exc),
            )
            raise self._failed_fill(category) from exc
        finally:
            self._telemetry.record_fill_duration(
                seconds=self._now() - started, outcome=outcome, scope=scope
            )

        # Age runs from fill start on this process's monotonic clock, so no
        # replica or source clock enters the snapshot's Redis lifetime.
        ttl_ms = self.policy.stale_ms - round((self._now() - started) * 1000)
        state = self.policy.classify(ttl_ms)
        if state is None:
            await self._settle_quietly(
                self._store.cool_down(
                    keys,
                    token=token,
                    category=CacheFailureCategory.SOURCE_UNAVAILABLE,
                    cooldown_ms=self._cooldown_ms,
                )
            )
            _logger.warning("cache fill outlived the stale window scope=%s", scope)
            raise self._failed_fill(CacheFailureCategory.SOURCE_UNAVAILABLE)
        sent = self._now()
        try:
            published = await self._settle_quietly(
                self._store.publish(keys, token=token, stored=StoredSnapshot(value), ttl_ms=ttl_ms)
            )
        except asyncio.CancelledError:
            self._release_later(keys, token)  # a no-op if the publish already applied
            raise
        if published:
            self._remember(keys, value, sent, ttl_ms)
        # A fenced owner still returns its genuine observation to its own
        # waiters, but never publishes it or keeps it for outage fallback.
        return self._result(value, state, ttl_ms, cached=False, source_reachable=True)

    async def _settle_quietly(self, settle: Awaitable[bool]) -> bool:
        """Run one fenced exit; a Redis failure leaves the lease to expire on its TTL."""
        try:
            settled = await settle
        except RedisUnavailable:
            self._available = False
            return False
        self._available = True
        return settled

    def _release_later(self, keys: CacheKeys, token: str) -> None:
        """Release a lease this process may hold but can no longer use."""
        task = asyncio.create_task(self._settle_quietly(self._store.release(keys, token=token)))
        self._cleanup.add(task)
        task.add_done_callback(self._cleanup.discard)

    # ------------------------------------------------------------------ values

    def _remember(self, keys: CacheKeys, value: Value, sent: float, ttl_ms: int) -> None:
        """Keep a positive observation for outage fallback, never past Redis's own TTL."""
        expires = sent + ttl_ms / 1000
        fresh_until = sent + (ttl_ms - self.policy.fresh_floor_ms) / 1000
        self._l1.put(keys.base, _Remembered(value, fresh_until, expires))

    def _result(
        self,
        value: Value,
        state: Freshness,
        ttl_ms: int,
        *,
        cached: bool,
        source_reachable: bool | None = None,
    ) -> CacheResult[Value]:
        """Build one result whose serviceable end is in this reader's clock."""
        return CacheResult(
            value,
            cached=cached,
            stale=state is Freshness.STALE,
            cache_available=self._available,
            source_reachable=source_reachable,
            serviceable_until=self._wall() + timedelta(milliseconds=ttl_ms),
            age_seconds=self.policy.age_seconds(ttl_ms),
        )

    def _fallback(self, keys: CacheKeys, scope: str, exc: Exception) -> _Outcome[Value]:
        """Serve a known serviceable L1 copy during a Redis outage, or fail closed."""
        entry = self._l1.get(keys.base)
        now = self._now()
        if entry is None or now >= entry.expires:
            self._l1.evict(keys.base)
            raise CacheUnavailable(
                "Redis unavailable and no serviceable snapshot", cache_available=False
            ) from exc
        stale = now > entry.fresh_until
        ttl_ms = round((entry.expires - now) * 1000)
        self._telemetry.record_cache_lookup(
            backend=self.backend_name,
            result="stale" if stale else "hit",
            scope=scope,
            age_seconds=self.policy.age_seconds(ttl_ms),
        )
        result = CacheResult(
            entry.value,
            cached=True,
            stale=stale,
            cache_available=False,
            source_reachable=None,
            serviceable_until=self._wall() + timedelta(milliseconds=ttl_ms),
            age_seconds=self.policy.age_seconds(ttl_ms),
        )
        return _Outcome(result, filled=False)

    @staticmethod
    def _failed_fill(category: CacheFailureCategory) -> CacheUnavailable | CacheInternalError:
        """Rebuild the sanitized failure every replica reports for one category."""
        if category is CacheFailureCategory.INTERNAL:
            return CacheInternalError()
        return CacheUnavailable(
            "The source fill is temporarily unavailable",
            cache_available=True,
            source_reachable=False,
        )

    @staticmethod
    def _failure_category(exc: Exception) -> CacheFailureCategory:
        """Reduce a source exception to one bounded category."""
        if isinstance(exc, CacheUnavailable):
            return CacheFailureCategory.SOURCE_UNAVAILABLE
        return CacheFailureCategory.INTERNAL

    # ---------------------------------------------------------------- shutdown

    async def shutdown(self) -> None:
        """Stop new work, cancel flights and owners, and await their lease releases."""
        self._closed = True
        tasks = (*self._flights.values(), *self._owners.values())
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        await asyncio.gather(*tuple(self._cleanup), return_exceptions=True)
        self._flights.clear()
        self._owners.clear()
        self._cleanup.clear()
        self._l1.clear()
