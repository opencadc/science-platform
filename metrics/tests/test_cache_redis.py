"""Redis adapter contracts, run against the real Lua scripts on fakeredis."""

from __future__ import annotations

import asyncio
from dataclasses import dataclass

import pytest
from fakeredis import FakeAsyncRedis
from redis.exceptions import ConnectionError as RedisConnectionError

from metrics.cache import (
    CacheFailureCategory,
    CacheIdentity,
    RedisSnapshots,
    RedisUnavailable,
    StoredNotFound,
    StoredSnapshot,
    cache_keys,
)
from metrics.telemetry import MetricsRecorder

pytestmark = pytest.mark.anyio

SECRET = b"k" * 32
IDENTITY = CacheIdentity("user", "bob", "cluster-a", "kueue", "v1")
TOKEN = "a" * 32
OTHER = "b" * 32


@dataclass(frozen=True)
class Snap:
    n: int


class Recorder(MetricsRecorder):
    def __init__(self) -> None:
        self.redis: list[tuple[str, str]] = []

    def record_redis(self, *, operation: str, outcome: str, seconds: float) -> None:
        self.redis.append((operation, outcome))


def _keys(identity: CacheIdentity = IDENTITY):
    return cache_keys(
        prefix="metrics:",
        identity=identity,
        secret=SECRET,
        schema_revision="9",
        source_revision="kueue-v2",
        query_revision="0",
    )


def _store(redis, telemetry: MetricsRecorder | None = None) -> RedisSnapshots[Snap]:
    return RedisSnapshots[Snap](
        redis=redis,
        value_type=Snap,
        secret=SECRET,
        command_timeout=0.2,
        schema_revision="9",
        source_revision="kueue-v2",
        query_revision="0",
        telemetry=telemetry,
    )


async def _observe(store: RedisSnapshots[Snap], *, token: str = TOKEN, claim: bool = True, **kw):
    return await store.observe(
        _keys(),
        token=token,
        fresh_floor_ms=500,
        lease_ms=kw.pop("lease_ms", 1_000),
        claim=claim,
        **kw,
    )


def test_payload_round_trips_and_is_bound_to_its_key() -> None:
    store = _store(FakeAsyncRedis())
    keys = _keys()
    raw = store.encode(keys, StoredSnapshot(Snap(7)))

    assert raw[:1] == b"v"
    assert store.decode(keys, raw) == StoredSnapshot(Snap(7))
    assert store.decode(keys, store.encode(keys, StoredNotFound())) == StoredNotFound()
    other = _keys(CacheIdentity("user", "alice", "cluster-a", "kueue", "v1"))
    assert store.decode(other, raw) is None  # a payload copied under another subject
    assert store.decode(keys, raw[:-1] + b"8") is None  # tampered body
    assert store.decode(keys, b"x" + raw[1:]) is None  # unknown kind
    assert store.decode(keys, b"n" + raw[1:65] + b"extra") is None


def test_unreadable_values_never_raise() -> None:
    store = _store(FakeAsyncRedis())
    keys = _keys()
    good = store.encode(keys, StoredSnapshot(Snap(1)))

    assert store.decode(keys, b"") is None
    assert store.decode(keys, b"v") is None
    assert store.decode(keys, "v".encode() + "é".encode() * 32 + b"{}") is None
    forged = b"v" + store._mac(keys.value, b"v", b"not json") + b"not json"
    assert store.decode(keys, forged) is None
    wrong_shape = b"v" + store._mac(keys.value, b"v", b'{"x":1}') + b'{"x":1}'
    assert store.decode(keys, wrong_shape) is None
    assert store.decode(keys, good) is not None


async def test_observe_claims_only_absent_stale_or_forced_values() -> None:
    redis = FakeAsyncRedis()
    store = _store(redis)
    keys = _keys()

    absent = await _observe(store)
    assert absent.stored is None and absent.claimed and not absent.unreadable
    assert await redis.get(keys.lease) == TOKEN.encode()
    await redis.delete(keys.lease)

    await redis.set(keys.value, store.encode(keys, StoredSnapshot(Snap(1))), px=900)
    fresh = await _observe(store)
    assert fresh.stored == StoredSnapshot(Snap(1)) and not fresh.claimed
    assert 800 < fresh.ttl_ms <= 900

    await redis.pexpire(keys.value, 400)  # below the 500 ms fresh floor: stale
    stale = await _observe(store)
    assert stale.stored == StoredSnapshot(Snap(1)) and stale.claimed

    second = await _observe(store, token=OTHER)
    assert second.stored is not None and not second.claimed and second.cooldown is None

    await redis.delete(keys.lease)
    unclaimed = await _observe(store, claim=False)
    assert not unclaimed.claimed and await redis.exists(keys.lease) == 0


async def test_live_not_found_is_never_claimed_over() -> None:
    redis = FakeAsyncRedis()
    store = _store(redis)
    keys = _keys()
    await redis.set(keys.value, store.encode(keys, StoredNotFound()), px=100)

    seen = await _observe(store)
    assert seen.stored == StoredNotFound() and not seen.claimed


async def test_unreadable_fresh_value_is_claimed_only_when_forced() -> None:
    redis = FakeAsyncRedis()
    store = _store(redis)
    keys = _keys()
    await redis.set(keys.value, b"v" + b"0" * 64 + b"{}", px=900)

    plain = await _observe(store)
    assert plain.unreadable and not plain.claimed and plain.stored is None
    forced = await _observe(store, force=True)
    assert forced.unreadable and forced.claimed

    await redis.set(keys.value, b"v" + b"0" * 64)  # no TTL at all
    await redis.delete(keys.lease)
    persistent = await _observe(store)
    assert persistent.stored is None and persistent.unreadable and persistent.claimed


async def test_retried_observe_recognises_its_own_claim() -> None:
    redis = FakeAsyncRedis()
    store = _store(redis)
    keys = _keys()
    # The first attempt applied SET NX but its reply was lost; the client resent it.
    await redis.set(keys.lease, TOKEN, px=1_000)

    assert (await _observe(store)).claimed
    assert not (await _observe(store, token=OTHER)).claimed
    assert not (await _observe(store, claim=False)).claimed


async def test_cooldown_marker_is_reported_and_blocks_claims() -> None:
    redis = FakeAsyncRedis()
    store = _store(redis)
    keys = _keys()
    assert (await _observe(store)).claimed
    assert await store.cool_down(
        keys, token=TOKEN, category=CacheFailureCategory.INTERNAL, cooldown_ms=300
    )

    seen = await _observe(store, token=OTHER)
    assert not seen.claimed and seen.cooldown is CacheFailureCategory.INTERNAL
    assert 200 < await redis.pttl(keys.lease) <= 300
    await redis.set(keys.lease, "!bogus", px=300)
    assert (await _observe(store, token=OTHER)).cooldown is CacheFailureCategory.INTERNAL


async def test_settle_modes_are_fenced_on_the_lease_token() -> None:
    redis = FakeAsyncRedis()
    store = _store(redis)
    keys = _keys()
    await _observe(store)

    assert not await store.publish(keys, token=OTHER, stored=StoredSnapshot(Snap(9)), ttl_ms=900)
    assert not await store.cool_down(
        keys, token=OTHER, category=CacheFailureCategory.INTERNAL, cooldown_ms=100
    )
    assert not await store.release(keys, token=OTHER)
    assert await redis.get(keys.lease) == TOKEN.encode()
    assert await redis.exists(keys.value) == 0

    assert await store.publish(keys, token=TOKEN, stored=StoredSnapshot(Snap(2)), ttl_ms=900)
    assert await redis.exists(keys.lease) == 0
    assert 800 < await redis.pttl(keys.value) <= 900
    assert not await store.release(keys, token=TOKEN)  # the publish already freed it


async def test_cooldown_never_outlives_an_existing_value() -> None:
    redis = FakeAsyncRedis()
    store = _store(redis)
    keys = _keys()
    await redis.set(keys.value, store.encode(keys, StoredSnapshot(Snap(1))), px=150)
    await _observe(store)

    assert await store.cool_down(
        keys, token=TOKEN, category=CacheFailureCategory.SOURCE_UNAVAILABLE, cooldown_ms=5_000
    )
    assert await redis.pttl(keys.lease) <= 150
    assert await redis.get(keys.lease) == b"!source_unavailable"


async def test_scripts_are_reloaded_after_a_script_flush() -> None:
    redis = FakeAsyncRedis()
    store = _store(redis)
    assert (await _observe(store)).claimed
    await redis.script_flush()
    await redis.delete(_keys().lease)
    assert (await _observe(store)).claimed


class _Broken:
    def __init__(self, reply=None, error: Exception | None = None, delay: float = 0) -> None:
        self.reply, self.error, self.delay = reply, error, delay

    async def _answer(self, *_args):
        await asyncio.sleep(self.delay)
        if self.error is not None:
            raise self.error
        return self.reply

    def ping(self):
        return self._answer()

    def eval(self, *args):
        return self._answer(*args)

    def evalsha(self, *args):
        return self._answer(*args)


@pytest.mark.parametrize(
    "client",
    [
        _Broken(error=RedisConnectionError("down")),
        _Broken(delay=1.0),
        _Broken(reply=[b"v", "x", 0, None, -2]),
        _Broken(reply=[b"v", 1]),
    ],
)
async def test_observe_failures_are_bounded_redis_unavailable(client) -> None:
    recorder = Recorder()
    with pytest.raises(RedisUnavailable):
        await _observe(_store(client, recorder))
    assert recorder.redis[-1][0] == "observe"


async def test_invalid_settle_and_ping_results_are_redis_unavailable() -> None:
    with pytest.raises(RedisUnavailable):
        await _store(_Broken(reply=7)).release(_keys(), token=TOKEN)
    with pytest.raises(RedisUnavailable):
        await _store(_Broken(reply=False)).ping()


async def test_telemetry_names_every_redis_operation() -> None:
    recorder = Recorder()
    store = _store(FakeAsyncRedis(), recorder)
    keys = _keys()
    await store.ping()
    await _observe(store)
    await store.cool_down(keys, token=TOKEN, category=CacheFailureCategory.INTERNAL, cooldown_ms=1)
    await asyncio.sleep(0.01)
    await _observe(store)
    await store.publish(keys, token=TOKEN, stored=StoredSnapshot(Snap(1)), ttl_ms=10)
    await store.release(keys, token=TOKEN)

    assert [name for name, _ in recorder.redis] == [
        "ping",
        "observe",
        "cooldown",
        "observe",
        "publish",
        "release",
    ]
    assert {outcome for _, outcome in recorder.redis} == {"ok"}
