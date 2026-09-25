"""Real-Redis proofs for the two-stage, two-key cache contract."""

from __future__ import annotations

import asyncio
import os
from dataclasses import dataclass

import pytest
from redis.asyncio import Redis

from metrics.cache import (
    CacheFailureCategory,
    CacheIdentity,
    CacheInternalError,
    CacheNotFound,
    CacheUnavailable,
    FreshnessPolicy,
    RedisCoordinator,
    RedisSnapshots,
    StoredSnapshot,
    cache_keys,
)

pytestmark = [pytest.mark.anyio, pytest.mark.integration]

SECRET = b"integration-cache-secret-is-32-bytes"
IDENTITY = CacheIdentity("platform", "canfar", "integration", "stub", "v1")
POLICY = FreshnessPolicy(0.5, 1.5)


@dataclass(frozen=True, slots=True)
class Snapshot:
    """Typed real-Redis test value."""

    value: int


class Source:
    """Count source calls and the maximum number running at once."""

    def __init__(self, delay: float = 0.05) -> None:
        self.calls = 0
        self.running = 0
        self.max_running = 0
        self.delay = delay
        self.fail: BaseException | None = None

    async def __call__(self) -> Snapshot:
        self.calls += 1
        self.running += 1
        self.max_running = max(self.max_running, self.running)
        try:
            await asyncio.sleep(self.delay)
            if self.fail is not None:
                raise self.fail
            return Snapshot(self.calls)
        finally:
            self.running -= 1


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
        schema_revision="9",
        source_revision="1",
        query_revision="0",
    )


def _keys(identity: CacheIdentity = IDENTITY):
    """Derive the stable two-key identity used by the integration."""
    return cache_keys(
        prefix="integration:",
        identity=identity,
        secret=SECRET,
        schema_revision="9",
        source_revision="1",
        query_revision="0",
    )


def _coordinator(redis: Redis, **kwargs) -> RedisCoordinator[Snapshot]:
    """Build one coordinator (one replica) against the shared Redis instance."""
    return RedisCoordinator(
        store=_store(redis),
        key_prefix="integration:",
        key_secret=SECRET,
        policy=kwargs.pop("policy", POLICY),
        fill_timeout=kwargs.pop("fill_timeout", 0.5),
        cold_timeout=kwargs.pop("cold_timeout", 2.0),
        lease_margin=0.2,
        failure_cooldown=kwargs.pop("failure_cooldown", 0.3),
        poll_min=0.005,
        poll_max=0.02,
        **kwargs,
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


async def test_two_replicas_and_100_requests_issue_one_fill(redis_clients) -> None:
    """The lease coordinates a cross-process-sized cold burst."""
    first, second = (_coordinator(client) for client in redis_clients)
    source = Source(delay=0.1)
    results = await asyncio.gather(
        *((first if index % 2 else second).get_or_fill(IDENTITY, source) for index in range(100))
    )
    assert source.calls == 1 and {result.value for result in results} == {Snapshot(1)}
    ttl = await redis_clients[0].pttl(_keys().value)
    assert 1_300 < ttl <= 1_500  # the snapshot lives exactly for the stale window
    assert await redis_clients[0].exists(_keys().lease) == 0


async def test_stale_requests_return_stale_and_start_one_refresh(redis_clients) -> None:
    """Stale readers never wait; one replica refreshes."""
    first, second = (_coordinator(client) for client in redis_clients)
    source = Source(delay=0.01)
    await first.get_or_fill(IDENTITY, source)
    await asyncio.sleep(0.55)
    source.delay = 0.2
    started = asyncio.get_running_loop().time()
    stale = await asyncio.gather(
        *((first if index % 2 else second).get_or_fill(IDENTITY, source) for index in range(60))
    )
    assert asyncio.get_running_loop().time() - started < 0.15
    assert all(result.stale and result.value == Snapshot(1) for result in stale)
    await asyncio.sleep(0.3)
    refreshed = await second.get_or_fill(IDENTITY, source)
    assert not refreshed.stale and refreshed.value == Snapshot(2)
    assert source.calls == 2 and source.max_running == 1


async def test_value_is_gone_at_the_end_of_the_stale_window(redis_clients) -> None:
    """Nothing is retained past the stale window."""
    coordinator = _coordinator(redis_clients[0])
    await coordinator.get_or_fill(IDENTITY, Source(delay=0.01))
    await asyncio.sleep(1.55)
    assert await redis_clients[0].exists(_keys().value) == 0


async def test_failed_refresh_turns_the_lease_into_a_bounded_cooldown(redis_clients) -> None:
    """A failed refresh blocks retries on every replica until the cooldown ends."""
    first, second = (_coordinator(client) for client in redis_clients)
    source = Source(delay=0.01)
    await first.get_or_fill(IDENTITY, source)
    await asyncio.sleep(0.55)
    source.fail = RuntimeError("source down")
    await first.get_or_fill(IDENTITY, source)
    await asyncio.sleep(0.05)
    assert await redis_clients[0].get(_keys().lease) == b"!internal"
    for _ in range(5):
        assert (await second.get_or_fill(IDENTITY, source)).stale
    assert source.calls == 2
    await asyncio.sleep(0.3)
    source.fail = None
    await second.get_or_fill(IDENTITY, source)
    await asyncio.sleep(0.05)
    assert source.calls == 3


async def test_cold_failure_is_shared_through_the_cooldown(redis_clients) -> None:
    """Cold followers on another replica fail fast with the same sanitized outcome."""
    first, second = (_coordinator(client) for client in redis_clients)
    source = Source(delay=0.01)
    source.fail = ValueError("private detail")
    with pytest.raises(CacheInternalError):
        await first.get_or_fill(IDENTITY, source)
    with pytest.raises(CacheInternalError):
        await second.get_or_fill(IDENTITY, source)
    assert source.calls == 1


async def test_not_found_is_shared_for_the_fresh_window(redis_clients) -> None:
    """A not-found tombstone lives for the fresh window only."""
    first, second = (_coordinator(client) for client in redis_clients)
    calls = 0

    async def missing() -> Snapshot:
        nonlocal calls
        calls += 1
        raise CacheNotFound()

    with pytest.raises(CacheNotFound):
        await first.get_or_fill(IDENTITY, missing)
    assert 400 < await redis_clients[0].pttl(_keys().value) <= 500
    with pytest.raises(CacheNotFound):
        await second.get_or_fill(IDENTITY, missing)
    assert calls == 1


async def test_expired_owner_cannot_publish_over_or_release_its_successor(redis_clients) -> None:
    """Every owner exit is fenced on the lease token in Lua."""
    store = _store(redis_clients[0])
    keys = _keys()
    first = await store.observe(keys, token="a" * 32, fresh_floor_ms=500, lease_ms=50, claim=True)
    assert first.claimed
    await asyncio.sleep(0.08)
    second = await store.observe(
        keys, token="b" * 32, fresh_floor_ms=500, lease_ms=5_000, claim=True
    )
    assert second.claimed
    assert not await store.publish(
        keys, token="a" * 32, stored=StoredSnapshot(Snapshot(1)), ttl_ms=1_000
    )
    assert not await store.cool_down(
        keys, token="a" * 32, category=CacheFailureCategory.INTERNAL, cooldown_ms=1_000
    )
    assert not await store.release(keys, token="a" * 32)
    assert await redis_clients[0].get(keys.lease) == b"b" * 32
    assert await store.publish(
        keys, token="b" * 32, stored=StoredSnapshot(Snapshot(2)), ttl_ms=1_000
    )


async def test_identity_binding_rejects_a_copied_payload(redis_clients) -> None:
    """A payload copied under another subject key is unreadable and refilled."""
    store = _store(redis_clients[0])
    alice = _keys(CacheIdentity("user", "alice", "integration", "stub", "v1"))
    bob = _keys(CacheIdentity("user", "bob", "integration", "stub", "v1"))
    await redis_clients[0].set(
        bob.value, store.encode(alice, StoredSnapshot(Snapshot(1))), px=1_000
    )
    seen = await store.observe(bob, token="a" * 32, fresh_floor_ms=500, lease_ms=1_000, claim=True)
    assert seen.stored is None and seen.unreadable


async def test_scripts_reload_after_script_flush(redis_clients) -> None:
    """EVALSHA falls back to EVAL after Redis loses its script cache."""
    store = _store(redis_clients[0])
    await store.observe(_keys(), token="a" * 32, fresh_floor_ms=1, lease_ms=10, claim=False)
    await redis_clients[0].script_flush()
    seen = await store.observe(_keys(), token="a" * 32, fresh_floor_ms=1, lease_ms=10, claim=True)
    assert seen.claimed


async def test_shutdown_releases_the_real_lease(redis_clients) -> None:
    """A cancelled owner releases its lease through the fenced script."""
    coordinator = _coordinator(redis_clients[0])
    waiter = asyncio.create_task(coordinator.get_or_fill(IDENTITY, Source(delay=5)))
    await asyncio.sleep(0.05)
    assert await redis_clients[0].exists(_keys().lease) == 1
    await coordinator.shutdown()
    with pytest.raises(CacheUnavailable):
        await asyncio.wait_for(waiter, timeout=5)
    assert await redis_clients[0].exists(_keys().lease) == 0
