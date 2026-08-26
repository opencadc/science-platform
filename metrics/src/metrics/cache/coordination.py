"""Coordinate two-key Redis reads, fills, leases, and outage fallback."""

from __future__ import annotations

import asyncio
import uuid
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, replace
from datetime import UTC, datetime
from typing import Generic, Literal, Protocol, TypeVar

from metrics.cache.memory import MemorySnapshots
from metrics.cache.models import (
    CacheIdentity,
    CacheKeys,
    CacheFillTimeout,
    CacheFailureCategory,
    CacheInternalError,
    CacheNotFound,
    CacheResult,
    CacheUnavailable,
    Freshness,
    FreshnessPolicy,
    cache_keys,
    serviceable_until,
)
from metrics.cache.redis import RedisUnavailable, StoredFailure, StoredNotFound, StoredSnapshot
from metrics.telemetry import MetricsRecorder, NoopMetricsRecorder

Value = TypeVar("Value")


class _CoordinatorStore(Protocol[Value]):
    """Small durable-store seam used by the coordinator and test fakes."""

    schema_revision: str
    source_revision: str
    query_revision: str

    async def ping(self) -> None:
        """Check durable-store reachability."""

    async def read(
        self, keys: CacheKeys
    ) -> StoredSnapshot[Value] | StoredNotFound | StoredFailure | None:
        """Read one verified envelope."""

    async def acquire_lease(self, *, keys: CacheKeys, token: str, lease_seconds: float) -> bool:
        """Try to acquire the stable subject lease."""

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
        """Commit one signed value only when the token still owns the lease."""

    async def release_lease(self, *, keys: CacheKeys, token: str) -> None:
        """Release the token-owned lease."""


@dataclass(slots=True)
class _LocalFlight(Generic[Value]):
    """Track one local cold fill and its active request waiters."""

    task: asyncio.Task[CacheResult[Value]]
    waiters: int = 0


class RedisCoordinator(Generic[Value]):
    """Provide bounded cache-aside reads with distributed single flight."""

    backend_name = "redis"

    def __init__(
        self,
        *,
        store: _CoordinatorStore[Value],
        key_prefix: str,
        key_secret: bytes,
        policy: FreshnessPolicy,
        created: Callable[[Value], datetime],
        fill_timeout: float = 10.0,
        cold_timeout: float = 12.0,
        poll_min: float = 0.05,
        max_l1_entries: int = 128,
        clock: Callable[[], datetime] | None = None,
        telemetry: MetricsRecorder | None = None,
    ) -> None:
        """Configure source deadlines, Redis identity, and local bounds."""
        self.policy = policy
        self._store = store
        self._key_prefix = key_prefix
        self._key_secret = key_secret
        self._created = created
        self._fill_timeout = max(0.001, fill_timeout)
        self._cold_timeout = max(0.001, cold_timeout)
        self._lease_seconds = max(self._fill_timeout + 1.0, self._cold_timeout + 1.0)
        self._poll_min = max(0.001, poll_min)
        self._l1 = MemorySnapshots[StoredSnapshot[Value]](max_l1_entries)
        self._clock = clock or (lambda: datetime.now(UTC))
        self._telemetry = telemetry or NoopMetricsRecorder()
        self._flights: dict[str, _LocalFlight[Value]] = {}
        self._detached_flights: set[asyncio.Task[CacheResult[Value]]] = set()
        self._refreshes: dict[str, asyncio.Task[None]] = {}
        self._background_tasks: set[asyncio.Task[None]] = set()
        self._closed = False
        self._available = True

    @property
    def available(self) -> bool:
        """Return the latest durable-store health bit for runtime recovery."""
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

    def _record_lookup(
        self,
        *,
        result: Literal["hit", "miss", "stale"],
        scope: str,
        age: float | None = None,
    ) -> None:
        """Record bounded lookup telemetry without coupling cache behavior to it."""
        try:
            self._telemetry.record_cache_lookup(
                backend=self.backend_name,
                result=result,
                scope=scope,
                age_seconds=age,
            )
        except Exception:
            pass

    def _record_lease(self, *, outcome: str, scope: str) -> None:
        """Record one lease outcome defensively."""
        try:
            self._telemetry.record_lease(outcome=outcome, scope=scope)
        except Exception:
            pass

    def _record_fill(self, *, seconds: float, outcome: str, scope: str) -> None:
        """Record one source-fill outcome defensively."""
        try:
            self._telemetry.record_fill_duration(
                seconds=seconds,
                outcome=outcome,
                scope=scope,
            )
        except Exception:
            pass

    async def ping(self) -> None:
        """Verify the durable cache and update the compatibility health bit."""
        try:
            await self._store.ping()
        except RedisUnavailable:
            self._available = False
            raise
        self._available = True

    async def _read(
        self, keys: CacheKeys
    ) -> StoredSnapshot[Value] | StoredNotFound | StoredFailure | None:
        """Read one value while recording the latest durable-store health."""
        try:
            result = await self._store.read(keys)
        except RedisUnavailable:
            self._available = False
            raise
        self._available = True
        return result

    def _remember(
        self,
        keys: CacheKeys,
        snapshot: StoredSnapshot[Value],
        state: Freshness,
    ) -> None:
        """Keep only positive observations that are still serviceable."""
        if state in {Freshness.FRESH, Freshness.STALE}:
            self._l1.put(keys.base, snapshot)
        else:
            self._l1.evict(keys.base)

    def _fallback(self, keys: CacheKeys, scope: str) -> CacheResult[Value] | None:
        """Return a stale, not-ready positive L1 value during Redis outage."""
        snapshot = self._l1.get(keys.base)
        if snapshot is None:
            return None
        now = self._clock()
        state = self.policy.classify(snapshot.created, now=now)
        if state not in {Freshness.FRESH, Freshness.STALE}:
            self._l1.evict(keys.base)
            return None
        self._record_lookup(result="stale", scope=scope)
        return CacheResult(
            snapshot.value,
            cached=True,
            stale=True,
            cache_available=False,
            source_reachable=None,
            serviceable_until=serviceable_until(snapshot.created, self.policy),
        )

    def _result(
        self,
        snapshot: StoredSnapshot[Value],
        *,
        state: Freshness,
        cached: bool,
        source_reachable: bool | None,
    ) -> CacheResult[Value]:
        """Build one result from the snapshot's observation age."""
        return CacheResult(
            snapshot.value,
            cached=cached,
            stale=state is Freshness.STALE,
            cache_available=True,
            source_reachable=source_reachable,
            serviceable_until=serviceable_until(snapshot.created, self.policy),
        )

    def _failed_fill(self, category: CacheFailureCategory) -> CacheUnavailable | CacheInternalError:
        """Rebuild the bounded failure outcome shared by cache replicas."""
        if category is CacheFailureCategory.INTERNAL:
            return CacheInternalError()
        return CacheUnavailable(
            "The source fill is temporarily unavailable",
            cache_available=True,
            source_reachable=False,
        )

    @staticmethod
    def _failure_category(exc: Exception) -> CacheFailureCategory:
        """Reduce a source exception to one bounded durable-cache category."""
        if isinstance(exc, CacheUnavailable):
            return CacheFailureCategory.SOURCE_UNAVAILABLE
        return CacheFailureCategory.INTERNAL

    @staticmethod
    def _normalise_failure(exc: Exception) -> CacheUnavailable | CacheInternalError:
        """Give owners the same sanitized semantic result as followers."""
        if isinstance(exc, CacheUnavailable):
            return CacheUnavailable(
                "The source fill is temporarily unavailable",
                cache_available=True,
                source_reachable=False,
            )
        return CacheInternalError()

    def _finish_flight(
        self,
        key: str,
        flight: _LocalFlight[Value],
        task: asyncio.Task[CacheResult[Value]],
    ) -> None:
        """Forget one completed flight and retrieve unobserved exceptions."""
        self._detached_flights.discard(task)
        if self._flights.get(key) is flight:
            self._flights.pop(key, None)
        if not task.cancelled():
            task.exception()

    async def _await_flight(
        self,
        keys: CacheKeys,
        fill: Callable[[], Awaitable[Value]],
        scope: str,
        deadline: float,
    ) -> CacheResult[Value]:
        """Share one local cache decision while cancelling it after its last waiter."""
        flight = self._flights.get(keys.base)
        if flight is None:
            task = asyncio.create_task(self._read_or_fill(keys, fill, scope, deadline))
            flight = _LocalFlight(task)
            self._flights[keys.base] = flight
            task.add_done_callback(
                lambda completed: self._finish_flight(keys.base, flight, completed)
            )
        leader = flight.waiters == 0
        flight.waiters += 1
        try:
            result = await asyncio.shield(flight.task)
            if not leader and not result.cached:
                return replace(result, cached=True, source_reachable=None)
            return result
        except asyncio.CancelledError:
            if flight.waiters == 1 and not flight.task.done():
                if self._flights.get(keys.base) is flight:
                    self._flights.pop(keys.base, None)
                self._detached_flights.add(flight.task)
                flight.task.cancel()
            raise
        finally:
            flight.waiters -= 1
            if flight.task.done() and self._flights.get(keys.base) is flight:
                self._flights.pop(keys.base, None)

    async def _read_or_fill(
        self,
        keys: CacheKeys,
        fill: Callable[[], Awaitable[Value]],
        scope: str,
        deadline: float,
    ) -> CacheResult[Value]:
        """Read, decide, and fill one identity inside its local flight."""
        try:
            async with asyncio.timeout_at(deadline):
                current = await self._read(keys)
        except TimeoutError as exc:
            raise CacheUnavailable("Cold cache lookup timed out") from exc
        except RedisUnavailable as exc:
            fallback = self._fallback(keys, scope)
            if fallback is not None:
                return fallback
            raise CacheUnavailable(
                "Redis unavailable and no serviceable snapshot",
                cache_available=False,
            ) from exc

        now = self._clock()
        if isinstance(current, StoredNotFound):
            self._l1.evict(keys.base)
            if self.policy.terminal_is_fresh(current.created, now=now):
                self._record_lookup(result="hit", scope=scope)
                raise CacheNotFound(cache_available=True, source_reachable=None)
            current = None
        if isinstance(current, StoredFailure):
            self._l1.evict(keys.base)
            if self.policy.terminal_is_fresh(current.created, now=now):
                self._record_lookup(result="hit", scope=scope)
                raise self._failed_fill(current.category)
            current = None
        if isinstance(current, StoredSnapshot):
            state = self.policy.classify(current.created, now=now)
            if state in {Freshness.FRESH, Freshness.STALE}:
                self._remember(keys, current, state)
                self._record_lookup(
                    result="stale" if state is Freshness.STALE else "hit",
                    scope=scope,
                    age=self.policy.age_seconds(current.created, now=now),
                )
                if state is Freshness.STALE:
                    self._schedule_refresh(keys, fill, scope)
                return self._result(
                    current,
                    state=state,
                    cached=True,
                    source_reachable=None,
                )
            self._l1.evict(keys.base)
            self._record_lookup(result="miss", scope=scope)
        else:
            self._record_lookup(result="miss", scope=scope)

        return await self._cold_fill(keys, fill, scope, deadline)

    async def get_or_fill(
        self,
        identity: CacheIdentity,
        fill: Callable[[], Awaitable[Value]],
    ) -> CacheResult[Value]:
        """Return a serviceable value or perform one bounded coordinated fill."""
        if self._closed:
            raise CacheUnavailable("Redis cache coordinator is shut down")
        keys = self._keys(identity)
        scope = identity.subject_kind
        deadline = asyncio.get_running_loop().time() + self._cold_timeout
        return await self._await_flight(keys, fill, scope, deadline)

    def _schedule_refresh(
        self,
        keys: CacheKeys,
        fill: Callable[[], Awaitable[Value]],
        scope: str,
    ) -> None:
        """Start one bounded best-effort stale refresh behind the request."""
        if self._closed:
            return
        existing = self._refreshes.get(keys.base)
        if existing is not None and not existing.done():
            return
        task = asyncio.create_task(self._refresh(keys, fill, scope))
        self._refreshes[keys.base] = task
        self._background_tasks.add(task)

        def finish(completed: asyncio.Task[None]) -> None:
            """Forget one refresh and retrieve unobserved exceptions."""
            self._background_tasks.discard(completed)
            if self._refreshes.get(keys.base) is completed:
                self._refreshes.pop(keys.base, None)
            if not completed.cancelled():
                completed.exception()

        task.add_done_callback(finish)

    async def _refresh(
        self,
        keys: CacheKeys,
        fill: Callable[[], Awaitable[Value]],
        scope: str,
    ) -> None:
        """Refresh a stale value only after winning the stable lease."""
        token = uuid.uuid4().hex
        try:
            won = await self._acquire(keys, token, scope)
            if not won:
                return
            try:
                await self._run_owner(
                    keys,
                    fill,
                    scope,
                    token,
                    asyncio.get_running_loop().time() + self._cold_timeout,
                    force_refresh=True,
                )
            except asyncio.CancelledError:
                raise
            except (CacheNotFound, CacheUnavailable, RedisUnavailable):
                return
            except Exception:
                return
        except asyncio.CancelledError:
            raise
        except RedisUnavailable:
            self._available = False

    async def _acquire(self, keys: CacheKeys, token: str, scope: str) -> bool:
        """Acquire a lease and keep the compatibility health bit current."""
        try:
            won = await self._store.acquire_lease(
                keys=keys,
                token=token,
                lease_seconds=self._lease_seconds,
            )
        except RedisUnavailable:
            self._available = False
            self._record_lease(outcome="error", scope=scope)
            raise
        self._available = True
        self._record_lease(outcome="acquired" if won else "contended", scope=scope)
        return won

    async def _release(self, keys: CacheKeys, token: str) -> None:
        """Attempt owner-checked release without masking source outcomes."""
        try:
            await self._store.release_lease(keys=keys, token=token)
        except RedisUnavailable:
            self._available = False

    async def _cold_fill(
        self,
        keys: CacheKeys,
        fill: Callable[[], Awaitable[Value]],
        scope: str,
        deadline: float,
    ) -> CacheResult[Value]:
        """Poll cold followers until a winner publishes or the deadline expires."""
        while True:
            remaining = deadline - asyncio.get_running_loop().time()
            if remaining <= 0:
                raise CacheUnavailable("Cold cache fill timed out")
            token = uuid.uuid4().hex
            try:
                won = await self._acquire(keys, token, scope)
            except RedisUnavailable as exc:
                fallback = self._fallback(keys, scope)
                if fallback is not None:
                    return fallback
                raise CacheUnavailable(
                    "Redis unavailable and no serviceable snapshot",
                    cache_available=False,
                ) from exc
            if won:
                try:
                    return await self._run_owner(
                        keys,
                        fill,
                        scope,
                        token,
                        deadline,
                        force_refresh=False,
                    )
                except RedisUnavailable as exc:
                    self._available = False
                    raise CacheUnavailable(
                        "Redis unavailable during cache fill",
                        cache_available=False,
                    ) from exc

            try:
                current = await self._read(keys)
            except RedisUnavailable as exc:
                fallback = self._fallback(keys, scope)
                if fallback is not None:
                    return fallback
                raise CacheUnavailable(
                    "Redis unavailable and no serviceable snapshot",
                    cache_available=False,
                ) from exc
            now = self._clock()
            if isinstance(current, StoredNotFound):
                self._l1.evict(keys.base)
                if self.policy.terminal_is_fresh(current.created, now=now):
                    raise CacheNotFound(cache_available=True, source_reachable=None)
            elif isinstance(current, StoredFailure):
                self._l1.evict(keys.base)
                if self.policy.terminal_is_fresh(current.created, now=now):
                    raise self._failed_fill(current.category)
            elif isinstance(current, StoredSnapshot):
                state = self.policy.classify(current.created, now=now)
                if state in {Freshness.FRESH, Freshness.STALE}:
                    self._remember(keys, current, state)
                    return self._result(
                        current,
                        state=state,
                        cached=True,
                        source_reachable=None,
                    )
                self._l1.evict(keys.base)
            await asyncio.sleep(min(self._poll_min, remaining))

    async def _run_owner(
        self,
        keys: CacheKeys,
        fill: Callable[[], Awaitable[Value]],
        scope: str,
        token: str,
        deadline: float,
        *,
        force_refresh: bool,
    ) -> CacheResult[Value]:
        """Re-read after lease acquisition, then perform and publish one fill."""
        try:
            current = await self._read(keys)
            now = self._clock()
            if isinstance(current, StoredNotFound):
                self._l1.evict(keys.base)
                if self.policy.terminal_is_fresh(current.created, now=now):
                    raise CacheNotFound(cache_available=True, source_reachable=None)
            elif isinstance(current, StoredFailure):
                self._l1.evict(keys.base)
                if self.policy.terminal_is_fresh(current.created, now=now):
                    raise self._failed_fill(current.category)
            elif isinstance(current, StoredSnapshot):
                state = self.policy.classify(current.created, now=now)
                if state is Freshness.FRESH or (state is Freshness.STALE and not force_refresh):
                    self._remember(keys, current, state)
                    return self._result(
                        current,
                        state=state,
                        cached=True,
                        source_reachable=None,
                    )

            try:
                value = await self._fill_source(fill, scope, deadline)
            except CacheNotFound:
                terminal_created = self._clock()
                committed = await self._commit(
                    keys,
                    token,
                    created=terminal_created,
                    value=None,
                    ttl_seconds=self.policy.fresh_seconds,
                    not_found=True,
                )
                if committed:
                    self._l1.evict(keys.base)
                    raise CacheNotFound(
                        cache_available=True,
                        source_reachable=True,
                    )
                return await self._await_authoritative(keys, scope, deadline)
            except asyncio.CancelledError:
                raise
            except RedisUnavailable:
                raise
            except Exception as exc:
                category = self._failure_category(exc)
                if not force_refresh:
                    await self._publish_failure(
                        keys,
                        token,
                        deadline,
                        failure_category=category,
                    )
                raise self._normalise_failure(exc) from exc

            created = self._created(value)
            now = self._clock()
            ttl_seconds = self.policy.remaining_seconds(created, now=now)
            state = self.policy.classify(created, now=now)
            if ttl_seconds <= 0 or state not in {Freshness.FRESH, Freshness.STALE}:
                error = CacheUnavailable(
                    "Source returned an unserviceable observation",
                    cache_available=True,
                    source_reachable=False,
                )
                if not force_refresh:
                    await self._publish_failure(
                        keys,
                        token,
                        deadline,
                        failure_category=CacheFailureCategory.SOURCE_UNAVAILABLE,
                    )
                raise error
            committed = await self._commit(
                keys,
                token,
                created=created,
                value=value,
                ttl_seconds=ttl_seconds,
            )
            if committed:
                stored = StoredSnapshot(value, created)
                self._remember(keys, stored, state)
                return self._result(
                    stored,
                    state=state,
                    cached=False,
                    source_reachable=True,
                )
            return await self._await_authoritative(keys, scope, deadline)
        finally:
            await self._release(keys, token)

    async def _commit(
        self,
        keys: CacheKeys,
        token: str,
        *,
        created: datetime,
        value: Value | None,
        ttl_seconds: float,
        not_found: bool = False,
        failure_category: CacheFailureCategory | None = None,
    ) -> bool:
        """Commit only through the fenced durable-store seam."""
        try:
            committed = await self._store.commit(
                keys=keys,
                token=token,
                created=created,
                value=value,
                ttl_seconds=ttl_seconds,
                not_found=not_found,
                failure_category=failure_category,
            )
        except RedisUnavailable:
            self._available = False
            raise
        self._available = True
        return committed

    async def _publish_failure(
        self,
        keys: CacheKeys,
        token: str,
        deadline: float,
        *,
        failure_category: CacheFailureCategory,
    ) -> None:
        """Publish a short-lived failed-fill outcome without masking its cause."""
        remaining = deadline - asyncio.get_running_loop().time()
        if remaining <= 0:
            return
        try:
            await self._commit(
                keys,
                token,
                created=self._clock(),
                value=None,
                ttl_seconds=min(float(self.policy.fresh_seconds), remaining),
                failure_category=failure_category,
            )
        except RedisUnavailable:
            return

    async def _fill_source(
        self,
        fill: Callable[[], Awaitable[Value]],
        scope: str,
        deadline: float,
    ) -> Value:
        """Run one source query under independent source and cold-fill deadlines."""
        started = asyncio.get_running_loop().time()
        outcome = "ok"
        try:
            remaining = deadline - asyncio.get_running_loop().time()
            if remaining <= 0:
                raise CacheFillTimeout()
            async with asyncio.timeout(min(self._fill_timeout, remaining)):
                return await fill()
        except asyncio.CancelledError:
            outcome = "cancelled"
            raise
        except CacheNotFound:
            outcome = "not_found"
            raise
        except CacheFillTimeout:
            outcome = "timeout"
            raise
        except TimeoutError as exc:
            outcome = "timeout"
            raise CacheFillTimeout() from exc
        except Exception:
            outcome = "error"
            raise
        finally:
            self._record_fill(
                seconds=asyncio.get_running_loop().time() - started,
                outcome=outcome,
                scope=scope,
            )

    async def _await_authoritative(
        self,
        keys: CacheKeys,
        scope: str,
        deadline: float,
    ) -> CacheResult[Value]:
        """Await a winner after fencing without exposing a private result."""
        while True:
            remaining = deadline - asyncio.get_running_loop().time()
            if remaining <= 0:
                raise CacheUnavailable("Authoritative cache publication timed out")
            try:
                current = await self._read(keys)
            except RedisUnavailable as exc:
                raise CacheUnavailable(
                    "Redis unavailable while awaiting publication",
                    cache_available=False,
                ) from exc
            now = self._clock()
            if isinstance(current, StoredNotFound):
                self._l1.evict(keys.base)
                if self.policy.terminal_is_fresh(current.created, now=now):
                    raise CacheNotFound(cache_available=True, source_reachable=True)
            elif isinstance(current, StoredSnapshot):
                state = self.policy.classify(current.created, now=now)
                if state in {Freshness.FRESH, Freshness.STALE}:
                    self._remember(keys, current, state)
                    self._record_lookup(
                        result="stale" if state is Freshness.STALE else "hit", scope=scope
                    )
                    return self._result(
                        current,
                        state=state,
                        cached=True,
                        source_reachable=True,
                    )
            elif isinstance(current, StoredFailure):
                self._l1.evict(keys.base)
                if self.policy.terminal_is_fresh(current.created, now=now):
                    raise self._failed_fill(current.category)
            self._l1.evict(keys.base)
            await asyncio.sleep(min(self._poll_min, remaining))

    async def shutdown(self) -> None:
        """Stop refresh work and await its lease-cleanup paths."""
        self._closed = True
        tasks = tuple(
            {
                *self._background_tasks,
                *(flight.task for flight in self._flights.values()),
                *self._detached_flights,
            }
        )
        for task in tasks:
            task.cancel()
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)
        self._flights.clear()
        self._detached_flights.clear()
        self._refreshes.clear()
        self._background_tasks.clear()
