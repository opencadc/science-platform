"""Public cache identity, freshness, envelope, and memory contracts."""

from datetime import UTC, datetime, timedelta

from metrics.cache import CacheFailureCategory
from metrics.cache import (
    CacheEnvelope,
    CacheIdentity,
    FRESHNESS_POLICIES,
    Freshness,
    FreshnessPolicy,
    MemorySnapshots,
    cache_keys,
)

SECRET = b"cache-test-secret"
NOW = datetime(2025, 1, 1, tzinfo=UTC)
IDENTITY = CacheIdentity("user", "bob", "cluster-a", "kueue", "v1")


def test_cache_keys_have_exactly_two_stable_opaque_keys() -> None:
    keys = cache_keys(
        prefix="metrics:",
        identity=IDENTITY,
        secret=SECRET,
        schema_revision="2",
        source_revision="kueue",
        query_revision="1",
    )

    assert keys.value == f"{keys.base}:value"
    assert keys.lease == f"{keys.base}:lease"
    assert keys.value != keys.lease
    assert "bob" not in keys.base
    assert keys.identity_digest


def test_cache_key_digest_changes_when_subject_identity_changes() -> None:
    first = cache_keys(
        prefix="metrics:",
        identity=IDENTITY,
        secret=SECRET,
        schema_revision="2",
        source_revision="kueue",
        query_revision="1",
    )
    second = cache_keys(
        prefix="metrics:",
        identity=CacheIdentity("user", "alice", "cluster-a", "kueue", "v1"),
        secret=SECRET,
        schema_revision="2",
        source_revision="kueue",
        query_revision="1",
    )

    assert first.base != second.base
    assert first.identity_digest != second.identity_digest


def test_production_freshness_windows() -> None:
    assert FRESHNESS_POLICIES["user"] == FreshnessPolicy(2 * 60, 3 * 60, 5 * 60)
    assert FRESHNESS_POLICIES["community"] == FreshnessPolicy(5 * 60, 10 * 60, 15 * 60)
    assert FRESHNESS_POLICIES["platform"] == FreshnessPolicy(5 * 60, 30 * 60, 60 * 60)


def test_freshness_policy_has_four_positive_states_and_remaining_ttl() -> None:
    policy = FreshnessPolicy(2, 10, 15)

    assert policy.classify(NOW, now=NOW) is Freshness.FRESH
    assert policy.classify(NOW - timedelta(seconds=3), now=NOW) is Freshness.STALE
    assert policy.classify(NOW - timedelta(seconds=11), now=NOW) is Freshness.RETAINED
    assert policy.classify(NOW - timedelta(seconds=16), now=NOW) is Freshness.PURGED
    assert policy.remaining_seconds(NOW - timedelta(seconds=5), now=NOW) == 10
    assert policy.terminal_is_fresh(NOW + timedelta(seconds=1), now=NOW) is False
    assert policy.terminal_is_fresh(NOW + timedelta(seconds=1), now=NOW + timedelta(seconds=1))


def test_envelope_signs_created_identity_revisions_kind_and_value() -> None:
    keys = cache_keys(
        prefix="metrics:",
        identity=IDENTITY,
        secret=SECRET,
        schema_revision="2",
        source_revision="kueue",
        query_revision="1",
    )
    envelope = CacheEnvelope(
        format="metrics-cache-v2",
        identity_digest=keys.identity_digest,
        schema_revision="2",
        source_revision="kueue",
        query_revision="1",
        kind="value",
        created=NOW,
        value={"count": 1},
        integrity="",
    ).sign(SECRET)

    restored = CacheEnvelope.model_validate_json(envelope.model_dump_json())
    assert restored.verify(SECRET)
    assert restored.created == NOW
    assert restored.value == {"count": 1}
    assert restored.kind == "value"


def test_negative_envelope_is_the_same_signed_format_with_no_value() -> None:
    envelope = CacheEnvelope(
        format="metrics-cache-v2",
        identity_digest="digest",
        schema_revision="2",
        source_revision="kueue",
        query_revision="1",
        kind="not_found",
        created=NOW,
        value=None,
        integrity="",
    ).sign(SECRET)

    assert envelope.kind == "not_found"
    assert envelope.value is None
    assert envelope.verify(SECRET)


def test_failure_envelope_signs_only_a_bounded_category() -> None:
    envelope = CacheEnvelope(
        format="metrics-cache-v2",
        identity_digest="digest",
        schema_revision="2",
        source_revision="kueue",
        query_revision="1",
        kind="failure",
        created=NOW,
        value=None,
        failure_category=CacheFailureCategory.INTERNAL,
        integrity="",
    ).sign(SECRET)

    encoded = envelope.model_dump_json()
    restored = CacheEnvelope.model_validate_json(encoded)

    assert restored.failure_category is CacheFailureCategory.INTERNAL
    assert "source failure details" not in encoded
    assert restored.verify(SECRET)


def test_memory_is_bounded_and_supports_terminal_eviction() -> None:
    memory = MemorySnapshots[int](max_entries=1)
    memory.put("one", 1)
    memory.put("two", 2)

    assert memory.get("one") is None
    assert memory.get("two") == 2
    memory.evict("two")
    assert memory.get("two") is None
