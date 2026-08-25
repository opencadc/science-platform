"""Real Redis proofs for leases, cross-replica fills, and outage fallback."""

from __future__ import annotations

import asyncio
import os
import subprocess
from dataclasses import dataclass
from datetime import UTC, datetime

import pytest
from redis.asyncio import Redis

from metrics.cache import (
    CacheIdentity,
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
    value: int
    created: datetime


def _redis_url() -> str:
    url = os.environ.get("METRICS_TEST_REDIS_URL")
    if not url:
        pytest.skip("METRICS_TEST_REDIS_URL is not configured")
    return url


def _store(redis: Redis, *, schema_revision: str = "1") -> RedisSnapshots[Snapshot]:
    return RedisSnapshots(
        redis=redis,
        value_type=Snapshot,
        secret=SECRET,
        command_timeout=0.2,
        retention_seconds=5,
        schema_revision=schema_revision,
        source_revision="1",
        query_revision="0",
    )


def _coordinator(
    redis: Redis,
    *,
    policy: FreshnessPolicy | None = None,
) -> RedisCoordinator[Snapshot]:
    return RedisCoordinator(
        store=_store(redis),
        key_prefix="test:",
        key_secret=SECRET,
        policy=policy or FreshnessPolicy(60, 120, 300),
        created=lambda snapshot: snapshot.created,
        fill_timeout=2,
        cold_timeout=3,
        poll_min=0.005,
        poll_max=0.015,
    )


@pytest.fixture
async def redis_clients():
    first = Redis.from_url(_redis_url())
    second = Redis.from_url(_redis_url())
    await first.flushdb()
    try:
        yield first, second
    finally:
        await first.flushdb()
        await first.aclose()
        await second.aclose()


async def test_two_replicas_and_100_requests_issue_one_fill(redis_clients) -> None:
    first_redis, second_redis = redis_clients
    first = _coordinator(first_redis)
    second = _coordinator(second_redis)
    fills = 0

    async def fill() -> Snapshot:
        nonlocal fills
        fills += 1
        await asyncio.sleep(0.1)
        return Snapshot(42, datetime.now(UTC))

    requests = [
        asyncio.create_task((first if index % 2 else second).get_or_fill(IDENTITY, fill))
        for index in range(100)
    ]
    results = await asyncio.gather(*requests)

    assert fills == 1
    assert {result.value.value for result in results} == {42}


async def test_lease_release_cannot_delete_a_new_owner(redis_clients) -> None:
    redis, _ = redis_clients
    store = _store(redis)
    keys = cache_keys(
        prefix="test:",
        identity=IDENTITY,
        secret=SECRET,
        schema_revision="1",
        source_revision="1",
        query_revision="0",
    )
    assert await store.acquire_lease(keys=keys, bucket=1, token="old", lease_seconds=0.05)
    await asyncio.sleep(0.08)
    assert await store.acquire_lease(keys=keys, bucket=1, token="new", lease_seconds=1)

    await store.release_lease(keys=keys, bucket=1, token="old")
    assert not await store.acquire_lease(keys=keys, bucket=1, token="third", lease_seconds=1)
    await store.release_lease(keys=keys, bucket=1, token="new")


async def test_stale_requests_have_one_refresh_winner(redis_clients) -> None:
    first_redis, second_redis = redis_clients
    policy = FreshnessPolicy(0.05, 1, 2)
    first = _coordinator(first_redis, policy=policy)
    second = _coordinator(second_redis, policy=policy)
    fills = 0

    async def fill() -> Snapshot:
        nonlocal fills
        fills += 1
        await asyncio.sleep(0.05)
        return Snapshot(fills, datetime.now(UTC))

    await first.get_or_fill(IDENTITY, fill)
    await asyncio.sleep(0.06)
    results = await asyncio.gather(
        *(coordinator.get_or_fill(IDENTITY, fill) for coordinator in [first, second] * 10)
    )

    assert fills == 2
    assert {result.value.value for result in results} == {1, 2}
    assert sum(not result.stale for result in results) == 1


async def test_unknown_envelope_revision_is_a_miss(redis_clients) -> None:
    redis, _ = redis_clients
    old_store = _store(redis, schema_revision="1")
    keys = cache_keys(
        prefix="test:",
        identity=IDENTITY,
        secret=SECRET,
        schema_revision="1",
        source_revision="1",
        query_revision="0",
    )
    created = datetime.now(UTC)
    await old_store.publish(
        keys=keys, snapshot_id="snapshot", created=created, value=Snapshot(1, created)
    )

    assert await _store(redis, schema_revision="2").read(keys) is None


async def test_redis_outage_serves_only_serviceable_l1(redis_clients) -> None:
    container = os.environ.get("METRICS_TEST_REDIS_CONTAINER")
    if not container:
        pytest.skip("METRICS_TEST_REDIS_CONTAINER is not configured")
    redis, _ = redis_clients
    coordinator = _coordinator(redis, policy=FreshnessPolicy(0.1, 0.5, 1))
    fills = 0

    async def fill() -> Snapshot:
        nonlocal fills
        fills += 1
        return Snapshot(7, datetime.now(UTC))

    assert (await coordinator.get_or_fill(IDENTITY, fill)).value.value == 7
    await asyncio.to_thread(
        subprocess.run,
        ["docker", "pause", container],
        check=True,
        capture_output=True,
    )
    try:
        fallback = await coordinator.get_or_fill(IDENTITY, fill)
        assert fallback.value.value == 7
        assert not coordinator.available
        await asyncio.sleep(0.55)
        with pytest.raises(CacheUnavailable):
            await coordinator.get_or_fill(IDENTITY, fill)
        assert fills == 1
    finally:
        await asyncio.to_thread(
            subprocess.run,
            ["docker", "unpause", container],
            check=True,
            capture_output=True,
        )
