"""Runtime wiring, latched readiness, and lifecycle tests."""

from __future__ import annotations

import asyncio
import logging
from datetime import UTC, datetime

import pytest

import metrics.core.runtime as runtime_module
from metrics.cache import FRESHNESS_POLICIES, CacheIdentity, RedisUnavailable
from metrics.core.runtime import MetricsRuntime, build_redis, cache_identity
from metrics.core.settings import (
    CacheConfig,
    KueueProviderConfig,
    PromQLProviderConfig,
    ProviderConfigs,
    Settings,
)
from metrics.errors import ProviderUnavailableError, RuntimeStartupError
from metrics.providers.promql import PromQLProvider
from metrics.services.metrics import MetricsService
from metrics.services.models import (
    CommunityObservation,
    PlatformObservation,
    SessionObservation,
    SessionUsageObservation,
    UserObservation,
)
from metrics.services.snapshots import SnapshotLoader
from metrics.telemetry import MetricsRecorder, NoopMetricsRecorder
from tests.test_cache_helpers import FakeCacheCoordinator


def _settings(**promql) -> Settings:
    """Build valid Redis-backed settings."""
    return Settings(
        cluster_name="cluster-a",
        redis_url="redis://:hunter2@localhost:6379/0",
        cache=CacheConfig(key_secret="test-cache-integrity-key-32-bytes"),
        providers=ProviderConfigs(
            kueue=KueueProviderConfig(cluster_queues=["cq-a"], namespaces=["work-a"]),
            promql=PromQLProviderConfig(**promql),
        ),
    )


# -------------------------------------------------------------------- wiring


def test_from_settings_builds_one_cache_per_surface_over_one_redis_client() -> None:
    runtime = MetricsRuntime.from_settings(_settings(), recorder=NoopMetricsRecorder())
    caches = runtime.metrics_service.caches

    assert set(caches) == {"platform", "user", "community", "session"}
    assert all(isinstance(cache, runtime_module.RedisCoordinator) for cache in caches.values())
    assert {surface: cache.policy for surface, cache in caches.items()} == FRESHNESS_POLICIES
    stores = {id(cache._store._redis) for cache in caches.values()}  # noqa: SLF001
    assert len(stores) == 1
    assert runtime._efficiency is None  # noqa: SLF001


def test_an_endpoint_enables_the_promql_provider() -> None:
    runtime = MetricsRuntime.from_settings(
        _settings(base_url="https://mimir.example/api/prom"), recorder=NoopMetricsRecorder()
    )
    assert isinstance(runtime._efficiency, PromQLProvider)  # noqa: SLF001


def test_runtime_cache_schema_revision_separates_two_stage_payloads() -> None:
    """Two-stage payloads and lease markers live in a keyspace older pods never read."""
    assert runtime_module._SCHEMA_REVISION == "9"  # noqa: SLF001


def test_runtime_lease_expires_before_cold_waiters_give_up() -> None:
    """A crashed owner's lease ends inside every follower's cold budget."""
    settings = _settings()
    runtime = MetricsRuntime.from_settings(settings, recorder=NoopMetricsRecorder())
    for cache in runtime.metrics_service.caches.values():
        assert cache._lease_ms == round(settings.cache.lease_seconds * 1000)  # noqa: SLF001
        assert cache._lease_ms < settings.cache.cold_get_timeout_seconds * 1000  # noqa: SLF001


def test_redis_client_is_lazy_retries_once_and_names_itself() -> None:
    settings = _settings()
    redis = build_redis(settings)
    kwargs = redis.connection_pool.connection_kwargs
    assert kwargs["client_name"] == "canfar-metrics"
    assert redis.get_retry()._retries == 1  # noqa: SLF001
    assert "hunter2" not in repr(settings)


def test_cache_identity_is_opaque_and_stable_across_operational_settings() -> None:
    identity = cache_identity(
        "platform", "canfar", cluster="cluster-a", source="kueue", fingerprint="rev"
    )
    assert identity == CacheIdentity("platform", "canfar", "cluster-a", "kueue", "rev")

    def platform_key(settings: Settings) -> bytes:
        runtime = MetricsRuntime.from_settings(settings, recorder=NoopMetricsRecorder())
        return runtime.metrics_service._identity("platform", "canfar").canonical()  # noqa: SLF001

    base = platform_key(_settings(base_url="https://mimir.example"))
    slower = platform_key(_settings(base_url="https://mimir.example", request_timeout_seconds=9))
    other = platform_key(_settings(base_url="https://other.example"))
    assert base == slower and base != other


# ----------------------------------------------------------------- lifecycle


class Kueue:
    """Kueue fake with controllable validation, probe, and shutdown."""

    name = "kueue"

    def __init__(
        self,
        *,
        validate_error: BaseException | None = None,
        validate_gate: asyncio.Event | None = None,
        shutdown_error: BaseException | None = None,
    ) -> None:
        self.validate_error = validate_error
        self.validate_gate = validate_gate
        self.shutdown_error = shutdown_error
        self.validations = 0
        self.validate_entered = asyncio.Event()
        self.shutdowns = 0

    async def validate_platform(self) -> None:
        self.validations += 1
        self.validate_entered.set()
        if self.validate_gate is not None:
            await self.validate_gate.wait()
        if self.validate_error is not None:
            raise self.validate_error

    async def probe_local_queues(self) -> None:
        """Always prove LocalQueue access."""

    async def shutdown(self) -> None:
        self.shutdowns += 1
        if self.shutdown_error is not None:
            raise self.shutdown_error

    async def read_platform(self) -> PlatformObservation:
        return PlatformObservation("cluster-a", {"cpu": "1"}, {"cpu": "0"}, 0, datetime.now(UTC))

    async def read_user(self, username: str) -> UserObservation:
        return UserObservation(username, {"cpu": "0"}, 0, datetime.now(UTC))

    async def read_community(self, community: str) -> CommunityObservation:
        return CommunityObservation(community, {"cpu": "0"}, 0, datetime.now(UTC))


class Resource:
    """A session, usage, or efficiency fake that records its lifecycle."""

    name = "session"

    def __init__(self, *, error: BaseException | None = None) -> None:
        self.error = error
        self.startups = 0
        self.shutdowns = 0

    async def startup(self) -> None:
        self.startups += 1
        if self.error is not None:
            raise self.error

    async def shutdown(self) -> None:
        self.shutdowns += 1

    async def read_session(self, session_id: str) -> SessionObservation:
        now = datetime.now(UTC)
        return SessionObservation(session_id, {"cpu": "1"}, 1, now, now, now, False)

    async def read_session_usage(self, observation) -> SessionUsageObservation:
        return SessionUsageObservation({}, datetime.now(UTC))


class Cache(FakeCacheCoordinator):
    """A cache fake whose ping and shutdown can fail."""

    def __init__(self, surface: str, events: list[str] | None = None) -> None:
        super().__init__(policy=FRESHNESS_POLICIES[surface])
        self.ping_error: BaseException | None = None
        self.shutdown_error: BaseException | None = None
        self.pings = 0
        self.events = events

    async def ping(self) -> None:
        self.pings += 1
        if self.ping_error is not None:
            raise self.ping_error

    async def shutdown(self) -> None:
        if self.events is not None:
            self.events.append("cache")
        if self.shutdown_error is not None:
            raise self.shutdown_error


class Recorder(MetricsRecorder):
    def __init__(self) -> None:
        self.lifecycle: list[tuple[str, str]] = []
        self.readiness: list[bool] = []

    def record_lifecycle(self, *, operation: str, outcome: str, seconds: float) -> None:
        self.lifecycle.append((operation, outcome))

    def record_readiness(self, ready: bool) -> None:
        self.readiness.append(ready)


def _runtime(
    kueue: Kueue | None = None,
    *,
    session: Resource | None = None,
    efficiency: Resource | None = None,
    recorder: MetricsRecorder | None = None,
    events: list[str] | None = None,
    redis=None,
) -> tuple[MetricsRuntime, dict[str, Cache]]:
    kueue = kueue or Kueue()
    session = session or Resource()
    caches = {
        surface: Cache(surface, events) for surface in ("platform", "user", "community", "session")
    }
    service = MetricsService(
        platform_name="canfar",
        caches=caches,
        identity=lambda kind, subject: CacheIdentity(kind, subject, "cluster-a", "kueue"),
        loader=SnapshotLoader(kueue=kueue, session=session, usage=Resource()),
    )
    runtime = MetricsRuntime(
        _settings(),
        kueue=kueue,
        session=session,
        usage=Resource(),
        service=service,
        efficiency=efficiency,  # type: ignore[arg-type]
        redis=redis,
        telemetry=recorder,
    )
    return runtime, caches


@pytest.mark.anyio
async def test_start_is_idempotent_latches_readiness_and_shutdown_is_terminal(
    caplog: pytest.LogCaptureFixture,
) -> None:
    caplog.set_level(logging.INFO, logger="metrics.core.runtime")
    recorder = Recorder()
    kueue = Kueue()
    runtime, caches = _runtime(kueue, recorder=recorder)

    assert await runtime.check_readiness() is False  # not started
    await runtime.start()
    await runtime.start()
    assert runtime.ready and kueue.validations == 1
    assert recorder.lifecycle == [("startup", "ok")]
    assert all(cache.pings == 2 for cache in caches.values())  # startup and validation
    assert "metrics runtime ready" in caplog.text

    await runtime.shutdown()
    await runtime.shutdown()
    assert not runtime.ready and kueue.shutdowns == 1
    assert recorder.readiness == [False, True, False]
    assert recorder.lifecycle[-1] == ("shutdown", "ok")
    assert await runtime.check_readiness() is False
    with pytest.raises(RuntimeStartupError, match="already been shut down"):
        await runtime.start()


@pytest.mark.anyio
async def test_redis_outage_at_startup_is_fatal_and_releases_resources(
    caplog: pytest.LogCaptureFixture,
) -> None:
    kueue = Kueue()
    runtime, caches = _runtime(kueue)
    caches["user"].ping_error = RedisUnavailable("redis down")

    with pytest.raises(RuntimeStartupError, match="Redis"):
        await runtime.start()
    assert kueue.validations == 0 and kueue.shutdowns == 1
    assert "Redis is unavailable at startup error=RedisUnavailable" in caplog.text


@pytest.mark.anyio
async def test_platform_failure_leaves_the_runtime_unready_until_one_validation_passes(
    caplog: pytest.LogCaptureFixture,
) -> None:
    kueue = Kueue(validate_error=ProviderUnavailableError("clusterqueues forbidden"))
    recorder = Recorder()
    runtime, _caches = _runtime(kueue, recorder=recorder)

    await runtime.start()
    assert not runtime.ready and recorder.lifecycle == [("startup", "degraded")]
    assert "dependency validation failed error=ProviderUnavailableError" in caplog.text
    assert await runtime.check_readiness() is False

    kueue.validate_error = None
    assert await runtime.check_readiness() is True
    kueue.validate_error = ProviderUnavailableError("later outage")
    assert await runtime.check_readiness() is True  # latched: no further validation
    assert kueue.validations == 3
    await runtime.shutdown()


@pytest.mark.anyio
async def test_concurrent_readiness_checks_share_one_validation() -> None:
    gate = asyncio.Event()
    kueue = Kueue(validate_error=ProviderUnavailableError("down"))
    runtime, _caches = _runtime(kueue)
    await runtime.start()

    kueue.validate_error = None
    kueue.validate_gate = gate
    checks = [asyncio.create_task(runtime.check_readiness()) for _ in range(20)]
    await asyncio.sleep(0.01)
    gate.set()
    assert await asyncio.gather(*checks) == [True] * 20
    assert kueue.validations == 2
    await runtime.shutdown()


@pytest.mark.anyio
async def test_redis_failure_during_validation_keeps_the_runtime_unready() -> None:
    kueue = Kueue(validate_error=ProviderUnavailableError("down"))
    runtime, caches = _runtime(kueue)
    await runtime.start()
    kueue.validate_error = None
    caches["platform"].ping_error = RedisUnavailable("redis down")
    assert await runtime.check_readiness() is False
    caches["platform"].ping_error = None
    assert await runtime.check_readiness() is True
    await runtime.shutdown()


@pytest.mark.anyio
async def test_optional_and_secondary_probe_failures_never_gate_readiness(
    caplog: pytest.LogCaptureFixture,
) -> None:
    efficiency = Resource(error=RuntimeError("mimir unavailable"))
    session = Resource(error=ProviderUnavailableError("jobs forbidden"))
    runtime, _caches = _runtime(session=session, efficiency=efficiency)

    await runtime.start()
    assert runtime.ready and efficiency.startups == 1 and session.startups == 1
    assert "PromQL efficiency access could not be verified" in caplog.text
    assert "Session Job access could not be verified" in caplog.text
    await runtime.shutdown()
    assert efficiency.shutdowns == 1 and session.shutdowns == 1


@pytest.mark.anyio
async def test_start_cancellation_closes_resources_and_propagates() -> None:
    gate = asyncio.Event()
    kueue = Kueue(validate_gate=gate)
    runtime, _caches = _runtime(kueue)
    task = asyncio.create_task(runtime.start())
    await kueue.validate_entered.wait()
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert kueue.shutdowns == 1 and not runtime.ready


class Redis:
    def __init__(self, events: list[str], error: BaseException | None = None) -> None:
        self.events = events
        self.error = error

    async def aclose(self) -> None:
        self.events.append("redis")
        if self.error is not None:
            raise self.error


@pytest.mark.anyio
async def test_shutdown_closes_caches_then_providers_then_redis_despite_failures() -> None:
    events: list[str] = []

    class Ordered(Kueue):
        async def shutdown(self) -> None:
            events.append("kueue")
            raise RuntimeError("provider close failed")

    recorder = Recorder()
    runtime, caches = _runtime(
        Ordered(), recorder=recorder, events=events, redis=Redis(events, RuntimeError("x"))
    )
    caches["platform"].shutdown_error = RuntimeError("cache close failed")
    await runtime.start()
    await runtime.shutdown()

    assert events == ["cache", "cache", "cache", "cache", "kueue", "redis"]
    assert recorder.lifecycle[-1] == ("shutdown", "error")


@pytest.mark.anyio
async def test_shutdown_raises_cancellation_only_after_every_step() -> None:
    events: list[str] = []
    runtime, caches = _runtime(events=events, redis=Redis(events))
    caches["user"].shutdown_error = asyncio.CancelledError()
    await runtime.start()
    with pytest.raises(asyncio.CancelledError):
        await runtime.shutdown()
    assert events == ["cache", "cache", "cache", "cache", "redis"]


@pytest.mark.anyio
@pytest.mark.parametrize(
    ("error", "outcome"),
    [(None, "ok"), (RuntimeError("x"), "error"), (asyncio.CancelledError(), "cancelled")],
    ids=["ok", "error", "cancelled"],
)
async def test_close_all_reports_each_outcome_without_skipping_peers(
    error: BaseException | None, outcome: str
) -> None:
    calls: list[str] = []

    async def first() -> None:
        calls.append("first")
        if error is not None:
            raise error

    async def second() -> None:
        calls.append("second")

    actual, cancellation = await runtime_module._close_all((first, second))  # noqa: SLF001
    assert actual == outcome and calls == ["first", "second"]
    assert (cancellation is not None) is (outcome == "cancelled")
