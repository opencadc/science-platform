"""Snapshot assembly and service error mapping at the service seam."""

from __future__ import annotations

import asyncio
from datetime import UTC, datetime, timedelta

import pytest

from metrics.cache import (
    FRESHNESS_POLICIES,
    CacheIdentity,
    CacheNotFound,
    CacheResult,
    CacheUnavailable,
)
from metrics.cache.coordination import _FILL_DEADLINE
from metrics.errors import AppError, ProviderExecutionError, ProviderUnavailableError
from metrics.errors import SubjectNotFoundError
from metrics.services.metrics import MetricsService
from metrics.services.models import (
    CachedSnapshot,
    CommunityObservation,
    EfficiencyObservation,
    MetricsSubject,
    PlatformObservation,
    SessionObservation,
    SessionUsageObservation,
    UserObservation,
)
from metrics.services.snapshots import SnapshotLoader
from metrics.telemetry import MetricsRecorder
from tests.test_cache_helpers import FakeCacheCoordinator

pytestmark = pytest.mark.anyio
NOW = datetime(2025, 1, 1, tzinfo=UTC)


class Kueue:
    """Return deterministic queue observations or configured failures."""

    def __init__(self, *, reserving: int = 2, error: BaseException | None = None) -> None:
        self.reserving = reserving
        self.error = error
        self.calls: list[str] = []

    async def read_platform(self) -> PlatformObservation:
        self.calls.append("platform")
        if self.error is not None:
            raise self.error
        return PlatformObservation("cluster-a", {"cpu": "4"}, {"cpu": "1"}, self.reserving, NOW)

    async def read_user(self, username: str) -> UserObservation:
        self.calls.append(f"user:{username}")
        if self.error is not None:
            raise self.error
        return UserObservation(username, {"cpu": "1"}, self.reserving, NOW)

    async def read_community(self, community: str) -> CommunityObservation:
        self.calls.append(f"community:{community}")
        if self.error is not None:
            raise self.error
        return CommunityObservation(community, {"cpu": "2"}, self.reserving, NOW)


class Sessions:
    """Return one deterministic Session observation."""

    def __init__(
        self,
        *,
        running: bool = True,
        pods_reachable: bool = True,
        age: timedelta = timedelta(minutes=10),
        error: BaseException | None = None,
    ) -> None:
        self.running = running
        self.pods_reachable = pods_reachable
        self.age = age
        self.error = error

    async def read_session(self, session_id: str) -> SessionObservation:
        if self.error is not None:
            raise self.error
        return SessionObservation(
            session=session_id,
            requests={"cpu": "1", "memory": "1Gi"},
            reserving_workloads=1,
            observed_at=NOW,
            start_time=NOW - self.age,
            window_end=NOW,
            has_running_pods=self.running,
            pods_reachable=self.pods_reachable,
            running_pods_by_namespace={"work-a": frozenset({"pod"})} if self.running else {},
        )


class Usage:
    """Return live usage or fail."""

    def __init__(self, *, error: BaseException | None = None, delay: float = 0) -> None:
        self.error = error
        self.delay = delay
        self.calls = 0

    async def read_session_usage(self, observation: SessionObservation) -> SessionUsageObservation:
        self.calls += 1
        await asyncio.sleep(self.delay)
        if self.error is not None:
            raise self.error
        return SessionUsageObservation({"cpu": "0.5"}, NOW - timedelta(seconds=30))


class Efficiency:
    """Return efficiency, fail, or stall, and record what was asked."""

    def __init__(self, *, error: BaseException | None = None, delay: float = 0) -> None:
        self.error = error
        self.delay = delay
        self.calls: list[str] = []

    async def _answer(self, what: str) -> EfficiencyObservation:
        self.calls.append(what)
        await asyncio.sleep(self.delay)
        if self.error is not None:
            raise self.error
        return EfficiencyObservation(NOW - timedelta(minutes=1), {"cpu": "0.5", "memory": "0.25"})

    async def read_platform(self) -> EfficiencyObservation:
        return await self._answer("platform")

    async def read_user(self, username: str) -> EfficiencyObservation:
        return await self._answer(f"user:{username}")

    async def read_community(self, community: str) -> EfficiencyObservation:
        return await self._answer(f"community:{community}")

    async def read_session(
        self, session_id: str, *, start_time: datetime, window_end: datetime
    ) -> EfficiencyObservation:
        return await self._answer(f"session:{session_id}:{(window_end - start_time).seconds}")


class Recorder(MetricsRecorder):
    def __init__(self) -> None:
        self.providers: list[tuple[str, str, str]] = []
        self.compute: list[tuple[str, str]] = []

    def record_provider_duration(self, *, provider, scope, status, seconds) -> None:
        self.providers.append((provider, scope, status))

    def record_compute_duration(self, *, seconds, status, scope) -> None:
        self.compute.append((scope, status))


def _loader(
    *,
    kueue: Kueue | None = None,
    sessions: Sessions | None = None,
    usage: Usage | None = None,
    efficiency: Efficiency | None = None,
    telemetry: MetricsRecorder | None = None,
) -> SnapshotLoader:
    return SnapshotLoader(
        kueue=kueue or Kueue(),
        session=sessions or Sessions(),
        usage=usage or Usage(),
        efficiency=efficiency,
        usage_timeout_seconds=0.2,
        efficiency_timeout_seconds=0.2,
        telemetry=telemetry,
    )


# ------------------------------------------------------------- Kueue surfaces


async def test_platform_without_efficiency_is_the_primary_observation() -> None:
    snapshot = await _loader().platform()
    assert snapshot == CachedSnapshot(snapshot.observation, created=NOW)
    assert not snapshot.partial and snapshot.efficiency is None


async def test_efficiency_is_added_after_the_primary_read_with_a_conservative_time() -> None:
    efficiency = Efficiency()
    kueue = Kueue()
    snapshot = await _loader(kueue=kueue, efficiency=efficiency).platform()
    assert kueue.calls == ["platform"] and efficiency.calls == ["platform"]
    assert snapshot.efficiency is not None and not snapshot.partial
    assert snapshot.created == NOW - timedelta(minutes=1)


@pytest.mark.parametrize("surface", ["platform", "user", "community"])
async def test_idle_subjects_do_not_query_efficiency(surface: str) -> None:
    efficiency = Efficiency()
    loader = _loader(kueue=Kueue(reserving=0), efficiency=efficiency)
    read = {
        "platform": loader.platform,
        "user": lambda: loader.user("bob"),
        "community": lambda: loader.community("astro"),
    }[surface]
    snapshot = await read()
    assert efficiency.calls == [] and snapshot.efficiency is None and not snapshot.partial


@pytest.mark.parametrize(
    "efficiency", [Efficiency(error=ProviderExecutionError("empty vector")), Efficiency(delay=1)]
)
async def test_failed_or_slow_efficiency_keeps_queue_data_as_partial(
    efficiency: Efficiency, caplog: pytest.LogCaptureFixture
) -> None:
    snapshot = await _loader(efficiency=efficiency).user("bob")
    assert snapshot.partial and snapshot.efficiency is None
    assert snapshot.observation.requests == {"cpu": "1"}
    assert "optional efficiency unavailable scope=user" in caplog.text
    assert "bob" not in caplog.text


async def test_unknown_user_never_reaches_efficiency() -> None:
    efficiency = Efficiency()
    loader = _loader(kueue=Kueue(error=SubjectNotFoundError("no queue")), efficiency=efficiency)
    with pytest.raises(CacheNotFound):
        await loader.user("ghost")
    assert efficiency.calls == []


@pytest.mark.parametrize(
    "error", [ProviderUnavailableError("403"), ProviderExecutionError("bad payload")]
)
async def test_source_failures_become_source_unavailable_with_the_cause_chained(
    error: Exception,
) -> None:
    recorder = Recorder()
    with pytest.raises(CacheUnavailable) as failure:
        await _loader(kueue=Kueue(error=error), telemetry=recorder).community("astro")
    assert failure.value.cache_available is True and failure.value.source_reachable is False
    assert failure.value.__cause__ is error
    assert recorder.providers == [("kueue", "community", "error")]


async def test_optional_reads_stop_at_the_fill_deadline(caplog: pytest.LogCaptureFixture) -> None:
    efficiency = Efficiency()
    token = _FILL_DEADLINE.set(asyncio.get_running_loop().time() + 0.3)  # < publish reserve
    try:
        snapshot = await _loader(efficiency=efficiency).platform()
    finally:
        _FILL_DEADLINE.reset(token)
    assert snapshot.partial and efficiency.calls == []
    assert "fill deadline reached" in caplog.text


async def test_optional_cancellation_propagates() -> None:
    efficiency = Efficiency(delay=5)
    task = asyncio.create_task(_loader(efficiency=efficiency).platform())
    await asyncio.sleep(0.01)
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task


# ------------------------------------------------------------------- Session


async def test_session_reads_usage_and_efficiency_together() -> None:
    usage = Usage(delay=0.1)
    efficiency = Efficiency(delay=0.1)
    started = asyncio.get_running_loop().time()
    snapshot = await _loader(usage=usage, efficiency=efficiency).session("s1")
    assert asyncio.get_running_loop().time() - started < 0.19
    assert snapshot.usage == {"cpu": "0.5"} and snapshot.efficiency is not None
    assert not snapshot.partial
    # Usage ages the report; duration efficiency does not.
    assert snapshot.created == NOW - timedelta(seconds=30)
    assert efficiency.calls == ["session:s1:600"]


async def test_session_without_running_pods_skips_usage() -> None:
    usage = Usage()
    snapshot = await _loader(sessions=Sessions(running=False), usage=usage).session("s1")
    assert usage.calls == 0 and snapshot.usage is None and not snapshot.partial


async def test_session_usage_failure_is_partial_only_with_running_pods() -> None:
    snapshot = await _loader(usage=Usage(error=ProviderUnavailableError("down"))).session("s1")
    assert snapshot.partial and snapshot.usage is None


async def test_session_with_unreachable_pods_is_partial() -> None:
    snapshot = await _loader(sessions=Sessions(running=False, pods_reachable=False)).session("s1")
    assert snapshot.partial


async def test_young_sessions_skip_efficiency_instead_of_reporting_partial() -> None:
    efficiency = Efficiency()
    snapshot = await _loader(
        sessions=Sessions(age=timedelta(seconds=20)), efficiency=efficiency
    ).session("s1")
    assert efficiency.calls == [] and not snapshot.partial


async def test_session_empty_usage_is_omitted() -> None:
    class Empty(Usage):
        async def read_session_usage(self, observation):
            return SessionUsageObservation({}, NOW)

    snapshot = await _loader(usage=Empty()).session("s1")
    assert snapshot.usage is None and not snapshot.partial


# ------------------------------------------------------------------- service


def _service(
    cache=None, *, telemetry: MetricsRecorder | None = None, loader: SnapshotLoader | None = None
) -> MetricsService:
    caches = {
        surface: FakeCacheCoordinator(policy=policy)
        for surface, policy in FRESHNESS_POLICIES.items()
    }
    if cache is not None:
        caches["user"] = cache
    return MetricsService(
        platform_name="canfar",
        caches=caches,
        identity=lambda kind, subject: CacheIdentity(kind, subject, "cluster-a", "kueue"),
        loader=loader or _loader(),
        telemetry=telemetry,
    )


async def test_service_reports_cache_provenance_and_the_fresh_window() -> None:
    service = _service()
    first = await service.get(MetricsSubject("user", "bob"))
    second = await service.get(MetricsSubject("user", "bob"))
    assert not first.cached and second.cached
    assert second.fresh_seconds == 120 and not second.stale and second.cache_available


async def test_platform_subject_must_match_the_configured_name() -> None:
    recorder = Recorder()
    with pytest.raises(AppError) as missing:
        await _service(telemetry=recorder).get(MetricsSubject("platform", "other"))
    assert (missing.value.status_code, missing.value.code) == (404, "platform_not_found")
    assert recorder.compute == [("platform", "not_found")]


class Failing(FakeCacheCoordinator):
    def __init__(self, error: Exception) -> None:
        super().__init__(policy=FRESHNESS_POLICIES["user"])
        self.error = error

    async def get_or_fill(self, identity, fill) -> CacheResult:
        raise self.error


@pytest.mark.parametrize(
    ("error", "status", "code", "retry"),
    [
        (CacheNotFound(), 404, "user_not_found", None),
        (CacheUnavailable("redis down"), 503, "metrics_cache_unavailable", 1),
        (
            CacheUnavailable("source", cache_available=True, source_reachable=False),
            503,
            "user_metrics_unavailable",
            None,
        ),
        (
            CacheUnavailable("follower timeout", cache_available=True),
            503,
            "user_metrics_unavailable",
            None,
        ),
    ],
)
async def test_cache_outcomes_map_to_sanitized_http_errors(
    error: Exception, status: int, code: str, retry: int | None
) -> None:
    with pytest.raises(AppError) as mapped:
        await _service(Failing(error)).get(MetricsSubject("user", "bob"))
    assert (mapped.value.status_code, mapped.value.code, mapped.value.retry_after) == (
        status,
        code,
        retry,
    )


async def test_unexpected_failures_propagate_and_are_recorded() -> None:
    recorder = Recorder()
    with pytest.raises(RuntimeError):
        await _service(Failing(RuntimeError("bug")), telemetry=recorder).get(
            MetricsSubject("user", "bob")
        )
    assert recorder.compute == [("user", "error")]


def test_efficiency_observation_requires_cpu_and_memory_together() -> None:
    with pytest.raises(ValueError):
        EfficiencyObservation(NOW, {"cpu": "1"})
