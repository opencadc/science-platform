"""Pure cache security and freshness invariants."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta

from metrics.cache import FRESHNESS_POLICIES, CacheIdentity, Freshness, cache_keys


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
