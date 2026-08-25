"""Pure cache security and freshness invariants."""

from __future__ import annotations

import asyncio
from datetime import UTC, datetime, timedelta

import pytest

from metrics.cache import (
    FRESHNESS_POLICIES,
    CacheIdentity,
    Freshness,
    FreshnessPolicy,
    RedisCoordinator,
    cache_keys,
)


def test_subject_keys_redact_values_and_rotate_with_secret() -> None:
    identity = CacheIdentity(
        subject_kind="user",
        subject_value="Alice.Example@CANFAR.NET",
        cluster="prod",
        source="kubernetes",
        fingerprint="namespaces-v1",
    )
    first = cache_keys(
        prefix="metrics:",
        identity=identity,
        secret=b"a" * 32,
        schema_revision="1",
        source_revision="2",
        query_revision="3",
    )
    rotated = cache_keys(
        prefix="metrics:",
        identity=identity,
        secret=b"b" * 32,
        schema_revision="1",
        source_revision="2",
        query_revision="3",
    )

    assert "alice" not in first.base.lower()
    assert "canfar" not in first.base.lower()
    assert first.base != rotated.base


def test_community_keys_are_hmac_redacted_and_isolated() -> None:
    astronomy = CacheIdentity("community", "astronomy", "prod", "kubernetes")
    physics = CacheIdentity("community", "physics", "prod", "kubernetes")
    keys = [
        cache_keys(
            prefix="metrics:",
            identity=identity,
            secret=b"a" * 32,
            schema_revision="1",
            source_revision="1",
            query_revision="0",
        ).base
        for identity in (astronomy, physics)
    ]

    assert keys[0] != keys[1]
    assert all(subject not in key for key in keys for subject in ("astronomy", "physics"))


def test_all_subject_freshness_windows_match_policy() -> None:
    now = datetime.now(UTC)
    expected = {
        "platform": (5 * 60, 30 * 60, 60 * 60),
        "user": (2 * 60, 10 * 60, 15 * 60),
        "community": (2 * 60, 10 * 60, 15 * 60),
    }
    for subject, boundaries in expected.items():
        policy = FRESHNESS_POLICIES[subject]
        assert (policy.fresh_seconds, policy.stale_seconds, policy.retention_seconds) == boundaries
        assert (
            policy.classify(now - timedelta(seconds=policy.fresh_seconds - 1), now=now)
            is Freshness.FRESH
        )
        assert (
            policy.classify(now - timedelta(seconds=policy.fresh_seconds + 1), now=now)
            is Freshness.STALE
        )
        assert (
            policy.classify(now - timedelta(seconds=policy.stale_seconds + 1), now=now)
            is Freshness.RETAINED
        )
        assert (
            policy.classify(now - timedelta(seconds=policy.retention_seconds + 1), now=now)
            is Freshness.PURGED
        )


@pytest.mark.anyio
async def test_redis_coordinator_shutdown_cancels_cold_fill() -> None:
    class Store:
        schema_revision = source_revision = query_revision = "1"

        async def read(self, _keys):
            return None

        async def pointer(self, _keys):
            return None

        async def acquire_lease(self, **_kwargs):
            return True

        async def release_lease(self, **_kwargs):
            return None

    started = asyncio.Event()

    async def fill():
        started.set()
        await asyncio.Future()

    coordinator = RedisCoordinator(
        store=Store(),
        key_prefix="test:",
        key_secret=b"x" * 32,
        policy=FreshnessPolicy(60, 120, 180),
        created=lambda value: value.created,
    )
    request = asyncio.create_task(
        coordinator.get_or_fill(CacheIdentity("platform", "canfar", "c", "test"), fill)
    )
    await started.wait()
    await coordinator.shutdown()

    with pytest.raises(asyncio.CancelledError):
        await request
