"""Public Redis-adapter tests without reimplementing its Lua scripts."""

from __future__ import annotations

import asyncio
import json
from dataclasses import dataclass
from datetime import UTC, datetime

import pytest

from metrics.cache import (
    CacheFailureCategory,
    CacheIdentity,
    RedisSnapshots,
    StoredFailure,
    StoredNotFound,
)
from metrics.cache import RedisUnavailable, cache_keys
from metrics.telemetry import MetricsRecorder

SECRET = b"cache-test-secret"
IDENTITY = CacheIdentity("user", "bob", "cluster-a", "kueue", "v1")
NOW = datetime(2025, 1, 1, tzinfo=UTC)


@dataclass(frozen=True, slots=True)
class Snapshot:
    """Small typed value for adapter validation tests."""

    value: int
    created: datetime


class RedisFake:
    """Record Redis calls and return configured command results."""

    def __init__(self) -> None:
        self.values: dict[str, object] = {}
        self.eval_result: object = 1
        self.eval_calls: list[tuple[str, int, tuple[object, ...]]] = []
        self.block_get = False
        self.get_started = asyncio.Event()

    async def ping(self) -> object:
        """Return a successful health response."""
        return True

    async def get(self, name: str) -> object:
        """Return one stored fake value."""
        if self.block_get:
            self.get_started.set()
            await asyncio.Future()
        return self.values.get(name)

    async def set(
        self, name: str, value: object, *, nx: bool = False, px: int | None = None
    ) -> object:
        """Record one lease SET and honor NX for the fake seam."""
        del px
        if nx and name in self.values:
            return None
        self.values[name] = value
        return True

    async def eval(self, script: str, numkeys: int, *keys_and_args: object) -> object:
        """Return a configured Lua result without interpreting the script."""
        self.eval_calls.append((script, numkeys, keys_and_args))
        return self.eval_result


def _keys(identity: CacheIdentity = IDENTITY):
    """Build one test identity's two keys."""
    return cache_keys(
        prefix="metrics:",
        identity=identity,
        secret=SECRET,
        schema_revision="2",
        source_revision="kueue",
        query_revision="1",
    )


def _store(redis: RedisFake) -> RedisSnapshots[Snapshot]:
    """Build an adapter with deterministic test revisions."""
    return RedisSnapshots(
        redis=redis,
        value_type=Snapshot,
        secret=SECRET,
        command_timeout=0.05,
        schema_revision="2",
        source_revision="kueue",
        query_revision="1",
    )


def _payload(
    store: RedisSnapshots[Snapshot],
    keys,
    *,
    kind: str = "value",
    failure_category: CacheFailureCategory | None = None,
) -> str:
    """Create a valid signed payload for a read seam test."""
    value = Snapshot(3, NOW) if kind == "value" else None
    return store._sign(  # noqa: SLF001
        keys=keys,
        kind=kind,
        created=NOW,
        value=value,
        failure_category=failure_category,
    )


@pytest.mark.anyio
async def test_read_accepts_typed_positive_envelope() -> None:
    """Valid signatures and revisions decode through the public read method."""
    redis = RedisFake()
    store = _store(redis)
    keys = _keys()
    redis.values[keys.value] = _payload(store, keys)

    result = await store.read(keys)

    assert result is not None
    assert result.value == Snapshot(3, NOW)  # type: ignore[union-attr]
    assert result.created == NOW  # type: ignore[union-attr]


@pytest.mark.anyio
async def test_read_rejects_payload_copied_between_subject_keys() -> None:
    """The signed identity digest prevents Alice data from serving Bob."""
    redis = RedisFake()
    store = _store(redis)
    alice_keys = _keys(CacheIdentity("user", "alice", "cluster-a", "kueue", "v1"))
    bob_keys = _keys()
    redis.values[bob_keys.value] = _payload(store, alice_keys)

    assert await store.read(bob_keys) is None


@pytest.mark.anyio
async def test_read_returns_authenticated_negative_envelope() -> None:
    """The one envelope format carries a signed not-found creation time."""
    redis = RedisFake()
    store = _store(redis)
    keys = _keys()
    redis.values[keys.value] = _payload(store, keys, kind="not_found")

    result = await store.read(keys)

    assert isinstance(result, StoredNotFound)
    assert result.created == NOW


@pytest.mark.anyio
async def test_read_returns_signed_failure_category_and_rejects_tampering() -> None:
    """Failure state carries no source text and cannot be changed unsigned."""
    redis = RedisFake()
    store = _store(redis)
    keys = _keys()
    redis.values[keys.value] = _payload(
        store,
        keys,
        kind="failure",
        failure_category=CacheFailureCategory.SOURCE_UNAVAILABLE,
    )

    result = await store.read(keys)

    assert isinstance(result, StoredFailure)
    assert result.category is CacheFailureCategory.SOURCE_UNAVAILABLE

    payload = json.loads(redis.values[keys.value])
    payload["failure_category"] = CacheFailureCategory.INTERNAL.value
    redis.values[keys.value] = json.dumps(payload)
    assert await store.read(keys) is None


@pytest.mark.anyio
async def test_read_treats_malformed_or_tampered_state_as_a_miss() -> None:
    """Untrusted Redis bytes never cross the typed cache seam."""
    redis = RedisFake()
    store = _store(redis)
    keys = _keys()
    redis.values[keys.value] = "not-json"
    assert await store.read(keys) is None

    redis.values[keys.value] = _payload(store, keys)
    payload = json.loads(redis.values[keys.value])
    payload["value"]["value"] = 99
    redis.values[keys.value] = json.dumps(payload)
    assert await store.read(keys) is None


@pytest.mark.anyio
async def test_commit_is_always_fenced_and_uses_only_two_keys() -> None:
    """The adapter sends the stable value and lease keys to one Lua commit."""
    redis = RedisFake()
    store = _store(redis)
    keys = _keys()
    redis.eval_result = 0

    committed = await store.commit(
        keys=keys,
        token="owner",
        created=NOW,
        value=Snapshot(1, NOW),
        ttl_seconds=12.5,
    )

    assert committed is False
    _script, numkeys, args = redis.eval_calls[-1]
    assert numkeys == 2
    assert args[:2] == (keys.value, keys.lease)
    assert args[3] == "owner"
    assert args[4] == 12500
    assert "latest" not in args[0]


@pytest.mark.anyio
async def test_negative_commit_carries_created_time_and_fresh_ttl() -> None:
    """Terminal publication uses the caller's two-minute policy TTL."""
    redis = RedisFake()
    store = _store(redis)
    keys = _keys()

    assert await store.commit(
        keys=keys,
        token="owner",
        created=NOW,
        value=None,
        ttl_seconds=120,
        not_found=True,
    )
    payload = json.loads(redis.eval_calls[-1][2][2])
    assert payload["kind"] == "not_found"
    assert payload["created"].startswith("2025-01-01T00:00:00")
    assert redis.eval_calls[-1][2][4] == 120000


@pytest.mark.anyio
async def test_failure_commit_carries_only_the_bounded_category() -> None:
    """A failed fill is serialized without its exception text."""
    redis = RedisFake()
    store = _store(redis)
    keys = _keys()

    assert await store.commit(
        keys=keys,
        token="owner",
        created=NOW,
        value=None,
        ttl_seconds=5,
        failure_category=CacheFailureCategory.INTERNAL,
    )

    payload = json.loads(redis.eval_calls[-1][2][2])
    assert payload["kind"] == "failure"
    assert payload["failure_category"] == "internal"
    assert "exception text" not in json.dumps(payload)


@pytest.mark.anyio
async def test_lease_release_is_owner_checked_by_lua_seam() -> None:
    """Release uses one lease key and the exact token."""
    redis = RedisFake()
    store = _store(redis)
    keys = _keys()

    await store.release_lease(keys=keys, token="owner")

    _script, numkeys, args = redis.eval_calls[-1]
    assert numkeys == 1
    assert args == (keys.lease, "owner")


@pytest.mark.anyio
async def test_command_timeout_maps_to_redis_unavailable() -> None:
    """Every Redis command has a finite deadline."""
    redis = RedisFake()
    redis.block_get = True
    store = _store(redis)

    with pytest.raises(RedisUnavailable):
        await store.read(_keys())


@pytest.mark.anyio
async def test_telemetry_failure_does_not_break_cache_commands() -> None:
    """Observability failures are isolated from lease and commit behavior."""

    class BrokenTelemetry(MetricsRecorder):
        """Raise from every Redis observation."""

        def record_redis(self, **_details: object) -> None:
            """Raise a synthetic recorder failure."""
            raise RuntimeError("telemetry failed")

    redis = RedisFake()
    store = RedisSnapshots(
        redis=redis,
        value_type=Snapshot,
        secret=SECRET,
        command_timeout=0.05,
        schema_revision="2",
        source_revision="kueue",
        query_revision="1",
        telemetry=BrokenTelemetry(),
    )

    await store.ping()
    assert await store.commit(
        keys=_keys(),
        token="owner",
        created=NOW,
        value=Snapshot(1, NOW),
        ttl_seconds=5,
    )
