"""Coordinate Redis cache-aside reads, distributed fills, and safe fallbacks.

The coordinator uses Redis leases to avoid replica stampedes, local single
flight to coalesce same-process misses, and a bounded L1 only during outages.
"""

from __future__ import annotations

import asyncio
import random
import time
import uuid
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from datetime import datetime
from typing import Generic, TypeVar

from metrics.cache.memory import MemorySnapshots
from metrics.cache.models import (
    CacheIdentity,
    CacheResult,
    CacheUnavailable,
    Freshness,
    FreshnessPolicy,
    cache_keys,
)
from metrics.cache.redis import RedisSnapshots, RedisUnavailable, StoredSnapshot
from metrics.telemetry import MetricsRecorder, NoopMetricsRecorder

Value = TypeVar("Value")


@dataclass(slots=True)
class _LocalFill(Generic[Value]):
    """Share one cold-fill result or failure among process-local waiters."""

    event: asyncio.Event
    result: CacheResult[Value] | None = None
    error: BaseException | None = None


class RedisCoordinator(Generic[Value]):
    """Provide distributed single flight with bounded process-local fallback.

    Fresh hits return immediately, stale hits attempt one leased refresh, and
    cold misses wait for a lease owner to publish. Redis outages use L1 only
    while its snapshot remains serviceable.
    """

    backend_name = "redis"

    def __init__(
        self,
        *,
        store: RedisSnapshots[Value],
        key_prefix: str,
        key_secret: bytes,
        policy: FreshnessPolicy,
        created: Callable[[Value], datetime],
        fill_timeout: float = 10.0,
        cold_timeout: float = 12.0,
        poll_min: float = 0.05,
        poll_max: float = 0.15,
        max_l1_entries: int = 128,
        max_fills: int = 8,
        telemetry: MetricsRecorder | None = None,
    ) -> None:
        """Configure freshness, finite deadlines, polling, and local bounds.

        Args:
            store: Redis snapshot repository.
            key_prefix: Namespace prepended to opaque Redis keys.
            key_secret: HMAC key used to obscure cache identities.
            policy: Freshness and retention boundaries.
            created: Callable extracting source collection time from a value.
            fill_timeout: Maximum time allowed for one provider fill.
            cold_timeout: Maximum total wait for a cold result.
            poll_min: Minimum delay between durable pointer checks.
            poll_max: Maximum delay between durable pointer checks.
            max_l1_entries: Maximum last-known snapshots retained locally.
            max_fills: Maximum concurrent provider fills in this process.
            telemetry: Optional bounded cache telemetry recorder.
        """
        self.policy = policy
        self._store = store
        self._key_prefix = key_prefix
        self._key_secret = key_secret
        self._created = created
        self._fill_timeout = fill_timeout
        self._cold_timeout = cold_timeout
        self._poll_min = poll_min
        self._poll_max = poll_max
        self._l1 = MemorySnapshots[StoredSnapshot[Value]](max_l1_entries)
        self._fills: dict[str, _LocalFill[Value]] = {}
        self._fill_tasks: set[asyncio.Task[None]] = set()
        self._lock = asyncio.Lock()
        self._capacity = asyncio.Semaphore(max(1, max_fills))
        self._available = True
        self._telemetry = telemetry or NoopMetricsRecorder()

    @property
    def available(self) -> bool:
        """Whether the most recent Redis operation succeeded."""
        return self._available

    def _keys(self, identity: CacheIdentity):
        """Derive revisioned Redis keys for one source identity."""
        return cache_keys(
            prefix=self._key_prefix,
            identity=identity,
            secret=self._key_secret,
            schema_revision=self._store.schema_revision,
            source_revision=self._store.source_revision,
            query_revision=self._store.query_revision,
        )

    async def ping(self) -> None:
        """Verify Redis before the runtime begins serving.

        Raises:
            RedisUnavailable: If the bounded health check fails.
        """
        try:
            await self._store.ping()
            self._available = True
        except RedisUnavailable:
            self._available = False
            raise

    def _remember(self, base: str, snapshot: StoredSnapshot[Value]) -> None:
        """Retain a serviceable durable snapshot as an outage fallback."""
        state = self.policy.classify(snapshot.created)
        if state in {Freshness.FRESH, Freshness.STALE}:
            self._l1.put(base, snapshot)

    def _fallback(self, base: str) -> CacheResult[Value] | None:
        """Return a serviceable L1 snapshot when one exists."""
        snapshot = self._l1.get(base)
        if snapshot is None:
            return None
        state = self.policy.classify(snapshot.created)
        if state not in {Freshness.FRESH, Freshness.STALE}:
            return None
        return CacheResult(snapshot.value, cached=True, stale=state is Freshness.STALE)

    async def _read(self, keys) -> StoredSnapshot[Value] | None:
        """Read Redis while updating the coordinator availability flag."""
        try:
            snapshot = await self._store.read(keys)
            self._available = True
            return snapshot
        except RedisUnavailable:
            self._available = False
            raise

    async def get_or_fill(
        self,
        identity: CacheIdentity,
        fill: Callable[[], Awaitable[Value]],
    ) -> CacheResult[Value]:
        """Read a snapshot or coordinate exactly one bounded downstream fill.

        Args:
            identity: Complete source stream identity.
            fill: Async callable producing a fresh value.

        Returns:
            A serviceable value with cache provenance.

        Raises:
            CacheUnavailable: If no durable or local result can be served.
        """
        with self._telemetry.span(
            "cache.get_or_fill",
            {
                "cache.backend": self.backend_name,
                "metrics.scope": identity.subject_kind,
            },
        ):
            return await self._get_or_fill(identity, fill)

    async def _get_or_fill(
        self,
        identity: CacheIdentity,
        fill: Callable[[], Awaitable[Value]],
    ) -> CacheResult[Value]:
        """Apply fresh, stale-refresh, cold-fill, and outage fallback policy.

        Args:
            identity: Complete source stream identity.
            fill: Async callable producing a fresh value.
        """
        keys = self._keys(identity)
        scope = identity.subject_kind
        try:
            snapshot = await self._read(keys)
        except RedisUnavailable as exc:
            fallback = self._fallback(keys.base)
            if fallback is not None:
                return fallback
            raise CacheUnavailable("Redis unavailable and no serviceable snapshot") from exc

        if snapshot is not None:
            self._remember(keys.base, snapshot)
            state = self.policy.classify(snapshot.created)
            if state is Freshness.FRESH:
                return CacheResult(snapshot.value, cached=True, stale=False)
            if state is Freshness.STALE:
                return await self._refresh_stale(keys, snapshot, fill, scope)

        return await self._join_cold(keys, fill, scope)

    async def _refresh_stale(
        self,
        keys,
        stale: StoredSnapshot[Value],
        fill: Callable[[], Awaitable[Value]],
        scope: str,
    ) -> CacheResult[Value]:
        """Refresh under a lease or immediately serve the stale snapshot.

        Args:
            keys: Derived keys for the source identity.
            stale: Serviceable stale snapshot.
            fill: Async callable producing a fresh value.
            scope: Bounded subject scope used for telemetry.
        """
        bucket = int(time.time() // self.policy.fresh_seconds)
        token = uuid.uuid4().hex
        try:
            won = await self._store.acquire_lease(
                keys=keys,
                bucket=bucket,
                token=token,
                lease_seconds=self._fill_timeout + 1,
            )
            self._available = True
        except RedisUnavailable:
            self._available = False
            return CacheResult(stale.value, cached=True, stale=True)
        if not won:
            self._telemetry.record_lease(outcome="contended", scope=scope)
            return CacheResult(stale.value, cached=True, stale=True)
        self._telemetry.record_lease(outcome="acquired", scope=scope)
        try:
            return await self._fill_under_lease(keys, fill, scope, bucket, token)
        except RedisUnavailable:
            self._available = False
            return CacheResult(stale.value, cached=True, stale=True)
        except Exception:
            return CacheResult(stale.value, cached=True, stale=True)

    async def _join_cold(
        self,
        keys,
        fill: Callable[[], Awaitable[Value]],
        scope: str,
    ) -> CacheResult[Value]:
        """Join or launch the process-local task serving a cold identity.

        Args:
            keys: Derived keys for the source identity.
            fill: Async callable producing a fresh value.
            scope: Bounded subject scope used for telemetry.
        """
        async with self._lock:
            local = self._fills.get(keys.base)
            if local is None:
                local = _LocalFill(event=asyncio.Event())
                self._fills[keys.base] = local
                task = asyncio.create_task(self._run_cold(keys, fill, local, scope))
                self._fill_tasks.add(task)
                task.add_done_callback(self._fill_tasks.discard)
        try:
            async with asyncio.timeout(self._cold_timeout):
                await local.event.wait()
        except TimeoutError as exc:
            raise CacheUnavailable("Cold cache fill timed out") from exc
        if local.error is not None:
            raise local.error
        if local.result is None:
            raise CacheUnavailable("Cold cache fill produced no result")
        return local.result

    async def _run_cold(
        self,
        keys,
        fill: Callable[[], Awaitable[Value]],
        local: _LocalFill[Value],
        scope: str,
    ) -> None:
        """Run one cold fill and publish its result to local waiters.

        Args:
            keys: Derived keys for the source identity.
            fill: Async callable producing a fresh value.
            local: Process-local result shared by waiters.
            scope: Bounded subject scope used for telemetry.
        """
        try:
            async with asyncio.timeout(self._cold_timeout):
                local.result = await self._fill_or_poll(keys, fill, scope)
        except BaseException as exc:
            local.error = exc
        finally:
            local.event.set()
            async with self._lock:
                if self._fills.get(keys.base) is local:
                    del self._fills[keys.base]

    async def _fill_or_poll(
        self,
        keys,
        fill: Callable[[], Awaitable[Value]],
        scope: str,
    ) -> CacheResult[Value]:
        """Acquire the distributed lease or poll for its owner's publication.

        Args:
            keys: Derived keys for the source identity.
            fill: Async callable producing a fresh value.
            scope: Bounded subject scope used for telemetry.
        """
        bucket = int(time.time() // self.policy.fresh_seconds)
        token = uuid.uuid4().hex
        try:
            baseline = await self._store.pointer(keys)
            won = await self._store.acquire_lease(
                keys=keys,
                bucket=bucket,
                token=token,
                lease_seconds=self._fill_timeout + 1,
            )
            self._available = True
        except RedisUnavailable as exc:
            self._available = False
            fallback = self._fallback(keys.base)
            if fallback is not None:
                return fallback
            raise CacheUnavailable("Redis unavailable during cold fill") from exc

        if not won:
            self._telemetry.record_lease(outcome="contended", scope=scope)
            return await self._poll(keys, baseline)
        self._telemetry.record_lease(outcome="acquired", scope=scope)
        try:
            return await self._fill_under_lease(keys, fill, scope, bucket, token)
        except RedisUnavailable as exc:
            self._available = False
            fallback = self._fallback(keys.base)
            if fallback is not None:
                return fallback
            raise CacheUnavailable("Redis unavailable while publishing snapshot") from exc

    async def _fill_under_lease(
        self,
        keys,
        fill: Callable[[], Awaitable[Value]],
        scope: str,
        bucket: int,
        token: str,
    ) -> CacheResult[Value]:
        """Publish one bounded fill while holding a distributed lease.

        Args:
            keys: Derived keys for the source identity.
            fill: Async callable producing a fresh value.
            scope: Bounded subject scope used for telemetry.
            bucket: Freshness bucket whose lease is held.
            token: Unique lease-owner token.

        Returns:
            Newly published value marked as a cache miss.
        """
        started = time.perf_counter()
        outcome = "ok"
        try:
            async with self._capacity, asyncio.timeout(self._fill_timeout):
                value = await fill()
            created = self._created(value)
            snapshot_id = uuid.uuid4().hex
            await self._store.publish(
                keys=keys,
                snapshot_id=snapshot_id,
                created=created,
                value=value,
            )
            self._available = True
            self._remember(keys.base, StoredSnapshot(snapshot_id, value, created))
            return CacheResult(value, cached=False, stale=False)
        except BaseException:
            outcome = "error"
            raise
        finally:
            self._telemetry.record_fill_duration(
                seconds=time.perf_counter() - started,
                outcome=outcome,
                scope=scope,
            )
            try:
                await self._store.release_lease(keys=keys, bucket=bucket, token=token)
            except RedisUnavailable:
                self._available = False

    async def _poll(self, keys, baseline: str | None) -> CacheResult[Value]:
        """Poll until another replica publishes a serviceable snapshot.

        Args:
            keys: Derived keys for the source identity.
            baseline: Snapshot ID present before lease contention.

        Returns:
            The newly published serviceable snapshot.
        """
        while True:
            await asyncio.sleep(random.uniform(self._poll_min, self._poll_max))
            try:
                snapshot = await self._read(keys)
            except RedisUnavailable as exc:
                fallback = self._fallback(keys.base)
                if fallback is not None:
                    return fallback
                raise CacheUnavailable("Redis unavailable while awaiting fill") from exc
            if snapshot is None or snapshot.snapshot_id == baseline:
                continue
            state = self.policy.classify(snapshot.created)
            if state in {Freshness.FRESH, Freshness.STALE}:
                self._remember(keys.base, snapshot)
                return CacheResult(snapshot.value, cached=True, stale=state is Freshness.STALE)

    async def shutdown(self) -> None:
        """Cancel and await process-local fills so none outlive the runtime."""
        tasks = tuple(self._fill_tasks)
        for task in tasks:
            task.cancel()
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)
