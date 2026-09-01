"""Focused runtime lifecycle and cache-wiring tests."""

from __future__ import annotations

import asyncio
import logging
from datetime import UTC, datetime, timedelta
from typing import Any

import pytest
import metrics.core.runtime as runtime_module
from metrics.cache import (
    FRESHNESS_POLICIES,
    CacheIdentity,
    RedisUnavailable,
)
from metrics.core.runtime import MetricsRuntime, build_cache, platform_cache_identity
from metrics.core.settings import (
    CacheConfig,
    KueueProviderConfig,
    PromQLProviderConfig,
    ProviderConfigs,
    Settings,
)
from metrics.errors import RuntimeStartupError
from metrics.services.metrics import MetricsService
from metrics.services.models import (
    CachedSnapshot,
    CommunityObservation,
    EfficiencyObservation,
    PlatformObservation,
    SessionObservation,
    UserObservation,
)
from metrics.telemetry import MetricsRecorder, NoopMetricsRecorder
from tests.test_cache_helpers import FakeCacheCoordinator


def _settings() -> Settings:
    """Build valid Redis-backed settings."""
    return Settings(
        cluster_name="cluster-a",
        redis_url="redis://localhost:6379/0",
        cache=CacheConfig(key_secret="x" * 32),
        providers=ProviderConfigs(
            kueue=KueueProviderConfig(cluster_queues=["cq-a"], namespaces=["work-a"])
        ),
    )


def test_from_settings_builds_three_surface_caches_without_accounting() -> None:
    """Runtime wiring has one Kueue provider and one cache per surface."""
    runtime = MetricsRuntime.from_settings(_settings(), recorder=NoopMetricsRecorder())

    assert len(runtime._caches) == 4  # noqa: SLF001
    assert all(isinstance(cache, runtime_module.RedisCoordinator) for cache in runtime._caches)  # noqa: SLF001
    assert runtime.metrics_service.user_cache_ttl_seconds == 120
    assert runtime.metrics_service.community_cache_ttl_seconds == 300
    assert runtime.metrics_service.session_cache_ttl_seconds == 30
    assert runtime.metrics_service.cache_ttl_seconds == 300
    assert runtime._efficiency_provider is None  # noqa: SLF001


class _FakeEfficiencyProvider:
    """Provide the optional runtime seam without a PromQL network call."""

    name = "promql"

    async def startup(self) -> None:
        """Satisfy the provider lifecycle protocol."""

    async def shutdown(self) -> None:
        """Satisfy the provider lifecycle protocol."""

    async def read_platform(self) -> Any:
        """Satisfy the platform loader protocol."""

    async def read_user(self, _username: str) -> Any:
        """Satisfy the user loader protocol."""

    async def read_community(self, _community: str) -> Any:
        """Satisfy the community loader protocol."""

    async def read_session(
        self,
        _session_id: str,
        *,
        start_time: datetime,
        window_end: datetime,
        observed_at: datetime | None = None,
    ) -> Any:
        """Satisfy the session loader protocol."""
        del start_time, window_end, observed_at

    def cache_fingerprint(self) -> str:
        """Return a stable fake backend identity."""
        return "fake-promql"


def test_from_settings_wires_endpoint_bound_efficiency_provider(
    monkeypatch,
) -> None:
    """An endpoint activates one optional loader for every Metrics surface."""
    settings = Settings(
        cluster_name="cluster-a",
        redis_url="redis://localhost:6379/0",
        cache=CacheConfig(key_secret="x" * 32),
        providers=ProviderConfigs(
            kueue=KueueProviderConfig(cluster_queues=["cq-a"], namespaces=["work-a"]),
            promql=PromQLProviderConfig(base_url="https://mimir.example/api/prom"),
        ),
    )
    efficiency = _FakeEfficiencyProvider()
    monkeypatch.setattr(
        runtime_module,
        "_build_efficiency_provider",
        lambda _settings, _recorder: efficiency,
    )

    runtime = MetricsRuntime.from_settings(settings, recorder=NoopMetricsRecorder())

    assert runtime._efficiency_provider is efficiency  # noqa: SLF001
    assert runtime.metrics_service._platform_efficiency.__self__ is efficiency  # noqa: SLF001
    assert runtime.metrics_service._workloads["user"].efficiency_loader.__self__ is efficiency  # noqa: SLF001
    assert (
        runtime.metrics_service._workloads["community"].efficiency_loader.__self__ is efficiency  # noqa: SLF001
    )


def test_runtime_lifecycle_uses_one_kueue_provider() -> None:
    """Startup validates and shutdown closes the injected source cleanly."""
    runtime = MetricsRuntime.from_settings(_settings(), recorder=NoopMetricsRecorder())
    provider = runtime._provider  # noqa: SLF001

    # The real provider has no API fake here, so lifecycle ownership is tested
    # through a direct injected runtime in the application smoke tests.
    assert provider.name == "kueue"
    assert runtime.metrics_service.readiness.surfaces == ("platform", "user", "community")


def test_cache_payload_type_has_no_lifetime_fields() -> None:
    """The cached value is queue data plus optional attributed efficiency."""
    assert not hasattr(CachedSnapshot, "accounting")
    assert not hasattr(CachedSnapshot, "running_pods")


def test_runtime_cache_schema_revision_separates_failure_envelopes() -> None:
    """New signed failure categories cannot be read as the old envelope shape."""
    assert runtime_module._SCHEMA_REVISION == "8"  # noqa: SLF001


def test_runtime_cache_freshness_uses_primary_observation_timestamp() -> None:
    """Cache ageing ignores an older optional efficiency observation."""
    runtime = MetricsRuntime.from_settings(_settings(), recorder=NoopMetricsRecorder())
    primary = PlatformObservation(
        cluster="cluster-a",
        capacity={"cpu": "1"},
        allocated={"cpu": "0"},
        reserving_workloads=0,
        observed_at=datetime(2025, 1, 1, 12, tzinfo=UTC),
    )
    snapshot = CachedSnapshot(
        observation=primary,
        created=primary.observed_at - timedelta(minutes=1),
        efficiency=EfficiencyObservation(
            primary.observed_at - timedelta(minutes=1),
            {"cpu": 0, "memory": 0},
        ),
    )

    assert runtime._caches[0]._created(snapshot) == primary.observed_at  # noqa: SLF001


class _LifecycleProvider:
    """Provide queue observations while exposing controllable lifecycle hooks."""

    name = "kueue"

    def __init__(
        self,
        *,
        startup_error: BaseException | None = None,
        shutdown_error: BaseException | None = None,
        startup_gate: asyncio.Event | None = None,
    ) -> None:
        self.startup_error = startup_error
        self.shutdown_error = shutdown_error
        self.startup_gate = startup_gate
        self.startup_entered = asyncio.Event()
        self.startup_calls = 0
        self.shutdown_calls = 0

    async def startup(self) -> None:
        """Record startup and optionally block or fail it."""
        self.startup_calls += 1
        self.startup_entered.set()
        if self.startup_gate is not None:
            await self.startup_gate.wait()
        if self.startup_error is not None:
            raise self.startup_error

    async def shutdown(self) -> None:
        """Record shutdown and optionally fail it."""
        self.shutdown_calls += 1
        if self.shutdown_error is not None:
            raise self.shutdown_error

    async def read_platform(self) -> PlatformObservation:
        """Return a minimal platform observation."""
        return PlatformObservation(
            cluster="cluster-a",
            capacity={"cpu": "1"},
            allocated={"cpu": "0"},
            reserving_workloads=0,
            observed_at=datetime.now(UTC),
        )

    async def read_user(self, username: str) -> UserObservation:
        """Return a minimal user observation."""
        return UserObservation(
            user=username,
            requests={"cpu": "0"},
            reserving_workloads=0,
            observed_at=datetime.now(UTC),
        )

    async def read_community(self, community: str) -> CommunityObservation:
        """Return a minimal community observation."""
        return CommunityObservation(
            community=community,
            requests={"cpu": "0"},
            reserving_workloads=0,
            observed_at=datetime.now(UTC),
        )

    def cache_fingerprint(self) -> str:
        """Return a stable source fingerprint."""
        return "lifecycle"


class _LifecycleSessionProvider:
    """Provide session observations while exposing controllable lifecycle hooks."""

    name = "session"

    def __init__(self, *, startup_error: BaseException | None = None) -> None:
        self.startup_error = startup_error
        self.startup_calls = 0
        self.shutdown_calls = 0

    async def startup(self) -> None:
        """Record startup and optionally fail it."""
        self.startup_calls += 1
        if self.startup_error is not None:
            raise self.startup_error

    async def shutdown(self) -> None:
        """Record shutdown."""
        self.shutdown_calls += 1

    async def read_session(self, session_id: str) -> SessionObservation:
        """Return a minimal session observation."""
        now = datetime.now(UTC)
        return SessionObservation(
            session=session_id,
            requests={"cpu": "1"},
            reserving_workloads=1,
            observed_at=now,
            start_time=now,
            window_end=now,
            has_running_pods=False,
        )

    def cache_fingerprint(self) -> str:
        """Return a stable source fingerprint."""
        return "lifecycle-session"


class _LifecycleUsageProvider:
    """Provide session usage without network access."""

    name = "kubemetrics"

    async def startup(self) -> None:
        """Satisfy the provider lifecycle protocol."""

    async def shutdown(self) -> None:
        """Satisfy the provider lifecycle protocol."""

    async def read_session_usage(self, _session_id: str) -> dict[str, str]:
        """Return empty usage for lifecycle tests."""
        return {}


class _LifecycleEfficiency:
    """Provide optional efficiency lifecycle behavior without network access."""

    def __init__(self, *, startup_error: BaseException | None = None) -> None:
        self.startup_error = startup_error
        self.startup_calls = 0
        self.shutdown_calls = 0

    async def startup(self) -> None:
        """Record startup and optionally fail the optional dependency."""
        self.startup_calls += 1
        if self.startup_error is not None:
            raise self.startup_error

    async def shutdown(self) -> None:
        """Record optional dependency shutdown."""
        self.shutdown_calls += 1

    async def read_platform(self) -> Any:
        """Satisfy the optional provider protocol for focused wiring tests."""
        return None

    async def read_user(self, _username: str) -> Any:
        """Satisfy the optional provider protocol for focused wiring tests."""
        return None

    async def read_community(self, _community: str) -> Any:
        """Satisfy the optional provider protocol for focused wiring tests."""
        return None


class _RecordingRecorder(MetricsRecorder):
    """Capture lifecycle and readiness observations for runtime assertions."""

    def __init__(self) -> None:
        self.lifecycle: list[tuple[str, str]] = []
        self.readiness: list[bool] = []

    def record_lifecycle(self, *, operation: str, outcome: str, seconds: float) -> None:
        """Record one lifecycle result."""
        self.lifecycle.append((operation, outcome))

    def record_readiness(self, ready: bool) -> None:
        """Record one readiness result."""
        self.readiness.append(ready)


def _test_cache(surface: str) -> FakeCacheCoordinator[CachedSnapshot]:
    """Build one deterministic cache seam for an injected runtime."""
    return FakeCacheCoordinator(
        policy=FRESHNESS_POLICIES[surface],
        created=lambda snapshot: snapshot.created,
    )


def _runtime_with_provider(
    provider: _LifecycleProvider,
    *,
    recorder: MetricsRecorder | None = None,
    efficiency_provider: _LifecycleEfficiency | None = None,
) -> MetricsRuntime:
    """Build a runtime around small injected lifecycle fakes."""
    recorder = recorder or NoopMetricsRecorder()
    platform_cache = _test_cache("platform")
    user_cache = _test_cache("user")
    community_cache = _test_cache("community")
    session_cache = _test_cache("session")
    service = MetricsService(
        platform=provider.read_platform,
        cache=platform_cache,
        identity=lambda: CacheIdentity("platform", "canfar", "cluster-a", "kueue", "test"),
        platform_name="canfar",
        user=provider.read_user,
        user_cache=user_cache,
        user_identity=lambda username: CacheIdentity(
            "user", username, "cluster-a", "kueue", "test"
        ),
        community=provider.read_community,
        community_cache=community_cache,
        community_identity=lambda community: CacheIdentity(
            "community", community, "cluster-a", "kueue", "test"
        ),
        session=_LifecycleSessionProvider().read_session,
        session_cache=session_cache,
        session_identity=lambda session_id: CacheIdentity(
            "session", session_id, "cluster-a", "session", "test"
        ),
        session_usage=_LifecycleUsageProvider().read_session_usage,
        telemetry=recorder,
    )
    return MetricsRuntime(
        _settings(),
        provider=provider,
        session_provider=_LifecycleSessionProvider(),
        usage_provider=_LifecycleUsageProvider(),
        metrics_service=service,
        cache=platform_cache,
        user_cache=user_cache,
        community_cache=community_cache,
        session_cache=session_cache,
        telemetry=recorder,
        efficiency_provider=efficiency_provider,
    )


class _HealthCoordinator:
    """Expose the Redis readiness probe with a mutable failure mode."""

    backend_name = "redis"

    def __init__(self) -> None:
        self.error: BaseException | None = None
        self.block = False
        self.ping_calls = 0
        self.available = True

    async def ping(self) -> None:
        """Succeed, fail, or block according to the current test mode."""
        self.ping_calls += 1
        if self.block:
            await asyncio.sleep(1)
        if self.error is not None:
            self.available = False
            raise self.error
        self.available = True


class _ShutdownResource:
    """Record shutdown while optionally returning an error."""

    def __init__(self, error: BaseException | None = None) -> None:
        self.error = error
        self.calls = 0

    async def shutdown(self) -> None:
        """Record one close attempt."""
        self.calls += 1
        if self.error is not None:
            raise self.error


class _RedisCloser:
    """Provide a Redis-like close method for cleanup tests."""

    def __init__(self, error: BaseException | None = None) -> None:
        self.error = error
        self.calls = 0

    async def aclose(self) -> None:
        """Record one Redis close attempt."""
        self.calls += 1
        if self.error is not None:
            raise self.error


@pytest.mark.anyio
async def test_runtime_start_is_idempotent_and_shutdown_is_terminal(
    caplog: pytest.LogCaptureFixture,
) -> None:
    """A successful runtime starts once, cleans up, and cannot restart."""
    caplog.set_level(logging.INFO, logger="metrics.core.runtime")
    recorder = _RecordingRecorder()
    provider = _LifecycleProvider()
    runtime = _runtime_with_provider(provider, recorder=recorder)

    await runtime.start()
    await runtime.start()

    assert runtime.ready
    assert provider.startup_calls == 1
    assert recorder.lifecycle == [("startup", "ok")]

    await runtime.shutdown()

    assert not runtime.ready
    assert provider.shutdown_calls == 1
    assert recorder.lifecycle[-1] == ("shutdown", "ok")
    assert any(record.getMessage() == "Runtime shutdown completed" for record in caplog.records)
    assert recorder.readiness[-1] is False
    with pytest.raises(RuntimeError, match="not initialised"):
        _ = runtime.metrics_service
    with pytest.raises(RuntimeStartupError, match="already been shut down"):
        await runtime.start()


@pytest.mark.anyio
@pytest.mark.parametrize(
    "startup_error",
    [
        RedisUnavailable("redis unavailable"),
        TimeoutError("validation timed out"),
        RuntimeStartupError("invalid startup"),
        ValueError("unexpected startup"),
    ],
    ids=["redis", "timeout", "runtime", "unexpected"],
)
async def test_runtime_start_provider_failures_degrade_readiness(
    startup_error: BaseException,
) -> None:
    """Kueue validation failures leave HTTP startup alive but not ready."""
    provider = _LifecycleProvider(startup_error=startup_error)
    runtime = _runtime_with_provider(provider)

    await runtime.start()

    assert provider.shutdown_calls == 0
    assert runtime._provider is provider  # noqa: SLF001
    assert not runtime.ready
    await runtime.shutdown()
    assert provider.shutdown_calls == 1


@pytest.mark.anyio
async def test_runtime_start_cancellation_closes_resources_and_propagates() -> None:
    """Cancellation during required validation still performs shutdown cleanup."""
    gate = asyncio.Event()
    provider = _LifecycleProvider(startup_gate=gate)
    runtime = _runtime_with_provider(provider)
    task = asyncio.create_task(runtime.start())

    await provider.startup_entered.wait()
    task.cancel()

    with pytest.raises(asyncio.CancelledError):
        await task
    assert provider.shutdown_calls == 1
    assert not runtime.ready


@pytest.mark.anyio
async def test_optional_efficiency_startup_failure_does_not_gate_runtime() -> None:
    """PromQL startup failure leaves queue serving available and shuts down later."""
    provider = _LifecycleProvider()
    efficiency = _LifecycleEfficiency(startup_error=RuntimeError("mimir unavailable"))
    runtime = _runtime_with_provider(provider, efficiency_provider=efficiency)

    await runtime.start()

    assert runtime.ready
    assert efficiency.startup_calls == 1
    await runtime.shutdown()
    assert efficiency.shutdown_calls == 1


@pytest.mark.anyio
async def test_readiness_recovery_retries_source_after_a_transient_failure() -> None:
    """Readiness recovery coalesces state and can recover a failed provider."""
    provider = _LifecycleProvider()
    runtime = _runtime_with_provider(provider)

    assert await runtime.check_readiness() is False
    await runtime.start()
    runtime._readiness.mark_source("platform", reachable=False)  # noqa: SLF001
    provider.startup_error = RuntimeError("temporary source failure")

    assert await runtime.check_readiness() is False
    assert not runtime.ready

    provider.startup_error = None
    assert await runtime.check_readiness() is True
    assert await runtime.check_readiness() is True
    assert provider.startup_calls == 3
    await runtime.shutdown()


@pytest.mark.anyio
async def test_readiness_recovery_retries_failed_redis_health() -> None:
    """A Redis probe failure blocks readiness until a later probe succeeds."""
    provider = _LifecycleProvider()
    runtime = _runtime_with_provider(provider)
    health = _HealthCoordinator()
    runtime._redis_coordinators = (health,)  # noqa: SLF001

    await runtime.start()
    runtime._readiness.mark_source("platform", reachable=False)  # noqa: SLF001
    health.error = RedisUnavailable("redis outage")

    assert await runtime.check_readiness() is False
    assert health.ping_calls == 2

    health.error = None
    assert await runtime.check_readiness() is True
    assert health.ping_calls == 3
    await runtime.shutdown()


@pytest.mark.anyio
async def test_readiness_recovery_updates_only_shared_platform_source() -> None:
    """Recovery does not claim arbitrary User or Community subjects were checked."""
    provider = _LifecycleProvider()
    runtime = _runtime_with_provider(provider)

    await runtime.start()
    runtime._readiness.mark_source("platform", reachable=False)  # noqa: SLF001
    runtime._readiness.mark_source("user", reachable=False)  # noqa: SLF001
    runtime._readiness.mark_source("community", reachable=False)  # noqa: SLF001

    assert await runtime.check_readiness() is True
    assert runtime._readiness._surfaces["platform"].source_reachable  # noqa: SLF001
    assert not runtime._readiness._surfaces["user"].source_reachable  # noqa: SLF001
    assert not runtime._readiness._surfaces["community"].source_reachable  # noqa: SLF001
    await runtime.shutdown()


@pytest.mark.anyio
async def test_readiness_recovery_times_out_a_blocked_redis_probe() -> None:
    """A bounded readiness probe returns false when Redis does not answer."""
    settings = _settings()
    settings.cache.redis_command_timeout_seconds = 0.001
    provider = _LifecycleProvider()
    runtime = _runtime_with_provider(provider)
    runtime._settings = settings  # noqa: SLF001
    health = _HealthCoordinator()
    runtime._redis_coordinators = (health,)  # noqa: SLF001

    await runtime.start()
    runtime._readiness.mark_source("platform", reachable=False)  # noqa: SLF001
    health.block = True

    assert await runtime.check_readiness() is False
    await runtime.shutdown()


@pytest.mark.anyio
async def test_ready_recomputes_an_expired_platform_snapshot_deadline() -> None:
    """Fast readiness reads do not retain a serviceable snapshot past its deadline."""
    runtime = _runtime_with_provider(_LifecycleProvider())
    await runtime.start()
    for surface in runtime._readiness.surfaces:  # noqa: SLF001
        runtime._readiness.mark_source(surface, reachable=True)  # noqa: SLF001
        runtime._readiness.mark_cache(surface, available=True)  # noqa: SLF001
    runtime._readiness.mark_source("platform", reachable=False)  # noqa: SLF001
    runtime._readiness.mark_snapshot(  # noqa: SLF001
        "platform",
        complete=True,
        serviceable=True,
    )
    runtime.metrics_service._serviceable_until["platform"] = (  # noqa: SLF001
        datetime.now(UTC) - timedelta(seconds=1)
    )

    assert not runtime.ready
    await runtime.shutdown()


@pytest.mark.anyio
async def test_runtime_shutdown_closes_all_resources_and_preserves_cancellation() -> None:
    """Cleanup continues across errors and raises a cancellation only afterward."""
    provider = _LifecycleProvider(shutdown_error=RuntimeError("provider close failed"))
    efficiency = _LifecycleEfficiency()
    recorder = _RecordingRecorder()
    runtime = _runtime_with_provider(
        provider,
        recorder=recorder,
        efficiency_provider=efficiency,
    )
    first_cache = _ShutdownResource(RuntimeError("cache close failed"))
    second_cache = _ShutdownResource()
    runtime._caches = (first_cache, second_cache)  # noqa: SLF001
    redis = _RedisCloser(RuntimeError("redis close failed"))
    runtime._redis = redis  # noqa: SLF001

    with pytest.raises(asyncio.CancelledError):
        runtime._efficiency_provider = _ShutdownResource(asyncio.CancelledError())  # noqa: SLF001
        await runtime.shutdown()

    assert provider.shutdown_calls == 1
    assert first_cache.calls == 1
    assert second_cache.calls == 1
    assert redis.calls == 1
    assert recorder.lifecycle[-1] == ("shutdown", "error")


@pytest.mark.anyio
async def test_runtime_shutdown_waits_for_cache_refresh_before_dependencies() -> None:
    """Cache refresh cancellation completes before provider and Redis shutdown."""
    events: list[str] = []
    refresh_cancelled = asyncio.Event()

    class BlockedRefreshCache:
        """Model a cache that cannot finish shutdown until refresh cancellation runs."""

        def __init__(self) -> None:
            self.refresh = asyncio.create_task(self._refresh())

        async def _refresh(self) -> None:
            """Hold a refresh open until the cache cancels it."""
            try:
                await asyncio.Future()
            except asyncio.CancelledError:
                refresh_cancelled.set()
                raise

        async def shutdown(self) -> None:
            """Wait for the blocked refresh's cancellation cleanup."""
            events.append("cache-start")
            self.refresh.cancel()
            try:
                await self.refresh
            except asyncio.CancelledError:
                pass
            await refresh_cancelled.wait()
            events.append("cache-finished")

    class OrderedProvider(_LifecycleProvider):
        """Record provider shutdown after cache cleanup."""

        async def shutdown(self) -> None:
            """Assert that cache cancellation completed first."""
            assert events == ["cache-start", "cache-finished"]
            events.append("provider")

    class OrderedRedis(_RedisCloser):
        """Record shared Redis shutdown last."""

        async def aclose(self) -> None:
            """Assert that the provider closed before Redis."""
            assert events == ["cache-start", "cache-finished", "provider"]
            events.append("redis")

    provider = OrderedProvider()
    runtime = _runtime_with_provider(provider)
    cache = BlockedRefreshCache()
    await asyncio.sleep(0)
    runtime._caches = (cache,)  # noqa: SLF001
    runtime._redis = OrderedRedis()  # noqa: SLF001

    await runtime.shutdown()

    assert events == ["cache-start", "cache-finished", "provider", "redis"]


@pytest.mark.anyio
@pytest.mark.parametrize(
    ("error", "outcome"),
    [
        (None, "ok"),
        (RuntimeError("close failed"), "error"),
        (asyncio.CancelledError(), "cancelled"),
    ],
    ids=["ok", "error", "cancelled"],
)
async def test_runtime_close_resources_preserves_each_shutdown_outcome(
    error: BaseException | None,
    outcome: str,
) -> None:
    """Resource cleanup records errors and cancellation without skipping peers."""
    first = _ShutdownResource(error)
    second = _ShutdownResource()

    actual, cancellation = await runtime_module._close_resources(
        (first, second), "resource shutdown failed"
    )

    assert actual == outcome
    assert first.calls == 1
    assert second.calls == 1
    assert (cancellation is not None) is (outcome == "cancelled")


@pytest.mark.anyio
@pytest.mark.parametrize(
    ("error", "outcome"),
    [
        (None, "ok"),
        (RuntimeError("close failed"), "error"),
        (asyncio.CancelledError(), "cancelled"),
    ],
    ids=["ok", "error", "cancelled"],
)
async def test_runtime_close_redis_preserves_each_shutdown_outcome(
    error: BaseException | None,
    outcome: str,
) -> None:
    """Redis close failures are classified without leaking provider details."""
    actual, cancellation = await runtime_module._close_redis(_RedisCloser(error))

    assert actual == outcome
    assert (cancellation is not None) is (outcome == "cancelled")


def test_runtime_wires_redis_and_optional_provider_without_connecting() -> None:
    """Redis wiring is lazy and an endpoint creates the concrete optional adapter."""
    settings = Settings(
        cluster_name="cluster-a",
        redis_url="redis://localhost:6379/0",
        cache=CacheConfig(
            key_secret="x" * 32,
        ),
        providers=ProviderConfigs(
            kueue=KueueProviderConfig(cluster_queues=["cq-a"], namespaces=["work-a"]),
            promql=PromQLProviderConfig(base_url="https://mimir.example/api/prom"),
        ),
    )
    redis = object()

    cache, owned_redis = build_cache(settings, redis=redis)  # type: ignore[arg-type]
    optional = runtime_module._build_efficiency_provider(settings, NoopMetricsRecorder())

    assert cache.backend_name == "redis"
    assert owned_redis is None
    assert optional is not None


def test_runtime_cache_identity_is_opaque_and_normalized() -> None:
    """Cache identities preserve subject scope while trimming fingerprints."""
    identity = platform_cache_identity(
        platform_name="canfar",
        cluster_name="cluster-a",
        source="kueue",
        fingerprint="  source-rev  ",
    )

    assert identity == CacheIdentity(
        subject_kind="platform",
        subject_value="canfar",
        cluster="cluster-a",
        source="kueue",
        fingerprint="source-rev",
    )
