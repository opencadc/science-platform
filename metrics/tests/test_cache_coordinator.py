"""Multi-replica coordinator contracts run against the real Lua scripts.

Each ``replica`` is one ``RedisCoordinator`` with its own client wrapper over a
shared fakeredis server, so tests observe exactly what several Metrics pods
sharing one Redis would do. Windows are shortened to fractions of a second.
"""

from __future__ import annotations

import asyncio
import logging
import random
from datetime import UTC, datetime, timedelta

import pytest
from fakeredis import FakeAsyncRedis
from pydantic.dataclasses import dataclass
from redis.exceptions import ConnectionError as RedisConnectionError

from metrics.cache import (
    CacheIdentity,
    CacheInternalError,
    CacheNotFound,
    CacheUnavailable,
    FreshnessPolicy,
    RedisCoordinator,
    RedisSnapshots,
    describe_failure,
)
from metrics.telemetry import MetricsRecorder

pytestmark = pytest.mark.anyio
IDENTITY = CacheIdentity("platform", "canfar", "c", "kueue", "v1")
POLICY = FreshnessPolicy(0.3, 0.9)  # fresh 300 ms, stale 900 ms
SECRET = b"k" * 32


@dataclass(frozen=True)
class Snap:
    n: int


class Client:
    """Share one fake Redis server, count scripts, and fail on demand."""

    def __init__(self, redis: FakeAsyncRedis) -> None:
        self.redis = redis
        self.down = False
        self.lose_reply = False
        self.scripts = 0

    def _guard(self) -> None:
        if self.down:
            raise RedisConnectionError("down")

    async def ping(self):
        self._guard()
        return await self.redis.ping()

    def _answered(self, reply):
        if self.lose_reply:
            self.lose_reply = False
            raise RedisConnectionError("reply lost after the script ran")
        return reply

    async def eval(self, *args):
        self._guard()
        return self._answered(await self.redis.eval(*args))

    async def evalsha(self, *args):
        self._guard()
        self.scripts += 1
        return self._answered(await self.redis.evalsha(*args))


class Recorder(MetricsRecorder):
    def __init__(self) -> None:
        self.lookups: list[tuple[str, str]] = []
        self.leases: list[str] = []
        self.fills: list[str] = []

    def record_cache_lookup(self, *, backend, result, scope, age_seconds=None) -> None:
        self.lookups.append((result, scope))

    def record_lease(self, *, outcome: str, scope: str) -> None:
        self.leases.append(outcome)

    def record_fill_duration(self, *, seconds: float, outcome: str, scope: str) -> None:
        self.fills.append(outcome)


class Source:
    """Count source calls and the maximum number running at once."""

    def __init__(self, delay: float = 0.05) -> None:
        self.calls = 0
        self.running = 0
        self.max_running = 0
        self.delay = delay
        self.fail: BaseException | None = None

    async def __call__(self) -> Snap:
        self.calls += 1
        self.running += 1
        self.max_running = max(self.max_running, self.running)
        try:
            await asyncio.sleep(self.delay)
            if self.fail is not None:
                raise self.fail
            return Snap(self.calls)
        finally:
            self.running -= 1


def replica(
    redis: FakeAsyncRedis,
    *,
    schema: str = "9",
    policy: FreshnessPolicy = POLICY,
    wall=None,
    fill_timeout: float = 0.5,
    cooldown: float = 0.2,
    cold_timeout: float = 1.5,
    telemetry: MetricsRecorder | None = None,
) -> tuple[Client, RedisCoordinator[Snap]]:
    client = Client(redis)
    store = RedisSnapshots[Snap](
        redis=client,
        value_type=Snap,
        secret=SECRET,
        command_timeout=0.1,
        schema_revision=schema,
        source_revision="kueue-v2",
        query_revision="0",
    )
    coordinator = RedisCoordinator[Snap](
        store=store,
        key_prefix="metrics:",
        key_secret=SECRET,
        policy=policy,
        fill_timeout=fill_timeout,
        cold_timeout=cold_timeout,
        lease_margin=0.2,
        failure_cooldown=cooldown,
        poll_min=0.005,
        poll_max=0.02,
        wall_clock=wall,
        telemetry=telemetry,
    )
    return client, coordinator


# ---------------------------------------------------------------------- fresh


async def test_cold_burst_across_replicas_fills_once_and_ttl_is_the_stale_window() -> None:
    redis = FakeAsyncRedis()
    replicas = [replica(redis)[1] for _ in range(3)]
    source = Source(delay=0.1)
    results = await asyncio.gather(
        *(replicas[i % 3].get_or_fill(IDENTITY, source) for i in range(90))
    )
    assert source.calls == 1
    assert {r.value for r in results} == {Snap(1)}
    assert sum(not r.cached for r in results) == 1
    assert sum(r.source_reachable is True for r in results) == 1
    assert all(not r.stale and r.cache_available for r in results)
    keys = replicas[0]._keys(IDENTITY)
    assert 700 < await redis.pttl(keys.value) <= 900  # R1: TTL == stale - age
    assert await redis.exists(keys.lease) == 0  # publish freed the lease


async def test_concurrent_requests_on_one_replica_share_one_observe() -> None:
    redis = FakeAsyncRedis()
    client, coordinator = replica(redis)
    source = Source(delay=0.01)
    await coordinator.get_or_fill(IDENTITY, source)
    client.scripts = 0
    results = await asyncio.gather(*(coordinator.get_or_fill(IDENTITY, source) for _ in range(50)))
    assert client.scripts == 1 and all(r.cached and not r.stale for r in results)


async def test_fresh_hits_report_age_and_serviceable_end() -> None:
    redis = FakeAsyncRedis()
    _, coordinator = replica(redis)
    source = Source(delay=0.01)
    filled = await coordinator.get_or_fill(IDENTITY, source)
    assert filled.age_seconds < 0.1 and filled.source_reachable is True
    await asyncio.sleep(0.1)
    hit = await coordinator.get_or_fill(IDENTITY, source)
    assert hit.cached and 0.08 < hit.age_seconds < 0.3
    assert hit.serviceable_until is not None
    remaining = (hit.serviceable_until - datetime.now(UTC)).total_seconds()
    assert 0.5 < remaining <= 0.9


# ---------------------------------------------------------------------- stale


async def test_stale_burst_serves_stale_immediately_and_refreshes_once() -> None:
    redis = FakeAsyncRedis()
    replicas = [replica(redis, policy=FreshnessPolicy(0.5, 1.5))[1] for _ in range(3)]
    source = Source(delay=0.2)
    await replicas[0].get_or_fill(IDENTITY, source)
    await asyncio.sleep(0.55)  # past fresh, inside stale

    started = asyncio.get_running_loop().time()
    wave = await asyncio.gather(
        *(replicas[i % 3].get_or_fill(IDENTITY, source) for i in range(120))
    )
    elapsed = asyncio.get_running_loop().time() - started
    assert elapsed < 0.1  # nobody waited on the 200 ms source call
    assert all(r.stale and r.cached and r.value == Snap(1) for r in wave)
    assert all(r.age_seconds > 0.5 for r in wave)
    await asyncio.sleep(0.05)
    second = await asyncio.gather(
        *(replicas[i % 3].get_or_fill(IDENTITY, source) for i in range(60))
    )  # during the refresh
    assert all(r.stale for r in second)
    await asyncio.sleep(0.25)
    fresh = await asyncio.gather(*(r.get_or_fill(IDENTITY, source) for r in replicas))
    assert source.calls == 2 and source.max_running == 1
    assert all(not r.stale and r.value == Snap(2) for r in fresh)


async def test_a_local_refresh_owner_suppresses_further_claims() -> None:
    redis = FakeAsyncRedis()
    client, coordinator = replica(redis)
    source = Source(delay=0.01)
    await coordinator.get_or_fill(IDENTITY, source)
    await asyncio.sleep(0.35)
    source.delay = 0.2
    for _ in range(10):  # sequential stale reads while the one refresh runs
        assert (await coordinator.get_or_fill(IDENTITY, source)).stale
    keys = coordinator._keys(IDENTITY)
    assert await redis.get(keys.lease) is not None
    assert source.calls == 2
    await asyncio.sleep(0.25)
    assert not (await coordinator.get_or_fill(IDENTITY, source)).stale
    assert client.scripts >= 11


async def test_failed_refresh_cools_down_across_replicas_and_keeps_serving_stale(
    caplog: pytest.LogCaptureFixture,
) -> None:
    redis = FakeAsyncRedis()
    replicas = [replica(redis, cooldown=0.25)[1] for _ in range(2)]
    source = Source(delay=0.01)
    await replicas[0].get_or_fill(IDENTITY, source)
    await asyncio.sleep(0.32)
    source.fail = RuntimeError("kueue down")
    with caplog.at_level(logging.WARNING, logger="metrics.cache.coordination"):
        await replicas[0].get_or_fill(IDENTITY, source)  # claims and fails once
        await asyncio.sleep(0.05)
    assert "cache fill failed scope=platform category=internal error=RuntimeError" in caplog.text
    assert "kueue down" not in caplog.text
    for _ in range(10):  # 150 ms of stale traffic inside the cooldown
        results = await asyncio.gather(*(r.get_or_fill(IDENTITY, source) for r in replicas))
        assert all(r.stale and r.value == Snap(1) for r in results)
        await asyncio.sleep(0.015)
    assert source.calls == 2  # the fill and the one failed refresh
    await asyncio.sleep(0.15)  # cooldown over
    source.fail = None
    await replicas[1].get_or_fill(IDENTITY, source)
    await asyncio.sleep(0.05)
    assert source.calls == 3


async def test_cooldown_never_outlives_the_stale_value() -> None:
    redis = FakeAsyncRedis()
    _, coordinator = replica(redis, cooldown=0.3)
    source = Source(delay=0.01)
    await coordinator.get_or_fill(IDENTITY, source)
    await asyncio.sleep(0.8)  # 100 ms of the stale window left
    source.fail = RuntimeError("down")
    await coordinator.get_or_fill(IDENTITY, source)
    await asyncio.sleep(0.03)
    keys = coordinator._keys(IDENTITY)
    assert (await redis.get(keys.lease)).startswith(b"!")
    assert await redis.pttl(keys.lease) <= await redis.pttl(keys.value) + 5
    await asyncio.sleep(0.1)  # value and cooldown gone together
    source.fail = None
    result = await coordinator.get_or_fill(IDENTITY, source)
    assert not result.cached and source.calls == 3


# ----------------------------------------------------------------------- cold


async def test_cold_failure_is_shared_and_fails_fast_during_cooldown() -> None:
    redis = FakeAsyncRedis()
    replicas = [replica(redis, cooldown=0.3)[1] for _ in range(3)]
    source = Source(delay=0.05)
    source.fail = ValueError("secret detail")
    results = await asyncio.gather(
        *(replicas[i % 3].get_or_fill(IDENTITY, source) for i in range(30)),
        return_exceptions=True,
    )
    assert source.calls == 1
    assert all(isinstance(r, CacheInternalError) and "secret" not in str(r) for r in results)
    started = asyncio.get_running_loop().time()
    with pytest.raises(CacheInternalError):
        await replicas[2].get_or_fill(IDENTITY, source)
    assert asyncio.get_running_loop().time() - started < 0.05 and source.calls == 1
    await asyncio.sleep(0.3)
    source.fail = None
    assert (await replicas[1].get_or_fill(IDENTITY, source)).value == Snap(2)


async def test_expected_source_failures_are_source_unavailable_everywhere() -> None:
    redis = FakeAsyncRedis()
    a, b = replica(redis)[1], replica(redis)[1]
    source = Source(delay=0.01)
    source.fail = CacheUnavailable("kueue 403", cache_available=True, source_reachable=False)
    for coordinator in (a, b):
        with pytest.raises(CacheUnavailable) as failure:
            await coordinator.get_or_fill(IDENTITY, source)
        assert failure.value.cache_available is True
        assert failure.value.source_reachable is False
        assert "403" not in str(failure.value)
    assert source.calls == 1


async def test_not_found_is_shared_for_the_fresh_window() -> None:
    redis = FakeAsyncRedis()
    a, b = replica(redis)[1], replica(redis)[1]
    calls = 0

    async def missing() -> Snap:
        nonlocal calls
        calls += 1
        raise CacheNotFound()

    with pytest.raises(CacheNotFound):
        await a.get_or_fill(IDENTITY, missing)
    keys = a._keys(IDENTITY)
    assert 200 < await redis.pttl(keys.value) <= 300
    with pytest.raises(CacheNotFound):
        await b.get_or_fill(IDENTITY, missing)
    assert calls == 1
    await asyncio.sleep(0.32)
    with pytest.raises(CacheNotFound):
        await b.get_or_fill(IDENTITY, missing)
    assert calls == 2


async def test_value_expires_at_the_stale_window_then_cold_fill() -> None:
    redis = FakeAsyncRedis()
    _, coordinator = replica(redis)
    source = Source(delay=0.01)
    await coordinator.get_or_fill(IDENTITY, source)
    await asyncio.sleep(0.92)
    assert await redis.exists(coordinator._keys(IDENTITY).value) == 0  # R1: nothing retained
    result = await coordinator.get_or_fill(IDENTITY, source)
    assert not result.cached and source.calls == 2


async def test_fill_that_outlives_the_stale_window_is_not_published() -> None:
    redis = FakeAsyncRedis()
    _, coordinator = replica(redis, policy=FreshnessPolicy(0.05, 0.1), fill_timeout=1.0)
    source = Source(delay=0.15)
    with pytest.raises(CacheUnavailable):
        await coordinator.get_or_fill(IDENTITY, source)
    keys = coordinator._keys(IDENTITY)
    assert await redis.exists(keys.value) == 0
    assert (await redis.get(keys.lease)).startswith(b"!")


async def test_fill_timeout_cools_down_as_source_unavailable() -> None:
    redis = FakeAsyncRedis()
    recorder = Recorder()
    _, coordinator = replica(redis, fill_timeout=0.05, telemetry=recorder)
    with pytest.raises(CacheUnavailable) as failure:
        await coordinator.get_or_fill(IDENTITY, Source(delay=1.0))
    assert failure.value.source_reachable is False
    assert recorder.fills == ["timeout"]
    lease = await redis.get(coordinator._keys(IDENTITY).lease)
    assert lease == b"!source_unavailable"


async def test_crashed_owner_is_taken_over_inside_the_cold_budget() -> None:
    redis = FakeAsyncRedis()
    _, coordinator = replica(redis)
    keys = coordinator._keys(IDENTITY)
    await redis.set(keys.lease, "0" * 32, px=coordinator._lease_ms)  # dead pod's lease
    assert coordinator._lease_ms < 1_500  # lease 0.9 s < cold 1.5 s
    source = Source(delay=0.01)
    result = await coordinator.get_or_fill(IDENTITY, source)
    assert result.value == Snap(1) and source.calls == 1


async def test_stalled_owner_is_fenced_and_cannot_overwrite_its_successor() -> None:
    redis = FakeAsyncRedis()
    _, slow = replica(redis, fill_timeout=5.0)
    _, fast = replica(redis)
    keys = slow._keys(IDENTITY)
    stalled = Source(delay=0.6)  # still running when its lease is lost
    first = asyncio.create_task(slow.get_or_fill(IDENTITY, stalled))
    await asyncio.sleep(0.05)
    await redis.delete(keys.lease)  # model lease expiry during a long stall
    other = Source(delay=0.01)
    other.calls = 41
    successor = await fast.get_or_fill(IDENTITY, other)
    assert successor.value == Snap(42)
    own = await first  # the fenced owner answers its own waiter with its genuine read
    assert own.value == Snap(1) and not own.cached
    stored = await fast._store.observe(
        keys,
        token="test-cache-integrity-key-32-bytes",
        fresh_floor_ms=POLICY.fresh_floor_ms,
        lease_ms=1,
        claim=False,
    )
    assert stored.stored is not None and stored.stored.value == Snap(42)


async def test_lost_claim_reply_is_released_and_the_next_request_fills() -> None:
    redis = FakeAsyncRedis()
    client, coordinator = replica(redis)
    client.lose_reply = True
    with pytest.raises(CacheUnavailable) as outage:
        await coordinator.get_or_fill(IDENTITY, Source(delay=0.01))
    assert outage.value.cache_available is False
    await asyncio.sleep(0.02)  # the fenced release runs in the background
    assert await redis.exists(coordinator._keys(IDENTITY).lease) == 0
    source = Source(delay=0.01)
    assert (await coordinator.get_or_fill(IDENTITY, source)).value == Snap(1)


async def test_unreadable_value_is_overwritten_by_one_fill(caplog) -> None:
    redis = FakeAsyncRedis()
    recorder = Recorder()
    replicas = [replica(redis, telemetry=recorder)[1] for _ in range(3)]
    keys = replicas[0]._keys(IDENTITY)
    await redis.set(keys.value, b"v" + b"0" * 64 + b"{}", px=900)  # fresh-looking forgery
    source = Source(delay=0.05)
    results = await asyncio.gather(
        *(replicas[i % 3].get_or_fill(IDENTITY, source) for i in range(30))
    )
    assert source.calls == 1 and {r.value for r in results} == {Snap(1)}
    rejected = [r for r in caplog.records if "cache payload rejected" in r.getMessage()]
    assert len(rejected) == 1
    # One lookup per replica flight; the claimer's reports the rejected payload.
    results = [result for result, _ in recorder.lookups]
    assert len(results) == 3 and "invalid" in results


async def test_schema_revisions_use_disjoint_keys() -> None:
    redis = FakeAsyncRedis()
    _, old = replica(redis, schema="8")
    _, new = replica(redis, schema="9")
    source = Source(delay=0.01)
    await old.get_or_fill(IDENTITY, source)
    result = await new.get_or_fill(IDENTITY, source)
    assert not result.cached and source.calls == 2


async def test_replica_wall_clock_skew_changes_nothing_but_serviceable_until() -> None:
    redis = FakeAsyncRedis()
    ahead = replica(redis, wall=lambda: datetime.now(UTC) + timedelta(seconds=30))[1]
    behind = replica(redis, wall=lambda: datetime.now(UTC) - timedelta(seconds=30))[1]
    source = Source(delay=0.01)
    await ahead.get_or_fill(IDENTITY, source)
    result = await behind.get_or_fill(IDENTITY, source)
    assert result.cached and not result.stale and source.calls == 1
    assert result.serviceable_until is not None
    assert result.serviceable_until < datetime.now(UTC)  # expressed in the reader's clock


# ---------------------------------------------------------------- redis outage


async def test_redis_outage_serves_l1_with_its_real_stage_and_never_calls_the_source() -> None:
    redis = FakeAsyncRedis()
    client, coordinator = replica(redis)
    source = Source(delay=0.01)
    await coordinator.get_or_fill(IDENTITY, source)
    client.down = True
    fresh = await coordinator.get_or_fill(IDENTITY, source)
    assert fresh.cached and not fresh.stale and not fresh.cache_available
    assert not coordinator.available
    await asyncio.sleep(0.35)
    stale = await coordinator.get_or_fill(IDENTITY, source)
    assert stale.stale and not stale.cache_available and stale.age_seconds > 0.3
    await asyncio.sleep(0.6)
    with pytest.raises(CacheUnavailable) as closed:
        await coordinator.get_or_fill(IDENTITY, source)
    assert closed.value.cache_available is False and source.calls == 1
    client.down = False
    await coordinator.ping()
    assert coordinator.available


async def test_not_found_evicts_the_outage_copy() -> None:
    redis = FakeAsyncRedis()
    client, coordinator = replica(redis, policy=FreshnessPolicy(0.05, 0.9))
    await coordinator.get_or_fill(IDENTITY, Source(delay=0.01))
    await asyncio.sleep(0.06)

    async def missing() -> Snap:
        raise CacheNotFound()

    await coordinator.get_or_fill(IDENTITY, missing)  # stale hit starts the refresh
    await asyncio.sleep(0.03)
    client.down = True
    with pytest.raises(CacheUnavailable):
        await coordinator.get_or_fill(IDENTITY, missing)


# ------------------------------------------------------ cancellation, shutdown


async def test_cancelled_waiter_does_not_cancel_the_shared_fill() -> None:
    redis = FakeAsyncRedis()
    _, a = replica(redis)
    _, b = replica(redis)
    source = Source(delay=0.2)
    waiter = asyncio.create_task(a.get_or_fill(IDENTITY, source))
    await asyncio.sleep(0.05)
    waiter.cancel()
    follower = await b.get_or_fill(IDENTITY, source)
    assert follower.value == Snap(1) and source.calls == 1


async def test_follower_deadline_on_healthy_redis_keeps_cache_available() -> None:
    redis = FakeAsyncRedis()
    _, owner = replica(redis, fill_timeout=1.0)
    _, follower = replica(redis, cold_timeout=0.1)
    asyncio.create_task(owner.get_or_fill(IDENTITY, Source(delay=0.5)))
    await asyncio.sleep(0.02)
    with pytest.raises(CacheUnavailable) as timeout:
        await follower.get_or_fill(IDENTITY, Source())
    assert timeout.value.cache_available is True
    await owner.shutdown()


async def test_shutdown_releases_the_lease_and_maps_waiters_to_503() -> None:
    redis = FakeAsyncRedis()
    _, a = replica(redis)
    source = Source(delay=1.0)
    waiter = asyncio.create_task(a.get_or_fill(IDENTITY, source))
    await asyncio.sleep(0.05)
    await a.shutdown()
    with pytest.raises(CacheUnavailable):
        await asyncio.wait_for(waiter, timeout=5)
    await asyncio.sleep(0.02)
    assert await redis.exists(a._keys(IDENTITY).lease) == 0
    with pytest.raises(CacheUnavailable):
        await a.get_or_fill(IDENTITY, source)
    _, b = replica(redis)
    assert (await b.get_or_fill(IDENTITY, Source(delay=0.01))).value == Snap(1)


# ------------------------------------------------------------------ telemetry


async def test_telemetry_records_stage_and_lease_outcomes() -> None:
    redis = FakeAsyncRedis()
    recorder = Recorder()
    _, a = replica(redis, telemetry=recorder, cooldown=0.1)
    _, b = replica(redis, telemetry=recorder)
    source = Source(delay=0.1)
    await asyncio.gather(a.get_or_fill(IDENTITY, source), b.get_or_fill(IDENTITY, source))
    assert sorted(recorder.leases) == ["acquired", "contended"]
    assert recorder.fills == ["ok"]
    await a.get_or_fill(IDENTITY, source)
    await asyncio.sleep(0.35)
    source.delay = 0.01
    await a.get_or_fill(IDENTITY, source)
    assert [result for result, _ in recorder.lookups] == ["miss", "miss", "hit", "stale"]


def test_failure_description_names_types_and_http_status_only() -> None:
    class Response:
        status_code = 403

    class ServerError(Exception):
        response = Response()

    try:
        try:
            raise ServerError("clusterqueues bob is forbidden")
        except ServerError as exc:
            raise ValueError("user bob") from exc
    except ValueError as outer:
        description = describe_failure(outer)
    assert description == "ValueError <- ServerError(403)"

    from metrics.errors import ProviderExecutionError

    try:
        try:
            raise ServerError("clusterqueues bob is forbidden")
        except ServerError as exc:
            raise ProviderExecutionError("PromQL returned no efficiency series") from exc
    except ProviderExecutionError as own:
        assert describe_failure(own) == (
            "ProviderExecutionError: PromQL returned no efficiency series <- ServerError(403)"
        )


# ---------------------------------------------------------------- property: R2


@pytest.mark.parametrize("seed", range(6))
async def test_at_most_one_fill_per_subject_under_random_multi_replica_traffic(seed: int) -> None:
    rng = random.Random(seed)
    redis = FakeAsyncRedis()
    replicas = [
        replica(redis, policy=FreshnessPolicy(0.08, 0.25), fill_timeout=0.2, cooldown=0.03)[1]
        for _ in range(rng.choice([2, 4, 8]))
    ]
    subjects = [CacheIdentity("user", f"u{i}", "c", "kueue", "v1") for i in range(3)]
    running: dict[str, int] = {}
    worst = 0
    fills = 0

    def source_for(subject: str):
        async def fill() -> Snap:
            nonlocal worst, fills
            fills += 1
            running[subject] = running.get(subject, 0) + 1
            worst = max(worst, running[subject])
            try:
                await asyncio.sleep(rng.uniform(0.005, 0.06))
                if rng.random() < 0.2:
                    raise RuntimeError("flaky source")
                return Snap(fills)
            finally:
                running[subject] -= 1

        return fill

    async def client(index: int) -> None:
        for _ in range(40):
            identity = rng.choice(subjects)
            coordinator = replicas[(index + rng.randrange(len(replicas))) % len(replicas)]
            try:
                await coordinator.get_or_fill(identity, source_for(identity.subject_value))
            except (CacheUnavailable, CacheInternalError):
                # Injected source failures are expected outcomes here; the
                # property under test is one fill per subject at a time.
                pass
            await asyncio.sleep(rng.uniform(0, 0.02))

    await asyncio.gather(*(client(i) for i in range(12)))
    assert worst == 1 and fills > 3
    for coordinator in replicas:
        await coordinator.shutdown()
