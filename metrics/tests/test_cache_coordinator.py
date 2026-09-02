"""Public coordinator contracts for freshness, leases, provenance, and fills."""

from __future__ import annotations

import asyncio
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta

import pytest

from metrics.cache import (
    CacheFailureCategory,
    CacheIdentity,
    CacheInternalError,
    CacheNotFound,
    CacheResult,
    CacheUnavailable,
    FreshnessPolicy,
    RedisCoordinator,
    RedisUnavailable,
)
from metrics.cache.redis import StoredFailure, StoredNotFound, StoredSnapshot
from metrics.telemetry import MetricsRecorder

NOW = datetime(2025, 1, 1, tzinfo=UTC)
POLICY = FreshnessPolicy(2, 10, 15)
IDENTITY = CacheIdentity("user", "bob", "cluster-a", "kueue", "v1")


@dataclass(frozen=True, slots=True)
class Snapshot:
    """Minimal source value with a signed observation time."""

    value: int
    created: datetime


class Store:
    """Small coordinator protocol fake; Redis Lua is covered by integration tests."""

    schema_revision = "2"
    source_revision = "kueue"
    query_revision = "1"

    def __init__(self) -> None:
        self.values: dict[str, StoredSnapshot[Snapshot] | StoredNotFound | StoredFailure] = {}
        self.leases: dict[str, str] = {}
        self.fail_reads = False
        self.fail_all = False
        self.read_count = 0
        self.commit_count = 0
        self.lease_acquires = 0

    def seed(self, snapshot: StoredSnapshot[Snapshot] | StoredNotFound | StoredFailure) -> None:
        """Seed the one subject used by a test."""
        self.values.clear()
        self.values["subject"] = snapshot

    async def ping(self) -> None:
        """Satisfy the durable-store seam."""
        if self.fail_all:
            raise RedisUnavailable("down")

    async def read(self, keys) -> StoredSnapshot[Snapshot] | StoredNotFound | StoredFailure | None:
        """Return the subject value or a bounded outage."""
        self.read_count += 1
        if self.fail_reads or self.fail_all:
            raise RedisUnavailable("down")
        return self.values.get(keys.base, self.values.get("subject"))

    async def acquire_lease(self, *, keys, token: str, lease_seconds: float) -> bool:
        """Model SET NX PX without reproducing the production Lua script."""
        del lease_seconds
        self.lease_acquires += 1
        if self.fail_all:
            raise RedisUnavailable("down")
        if keys.base in self.leases:
            return False
        self.leases[keys.base] = token
        return True

    async def commit(
        self,
        *,
        keys,
        token: str,
        created: datetime,
        value: Snapshot | None,
        ttl_seconds: float,
        not_found: bool = False,
        failure_category: CacheFailureCategory | None = None,
    ) -> bool:
        """Commit only for the current token and release the fake lease."""
        del ttl_seconds
        self.commit_count += 1
        if self.fail_all:
            raise RedisUnavailable("down")
        if self.leases.get(keys.base) != token:
            return False
        if not_found:
            stored = StoredNotFound(created)
        elif failure_category is not None:
            stored = StoredFailure(created, failure_category)
        else:
            stored = StoredSnapshot(value, created)
        self.values[keys.base] = stored
        self.leases.pop(keys.base, None)
        return True

    async def release_lease(self, *, keys, token: str) -> None:
        """Release only the matching fake owner."""
        if self.leases.get(keys.base) == token:
            self.leases.pop(keys.base, None)


def _coordinator(
    store: Store,
    *,
    clock=lambda: NOW,
    **kwargs,
) -> RedisCoordinator[Snapshot]:
    """Build one coordinator against the protocol fake."""
    return RedisCoordinator(
        store=store,
        key_prefix="metrics:",
        key_secret=b"cache-test-secret",
        policy=POLICY,
        created=lambda value: value.created,
        fill_timeout=kwargs.pop("fill_timeout", 1),
        cold_timeout=kwargs.pop("cold_timeout", 1),
        poll_min=kwargs.pop("poll_min", 0.001),
        clock=clock,
        **kwargs,
    )


def _stored(value: int, age: float = 0) -> StoredSnapshot[Snapshot]:
    """Create one positive stored observation."""
    return StoredSnapshot(
        Snapshot(value, NOW - timedelta(seconds=age)), NOW - timedelta(seconds=age)
    )


@pytest.mark.anyio
async def test_fresh_hit_does_not_call_source_and_has_request_provenance() -> None:
    """Fresh durable data is cached and reports that source was not probed."""
    store = Store()
    store.seed(_stored(1))
    coordinator = _coordinator(store)
    called = False

    async def fill() -> Snapshot:
        nonlocal called
        called = True
        return Snapshot(2, NOW)

    result = await coordinator.get_or_fill(IDENTITY, fill)

    assert result == CacheResult(
        Snapshot(1, NOW),
        cached=True,
        stale=False,
        cache_available=True,
        source_reachable=None,
        serviceable_until=NOW + timedelta(seconds=10),
    )
    assert not called
    await coordinator.shutdown()


@pytest.mark.anyio
async def test_stale_hit_returns_immediately_and_starts_one_leased_refresh() -> None:
    """Stale callers receive stale data while one background owner refreshes."""
    store = Store()
    store.seed(_stored(1, age=3))
    coordinator = _coordinator(store)
    started = asyncio.Event()
    release = asyncio.Event()
    fills = 0

    async def fill() -> Snapshot:
        nonlocal fills
        fills += 1
        started.set()
        await release.wait()
        return Snapshot(2, NOW)

    result = await coordinator.get_or_fill(IDENTITY, fill)
    await started.wait()
    assert result.value == Snapshot(1, NOW - timedelta(seconds=3))
    assert result.cached and result.stale
    assert result.source_reachable is None
    assert fills == 1

    release.set()
    for _ in range(20):
        await asyncio.sleep(0.01)
        if any(key != "subject" for key in store.values):
            break
    await coordinator.shutdown()
    assert any(
        value == StoredSnapshot(Snapshot(2, NOW), NOW)
        for key, value in store.values.items()
        if key != "subject"
    )


@pytest.mark.anyio
async def test_stale_burst_starts_one_local_refresh() -> None:
    """Concurrent stale reads share one local refresh task for an identity."""
    store = Store()
    store.seed(_stored(1, age=3))
    coordinator = _coordinator(store)
    started = asyncio.Event()
    release = asyncio.Event()

    async def fill() -> Snapshot:
        started.set()
        await release.wait()
        return Snapshot(2, NOW)

    results = await asyncio.gather(*(coordinator.get_or_fill(IDENTITY, fill) for _ in range(10)))

    assert all(result.stale for result in results)
    await started.wait()
    assert store.lease_acquires == 1
    release.set()
    await coordinator.shutdown()


@pytest.mark.anyio
async def test_cold_followers_share_one_fill_and_re_read_the_winner() -> None:
    """Cold followers poll Redis instead of invoking the source themselves."""
    store = Store()
    first_started = asyncio.Event()
    release = asyncio.Event()
    fills = 0
    first = _coordinator(store)
    second = _coordinator(store)

    async def fill() -> Snapshot:
        nonlocal fills
        fills += 1
        first_started.set()
        await release.wait()
        return Snapshot(7, NOW)

    requests = [
        asyncio.create_task((first if index % 2 else second).get_or_fill(IDENTITY, fill))
        for index in range(20)
    ]
    await first_started.wait()
    release.set()
    results = await asyncio.gather(*requests)

    assert fills == 1
    assert {result.value for result in results} == {Snapshot(7, NOW)}
    assert sum(not result.cached for result in results) == 1
    assert sum(result.source_reachable is True for result in results) == 1
    assert sum(result.source_reachable is None for result in results) == 19
    await first.shutdown()
    await second.shutdown()


@pytest.mark.anyio
async def test_same_key_cold_burst_reads_once_per_coordinator() -> None:
    """A local burst shares its initial durable read while replicas coordinate the fill."""

    class GatedStore(Store):
        """Hold durable reads until both coordinators have entered the burst."""

        def __init__(self) -> None:
            super().__init__()
            self.reads_released = asyncio.Event()

        async def read(self, keys):
            result = await super().read(keys)
            await self.reads_released.wait()
            return result

    store = GatedStore()
    first = _coordinator(store)
    second = _coordinator(store)
    fill_started = asyncio.Event()
    fill_release = asyncio.Event()
    fills = 0

    async def fill() -> Snapshot:
        nonlocal fills
        fills += 1
        fill_started.set()
        await fill_release.wait()
        return Snapshot(7, NOW)

    requests = [
        asyncio.create_task(first.get_or_fill(IDENTITY, fill)),
        asyncio.create_task(second.get_or_fill(IDENTITY, fill)),
    ]
    while store.read_count < 2:
        await asyncio.sleep(0)

    requests.extend(
        asyncio.create_task((first if index % 2 else second).get_or_fill(IDENTITY, fill))
        for index in range(18)
    )
    await asyncio.sleep(0.01)

    assert store.read_count == 2
    store.reads_released.set()
    await fill_started.wait()
    fill_release.set()
    results = await asyncio.gather(*requests)

    assert fills == 1
    assert {result.value for result in results} == {Snapshot(7, NOW)}
    await first.shutdown()
    await second.shutdown()


@pytest.mark.anyio
async def test_cancelled_last_waiter_detaches_before_cleanup_finishes() -> None:
    """A same-key retry cannot attach to a flight that is being cancelled."""

    class GatedReleaseStore(Store):
        """Hold the cancelled owner's release to expose the retry window."""

        def __init__(self) -> None:
            super().__init__()
            self.release_started = asyncio.Event()
            self.allow_release = asyncio.Event()
            self.retry_read_started = asyncio.Event()

        async def read(self, keys):
            result = await super().read(keys)
            if self.read_count >= 3:
                self.retry_read_started.set()
            return result

        async def release_lease(self, *, keys, token: str) -> None:
            if not self.release_started.is_set():
                self.release_started.set()
                await self.allow_release.wait()
            await super().release_lease(keys=keys, token=token)

    store = GatedReleaseStore()
    coordinator = _coordinator(store, cold_timeout=0.5)
    source_started = asyncio.Event()
    calls = 0

    async def fill() -> Snapshot:
        nonlocal calls
        calls += 1
        source_started.set()
        if calls == 1:
            await asyncio.Future()
        return Snapshot(2, NOW)

    request = asyncio.create_task(coordinator.get_or_fill(IDENTITY, fill))
    await source_started.wait()
    request.cancel()
    with pytest.raises(asyncio.CancelledError):
        await request
    await store.release_started.wait()

    retry = asyncio.create_task(coordinator.get_or_fill(IDENTITY, fill))
    retry_read_timed_out = False
    try:
        await asyncio.wait_for(store.retry_read_started.wait(), timeout=0.5)
    except TimeoutError:
        retry_read_timed_out = True
    finally:
        store.allow_release.set()

    try:
        result = await asyncio.wait_for(retry, timeout=0.5)
    except BaseException as exc:  # noqa: BLE001 - assert the public cancellation outcome below
        result = exc

    assert not retry_read_timed_out
    assert isinstance(result, CacheResult)
    assert result.value == Snapshot(2, NOW)
    assert calls == 2
    await coordinator.shutdown()


@pytest.mark.anyio
async def test_failed_cold_burst_shares_one_source_failure_outcome() -> None:
    """A failed cold fill is coordinated instead of retried by every follower."""
    store = Store()
    first = _coordinator(store, cold_timeout=0.5)
    second = _coordinator(store, cold_timeout=0.5)
    started = asyncio.Event()
    calls = 0

    async def fill() -> Snapshot:
        nonlocal calls
        calls += 1
        started.set()
        await asyncio.sleep(0.01)
        raise RuntimeError("source unavailable")

    requests = [
        asyncio.create_task((first if index % 2 else second).get_or_fill(IDENTITY, fill))
        for index in range(10)
    ]
    await started.wait()
    results = await asyncio.gather(*requests, return_exceptions=True)

    assert calls == 1
    assert all(isinstance(result, CacheInternalError) for result in results)
    assert all("source unavailable" not in str(result) for result in results)
    stored = next(value for key, value in store.values.items() if key != "subject")
    assert isinstance(stored, StoredFailure)
    assert stored.category is CacheFailureCategory.INTERNAL
    await first.shutdown()
    await second.shutdown()


@pytest.mark.anyio
async def test_expected_source_failure_has_the_same_owner_and_follower_outcome() -> None:
    """Source-unavailable failures retain one sanitized 503 category everywhere."""
    store = Store()
    owner = _coordinator(store)
    follower = _coordinator(store)

    async def fill() -> Snapshot:
        raise CacheUnavailable("private source failure")

    with pytest.raises(CacheUnavailable) as owner_error:
        await owner.get_or_fill(IDENTITY, fill)
    with pytest.raises(CacheUnavailable) as follower_error:
        await follower.get_or_fill(IDENTITY, _never_called)

    assert not isinstance(owner_error.value, CacheInternalError)
    assert not isinstance(follower_error.value, CacheInternalError)
    assert owner_error.value.args == follower_error.value.args
    assert "private source failure" not in str(owner_error.value)
    assert owner_error.value.cache_available
    assert owner_error.value.source_reachable is False
    stored = next(value for key, value in store.values.items() if key != "subject")
    assert isinstance(stored, StoredFailure)
    assert stored.category is CacheFailureCategory.SOURCE_UNAVAILABLE
    await owner.shutdown()
    await follower.shutdown()


@pytest.mark.anyio
async def test_different_identities_fill_independently() -> None:
    """Per-identity coordination does not serialize unrelated subjects."""
    store = Store()
    coordinator = _coordinator(store)
    entered = asyncio.Event()
    release = asyncio.Event()
    active = 0
    peak = 0

    async def fill(value: int) -> Snapshot:
        nonlocal active, peak
        active += 1
        peak = max(peak, active)
        if active == 2:
            entered.set()
        await release.wait()
        active -= 1
        return Snapshot(value, NOW)

    one = asyncio.create_task(
        coordinator.get_or_fill(
            CacheIdentity("user", "one", "cluster-a", "kueue"),
            lambda: fill(1),
        )
    )
    two = asyncio.create_task(
        coordinator.get_or_fill(
            CacheIdentity("user", "two", "cluster-a", "kueue"),
            lambda: fill(2),
        )
    )
    await entered.wait()
    assert peak == 2
    release.set()
    assert {result.value for result in await asyncio.gather(one, two)} == {
        Snapshot(1, NOW),
        Snapshot(2, NOW),
    }
    await coordinator.shutdown()


@pytest.mark.anyio
async def test_negative_terminal_requeries_after_the_two_minute_fresh_window() -> None:
    """A stale negative cannot suppress a newly created subject."""
    store = Store()
    store.seed(StoredNotFound(NOW - timedelta(seconds=3)))
    coordinator = _coordinator(store)
    fills = 0

    async def fill() -> Snapshot:
        nonlocal fills
        fills += 1
        return Snapshot(9, NOW)

    result = await coordinator.get_or_fill(IDENTITY, fill)

    assert result.value == Snapshot(9, NOW)
    assert result.source_reachable
    assert fills == 1
    await coordinator.shutdown()


@pytest.mark.anyio
async def test_fresh_negative_is_authoritative_404_and_does_not_call_source() -> None:
    """A fresh terminal returns a typed not-found with cache provenance."""
    store = Store()
    store.seed(StoredNotFound(NOW))
    coordinator = _coordinator(store)
    called = False

    async def fill() -> Snapshot:
        nonlocal called
        called = True
        return Snapshot(1, NOW)

    with pytest.raises(CacheNotFound) as caught:
        await coordinator.get_or_fill(IDENTITY, fill)

    assert caught.value.cache_available
    assert caught.value.source_reachable is None
    assert not called
    await coordinator.shutdown()


@pytest.mark.anyio
async def test_source_not_found_winner_reports_successful_source_probe() -> None:
    """A source terminal committed by this request carries positive provenance."""
    store = Store()
    coordinator = _coordinator(store)

    async def fill() -> Snapshot:
        raise CacheNotFound()

    with pytest.raises(CacheNotFound) as caught:
        await coordinator.get_or_fill(IDENTITY, fill)

    assert caught.value.cache_available
    assert caught.value.source_reachable is True
    await coordinator.shutdown()


@pytest.mark.anyio
async def test_terminal_observation_evicts_l1_before_a_redis_outage() -> None:
    """A cached positive cannot resurrect after an authoritative terminal."""
    store = Store()
    store.seed(_stored(1))
    coordinator = _coordinator(store)

    await coordinator.get_or_fill(IDENTITY, lambda: _never_called())
    store.seed(StoredNotFound(NOW))
    with pytest.raises(CacheNotFound):
        await coordinator.get_or_fill(IDENTITY, lambda: _never_called())

    store.fail_reads = True
    with pytest.raises(CacheUnavailable):
        await coordinator.get_or_fill(IDENTITY, lambda: _never_called())
    await coordinator.shutdown()


@pytest.mark.anyio
async def test_fenced_private_not_found_waits_for_authoritative_winner() -> None:
    """A fenced 404 waits for a later positive winner rather than leaking it."""

    class FencedStore(Store):
        """Return a fenced terminal while a winner publishes a value."""

        async def commit(self, **kwargs) -> bool:
            if kwargs["not_found"]:
                self.values["subject"] = StoredSnapshot(Snapshot(8, NOW), NOW)
                self.leases.pop(kwargs["keys"].base, None)
                return False
            return await super().commit(**kwargs)

    store = FencedStore()
    coordinator = _coordinator(store, cold_timeout=0.5)

    async def fill() -> Snapshot:
        raise CacheNotFound()

    result = await coordinator.get_or_fill(IDENTITY, fill)

    assert result.value == Snapshot(8, NOW)
    assert result.cached
    assert result.source_reachable is True
    await coordinator.shutdown()


@pytest.mark.anyio
async def test_re_read_after_lease_avoids_redundant_source_query() -> None:
    """A publication between miss and lease acquisition wins on the re-read."""

    class PublishedBetweenReads(Store):
        """Publish a value on the second read."""

        async def read(self, keys):
            result = await super().read(keys)
            if self.read_count == 2:
                self.values[keys.base] = StoredSnapshot(Snapshot(3, NOW), NOW)
                return self.values[keys.base]
            return result

    store = PublishedBetweenReads()
    coordinator = _coordinator(store)
    called = False

    async def fill() -> Snapshot:
        nonlocal called
        called = True
        return Snapshot(4, NOW)

    result = await coordinator.get_or_fill(IDENTITY, fill)

    assert result.value == Snapshot(3, NOW)
    assert result.source_reachable is None
    assert not called
    await coordinator.shutdown()


@pytest.mark.anyio
async def test_redis_l1_fallback_reports_source_not_probed() -> None:
    """A serviceable local fallback changes cache health but not source state."""
    store = Store()
    store.seed(_stored(1))
    coordinator = _coordinator(store)

    await coordinator.get_or_fill(IDENTITY, lambda: _never_called())
    store.fail_reads = True
    result = await coordinator.get_or_fill(IDENTITY, lambda: _never_called())

    assert result.value == Snapshot(1, NOW)
    assert result.stale
    assert not result.cache_available
    assert result.source_reachable is None
    await coordinator.shutdown()


@pytest.mark.anyio
async def test_stale_lookup_keeps_one_cache_decision_when_clock_advances() -> None:
    """A stale decision cannot become a fresh-looking result on a later clock read."""
    store = Store()
    store.seed(_stored(1, age=3))
    clock_values = iter(
        [
            NOW + timedelta(seconds=3),
            NOW + timedelta(seconds=3),
            NOW + timedelta(seconds=3),
            NOW + timedelta(seconds=11),
        ]
    )
    coordinator = _coordinator(store, clock=lambda: next(clock_values))

    result = await coordinator.get_or_fill(IDENTITY, lambda: _never_called())

    assert result.stale
    await coordinator.shutdown()


@pytest.mark.anyio
async def test_cancellation_releases_the_owner_lease() -> None:
    """Cancelled source work propagates cancellation and cleans its lease."""
    store = Store()
    coordinator = _coordinator(store)
    started = asyncio.Event()
    stop = asyncio.Event()

    async def fill() -> Snapshot:
        started.set()
        await stop.wait()
        return Snapshot(1, NOW)

    request = asyncio.create_task(coordinator.get_or_fill(IDENTITY, fill))
    await started.wait()
    request.cancel()
    with pytest.raises(asyncio.CancelledError):
        await request

    assert not store.leases
    await coordinator.shutdown()


@pytest.mark.anyio
async def test_redis_outage_never_invokes_source() -> None:
    """A cold Redis outage fails closed instead of bypassing the cache."""
    store = Store()
    store.fail_all = True
    coordinator = _coordinator(store)
    called = False

    async def fill() -> Snapshot:
        nonlocal called
        called = True
        return Snapshot(1, NOW)

    with pytest.raises(CacheUnavailable):
        await coordinator.get_or_fill(IDENTITY, fill)
    assert not called
    await coordinator.shutdown()


@pytest.mark.anyio
async def test_telemetry_failure_does_not_change_coordinator_result() -> None:
    """Cache operations survive recorder exceptions."""

    class BrokenTelemetry(MetricsRecorder):
        """Raise from every cache observation."""

        def record_cache_lookup(self, **_details: object) -> None:
            """Raise a synthetic lookup error."""
            raise RuntimeError("telemetry failed")

        def record_lease(self, **_details: object) -> None:
            """Raise a synthetic lease error."""
            raise RuntimeError("telemetry failed")

        def record_fill_duration(self, **_details: object) -> None:
            """Raise a synthetic fill error."""
            raise RuntimeError("telemetry failed")

    store = Store()
    coordinator = RedisCoordinator(
        store=store,
        key_prefix="metrics:",
        key_secret=b"cache-test-secret",
        policy=POLICY,
        created=lambda value: value.created,
        telemetry=BrokenTelemetry(),
        clock=lambda: NOW,
    )

    result = await coordinator.get_or_fill(IDENTITY, lambda: _snapshot(1))

    assert result.value == Snapshot(1, NOW)
    await coordinator.shutdown()


async def _snapshot(value: int) -> Snapshot:
    """Return one immediate source value."""
    return Snapshot(value, NOW)


async def _never_called() -> Snapshot:
    """Fail if a test accidentally bypasses the cache contract."""
    raise AssertionError("source should not run")
