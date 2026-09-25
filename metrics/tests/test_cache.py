"""Public cache identity, freshness, and memory contracts."""

import pytest

from metrics.cache import (
    FRESHNESS_POLICIES,
    CacheIdentity,
    Freshness,
    FreshnessPolicy,
    MemorySnapshots,
    cache_keys,
)

SECRET = b"cache-test-secret"
IDENTITY = CacheIdentity("user", "bob", "cluster-a", "kueue", "v1")


def _keys(identity: CacheIdentity = IDENTITY, schema: str = "2"):
    return cache_keys(
        prefix="metrics:",
        identity=identity,
        secret=SECRET,
        schema_revision=schema,
        source_revision="kueue",
        query_revision="1",
    )


def test_cache_keys_have_exactly_two_stable_opaque_keys_in_one_slot() -> None:
    keys = _keys()

    assert keys.value == f"{keys.base}:value"
    assert keys.lease == f"{keys.base}:lease"
    assert "bob" not in keys.base
    # One Redis Cluster hash tag holds the digest, so both keys share a slot.
    assert keys.base.count("{") == 1 and keys.base.endswith("}")
    assert keys == _keys()


def test_cache_key_changes_with_subject_identity_and_revisions() -> None:
    alice = CacheIdentity("user", "alice", "cluster-a", "kueue", "v1")

    assert _keys().base != _keys(alice).base
    assert _keys(schema="2").base != _keys(schema="3").base


def test_production_freshness_windows_are_two_stage() -> None:
    assert FRESHNESS_POLICIES == {
        "platform": FreshnessPolicy(300, 600),
        "user": FreshnessPolicy(120, 240),
        "community": FreshnessPolicy(300, 600),
        "session": FreshnessPolicy(30, 60),
    }


def test_policy_stages_come_from_remaining_ttl_with_exact_boundaries() -> None:
    policy = FreshnessPolicy(30, 60)

    assert policy.stale_ms == 60_000
    assert policy.fresh_floor_ms == 30_000
    assert policy.classify(60_000) is Freshness.FRESH  # age 0
    assert policy.classify(30_000) is Freshness.FRESH  # age == fresh window
    assert policy.classify(29_999) is Freshness.STALE
    assert policy.classify(1) is Freshness.STALE
    assert policy.classify(0) is None  # age == stale window: Redis deleted it
    assert policy.classify(-2) is None
    assert policy.age_seconds(45_000) == 15
    assert policy.age_seconds(60_500) == 0


@pytest.mark.parametrize(("fresh", "stale"), [(60, 60), (0, 60), (61, 60), (-1, 10)])
def test_policy_requires_fresh_below_stale(fresh: float, stale: float) -> None:
    with pytest.raises(ValueError):
        FreshnessPolicy(fresh, stale)


def test_memory_is_bounded_and_supports_eviction() -> None:
    memory = MemorySnapshots[int](max_entries=1)
    memory.put("one", 1)
    memory.put("two", 2)

    assert memory.get("one") is None
    assert memory.get("two") == 2
    memory.evict("two")
    assert memory.get("two") is None
