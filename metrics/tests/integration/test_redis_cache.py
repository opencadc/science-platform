"""Real-Redis proofs for the two-key cache contract."""

from __future__ import annotations

import asyncio
import os
import subprocess
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta

import pytest
from redis.asyncio import Redis

from metrics.cache import (
    CacheIdentity,
    CacheNotFound,
    CacheUnavailable,
    FreshnessPolicy,
    RedisCoordinator,
    RedisSnapshots,
    cache_keys,
)

pytestmark = [pytest.mark.anyio, pytest.mark.integration]

SECRET = b"integration-cache-secret-is-32-bytes"
IDENTITY = CacheIdentity("platform", "", "integration", "stub", "v1")


@dataclass(frozen=True, slots=True)
class Snapshot:
    """Typed real-Redis test value."""

    value: int
    created: datetime


def _redis_url() -> str:
    """Read the opt-in integration Redis endpoint."""
    url = os.environ.get("METRICS_TEST_REDIS_URL")
    if not url:
        pytest.skip("METRICS_TEST_REDIS_URL is not configured")
    return url


def _store(redis: Redis) -> RedisSnapshots[Snapshot]:
    """Build the real Redis adapter."""
    return RedisSnapshots(
        redis=redis,
        value_type=Snapshot,
        secret=SECRET,
        command_timeout=0.5,
        schema_revision="2",
        source_revision="1",
        query_revision="0",
    )


def _keys(identity: CacheIdentity = IDENTITY):
    """Derive the stable two-key identity used by the integration."""
    return cache_keys(
        prefix="integration:",
        identity=identity,
        secret=SECRET,
        schema_revision="2",
        source_revision="1",
        query_revision="0",
    )


def _coordinator(
    redis: Redis,
    *,
    policy: FreshnessPolicy | None = None,
    clock=None,
) -> RedisCoordinator[Snapshot]:
    """Build one coordinator against the shared Redis instance."""
    return RedisCoordinator(
        store=_store(redis),
        key_prefix="integration:",
        key_secret=SECRET,
        policy=policy or FreshnessPolicy(1, 5, 10),
        created=lambda snapshot: snapshot.created,
        fill_timeout=2,
        cold_timeout=3,
        poll_min=0.005,
        clock=clock,
    )


@pytest.fixture
async def redis_clients():
    """Provide two prewarmed clients over one isolated Redis database."""
    first = Redis.from_url(_redis_url())
    second = Redis.from_url(_redis_url())
    await asyncio.gather(first.ping(), second.ping())
    await first.flushdb()
    try:
        yield first, second
    finally:
        await first.flushdb()
        await first.aclose()
        await second.aclose()


async def test_two_coordinators_and_100_requests_issue_one_fill(redis_clients) -> None:
    """The stable lease coordinates a cross-process-sized cold burst."""
    first_redis, second_redis = redis_clients
    first = _coordinator(first_redis)
    second = _coordinator(second_redis)
    fills = 0

    async def fill() -> Snapshot:
        nonlocal fills
        fills += 1
        await asyncio.sleep(0.05)
        return Snapshot(42, datetime.now(UTC))

    requests = [
        asyncio.create_task((first if index % 2 else second).get_or_fill(IDENTITY, fill))
        for index in range(100)
    ]
    results = await asyncio.gather(*requests)

    assert fills == 1
    assert {result.value.value for result in results} == {42}
    await first.shutdown()
    await second.shutdown()


async def test_stale_requests_return_stale_and_start_one_refresh(redis_clients) -> None:
    """A stale burst returns immediately while one Redis lease refreshes."""
    first_redis, second_redis = redis_clients
    policy = FreshnessPolicy(1, 5, 10)
    first = _coordinator(first_redis, policy=policy)
    second = _coordinator(second_redis, policy=policy)
    fills = 0
    refresh_started = asyncio.Event()

    async def fill() -> Snapshot:
        nonlocal fills
        fills += 1
        if fills == 2:
            refresh_started.set()
        created = datetime.now(UTC)
        if fills == 1:
            created -= timedelta(seconds=2)
        await asyncio.sleep(0.05)
        return Snapshot(fills, created)

    initial = await first.get_or_fill(IDENTITY, fill)
    assert initial.stale
    results = await asyncio.gather(
        *(coordinator.get_or_fill(IDENTITY, fill) for coordinator in [first, second] * 10)
    )
    assert all(result.stale for result in results)
    await asyncio.wait_for(refresh_started.wait(), timeout=1)
    for _ in range(100):
        if fills == 2:
            break
        await asyncio.sleep(0.01)
    assert fills == 2
    await first.shutdown()
    await second.shutdown()


async def test_lease_key_is_stable_across_wall_clock_boundary(redis_clients) -> None:
    """The same subject has one lease key before and after time advances."""
    redis, _ = redis_clients
    store = _store(redis)
    keys = _keys()
    assert keys.lease == _keys().lease
    assert await store.acquire_lease(keys=keys, token="old", lease_seconds=0.05)
    await asyncio.sleep(0.08)
    assert await store.acquire_lease(keys=keys, token="new", lease_seconds=1)
    await store.release_lease(keys=keys, token="new")


async def test_expired_owner_cannot_release_new_owner(redis_clients) -> None:
    """Owner-checked release cannot delete a later lease owner."""
    redis, _ = redis_clients
    store = _store(redis)
    keys = _keys()
    assert await store.acquire_lease(keys=keys, token="old", lease_seconds=0.05)
    await asyncio.sleep(0.08)
    assert await store.acquire_lease(keys=keys, token="new", lease_seconds=1)
    await store.release_lease(keys=keys, token="old")
    assert not await store.acquire_lease(keys=keys, token="third", lease_seconds=1)
    await store.release_lease(keys=keys, token="new")


async def test_fenced_commit_cannot_publish_private_value(redis_clients) -> None:
    """A token that lost its lease cannot overwrite the authoritative value."""
    redis, _ = redis_clients
    store = _store(redis)
    keys = _keys()
    assert await store.acquire_lease(keys=keys, token="old", lease_seconds=0.05)
    await asyncio.sleep(0.08)
    assert await store.acquire_lease(keys=keys, token="new", lease_seconds=1)

    assert not await store.commit(
        keys=keys,
        token="old",
        created=datetime.now(UTC),
        value=Snapshot(1, datetime.now(UTC)),
        ttl_seconds=5,
    )
    assert await store.commit(
        keys=keys,
        token="new",
        created=datetime.now(UTC),
        value=Snapshot(2, datetime.now(UTC)),
        ttl_seconds=5,
    )
    assert (await store.read(keys)).value.value == 2  # type: ignore[union-attr]


async def test_terminal_revalidates_after_two_minutes_not_retention(redis_clients) -> None:
    """A negative terminal expires on the fresh window and is requeried."""
    redis, _ = redis_clients
    policy = FreshnessPolicy(1, 3, 5)
    coordinator = _coordinator(redis, policy=policy)
    fills = 0

    async def fill() -> Snapshot:
        nonlocal fills
        fills += 1
        if fills == 1:
            raise CacheNotFound()
        return Snapshot(4, datetime.now(UTC))

    with pytest.raises(CacheNotFound):
        await coordinator.get_or_fill(IDENTITY, fill)
    with pytest.raises(CacheNotFound):
        await coordinator.get_or_fill(IDENTITY, fill)
    await asyncio.sleep(1.1)
    result = await coordinator.get_or_fill(IDENTITY, fill)

    assert result.value.value == 4
    assert fills == 2
    await coordinator.shutdown()


async def test_remaining_retention_ttl_is_based_on_created_time(redis_clients) -> None:
    """An old positive publication never receives a fresh full-retention TTL."""
    redis, _ = redis_clients
    policy = FreshnessPolicy(1, 5, 10)
    coordinator = _coordinator(redis, policy=policy)
    created = datetime.now(UTC) - timedelta(seconds=3)

    result = await coordinator.get_or_fill(
        IDENTITY,
        lambda: _snapshot(5, created),
    )
    keys = _keys()
    ttl_ms = await redis.pttl(keys.value)

    assert result.value.value == 5
    assert 0 < ttl_ms <= 7500
    await coordinator.shutdown()


async def test_identity_binding_rejects_copied_alice_payload(redis_clients) -> None:
    """A valid signed Alice envelope is not valid under Bob's value key."""
    redis, _ = redis_clients
    store = _store(redis)
    alice = _keys(CacheIdentity("user", "alice", "integration", "stub", "v1"))
    bob = _keys(CacheIdentity("user", "bob", "integration", "stub", "v1"))
    assert await store.acquire_lease(keys=alice, token="alice", lease_seconds=1)
    assert await store.commit(
        keys=alice,
        token="alice",
        created=datetime.now(UTC),
        value=Snapshot(11, datetime.now(UTC)),
        ttl_seconds=5,
    )
    payload = await redis.get(alice.value)
    await redis.set(bob.value, payload, px=5000)

    assert await store.read(bob) is None


async def test_cancellation_releases_real_redis_lease(redis_clients) -> None:
    """Cancellation during source work leaves no owner lease behind."""
    redis, _ = redis_clients
    coordinator = _coordinator(redis)
    started = asyncio.Event()
    release = asyncio.Event()

    async def fill() -> Snapshot:
        started.set()
        await release.wait()
        return Snapshot(1, datetime.now(UTC))

    request = asyncio.create_task(coordinator.get_or_fill(IDENTITY, fill))
    await started.wait()
    request.cancel()
    with pytest.raises(asyncio.CancelledError):
        await request

    assert await redis.get(_keys().lease) is None
    await coordinator.shutdown()


async def test_l1_terminal_invalidation_survives_redis_outage(redis_clients) -> None:
    """An observed terminal evicts L1 before an outage can resurrect it."""
    container = os.environ.get("METRICS_TEST_REDIS_CONTAINER")
    if not container:
        pytest.skip("METRICS_TEST_REDIS_CONTAINER is not configured")
    redis, _ = redis_clients
    policy = FreshnessPolicy(1, 3, 5)
    coordinator = _coordinator(redis, policy=policy)

    await coordinator.get_or_fill(IDENTITY, lambda: _snapshot(1, datetime.now(UTC)))
    store = _store(redis)
    keys = _keys()
    assert await store.acquire_lease(keys=keys, token="terminal", lease_seconds=1)
    assert await store.commit(
        keys=keys,
        token="terminal",
        created=datetime.now(UTC),
        value=None,
        ttl_seconds=1,
        not_found=True,
    )
    with pytest.raises(CacheNotFound):
        await coordinator.get_or_fill(IDENTITY, lambda: _never_called())

    await asyncio.to_thread(subprocess.run, ["docker", "pause", container], check=True)
    try:
        with pytest.raises(CacheUnavailable):
            await coordinator.get_or_fill(IDENTITY, lambda: _never_called())
    finally:
        await asyncio.to_thread(subprocess.run, ["docker", "unpause", container], check=True)
    await coordinator.shutdown()


async def _snapshot(value: int, created: datetime) -> Snapshot:
    """Return one immediate source value."""
    return Snapshot(value, created)


async def _never_called() -> Snapshot:
    """Fail if the cache bypasses Redis during an outage."""
    raise AssertionError("source should not run")
