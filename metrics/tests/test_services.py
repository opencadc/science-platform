"""Focused service tests for optional efficiency and primary-source cancellation."""

from __future__ import annotations

import asyncio
from datetime import UTC, datetime, timedelta

from decimal import Decimal

import pytest

from metrics.cache import CacheFillTimeout, CacheIdentity, CacheUnavailable, FRESHNESS_POLICIES
from metrics.errors import AppError, ProviderExecutionError, ProviderUnavailableError
from metrics.services.metrics import MetricsService
from metrics.services.models import (
    CommunityObservation,
    EfficiencyObservation,
    MetricsSubject,
    PlatformObservation,
    UserObservation,
)
from metrics.telemetry import MetricsRecorder
from tests.test_cache_helpers import FakeCacheCoordinator


NOW = datetime(2025, 1, 1, tzinfo=UTC)


def _service(
    *,
    platform,
    platform_efficiency=None,
    platform_cache=None,
    telemetry=None,
    user_loader=None,
    user_cache=None,
    community_loader=None,
    community_cache=None,
) -> MetricsService:
    """Build one service at the public Metrics subject seam."""

    async def default_user(username: str) -> UserObservation:
        return UserObservation(username, {"cpu": "1"}, 0, NOW)

    async def default_community(name: str) -> CommunityObservation:
        return CommunityObservation(name, {"cpu": "1"}, 0, NOW)

    return MetricsService(
        platform=platform,
        cache=platform_cache
        or FakeCacheCoordinator(
            policy=FRESHNESS_POLICIES["platform"],
            created=lambda observation: observation.created,
        ),
        identity=lambda: CacheIdentity("platform", "canfar", "cluster-a", "kueue"),
        platform_name="canfar",
        platform_efficiency=platform_efficiency,
        user=user_loader or default_user,
        user_cache=user_cache
        or FakeCacheCoordinator(
            policy=FRESHNESS_POLICIES["user"],
            created=lambda observation: observation.created,
        ),
        user_identity=lambda username: CacheIdentity("user", username, "cluster-a", "kueue"),
        community=community_loader or default_community,
        community_cache=community_cache
        or FakeCacheCoordinator(
            policy=FRESHNESS_POLICIES["community"],
            created=lambda observation: observation.created,
        ),
        community_identity=lambda name: CacheIdentity("community", name, "cluster-a", "kueue"),
        telemetry=telemetry,
        efficiency_timeout_seconds=0.2,
    )


async def _platform() -> PlatformObservation:
    """Return one deterministic Kueue observation."""
    return PlatformObservation("cluster-a", {"cpu": "1"}, {"cpu": "0"}, 0, NOW)


async def _efficiency() -> EfficiencyObservation:
    """Return one deterministic efficiency observation."""
    return EfficiencyObservation(NOW, {"cpu": 0, "memory": 0})


@pytest.mark.anyio
async def test_accepted_old_efficiency_keeps_conservative_report_timestamp() -> None:
    """A valid report still exposes the older optional observation time."""
    old_efficiency_time = NOW - timedelta(minutes=1)

    async def old_efficiency() -> EfficiencyObservation:
        return EfficiencyObservation(old_efficiency_time, {"cpu": 0, "memory": 0})

    result = await _service(platform=_platform, platform_efficiency=old_efficiency).get(
        MetricsSubject("platform", "canfar")
    )

    assert result.ready
    assert result.efficiency is not None
    assert result.created == old_efficiency_time


@pytest.mark.anyio
async def test_ordinary_optional_exception_returns_partial_queue_data() -> None:
    """An unexpected optional-provider exception does not erase Kueue data."""

    async def unavailable() -> EfficiencyObservation:
        raise RuntimeError("optional backend failed")

    result = await _service(platform=_platform, platform_efficiency=unavailable).get(
        MetricsSubject("platform", "canfar")
    )

    assert result.observation.cluster == "cluster-a"
    assert result.efficiency is None
    assert not result.ready
    assert result.ready_reason == "PartialData"


@pytest.mark.anyio
async def test_optional_provider_cancellation_propagates() -> None:
    """Provider cancellation is not converted into a partial success."""

    async def cancelled() -> EfficiencyObservation:
        raise asyncio.CancelledError

    with pytest.raises(asyncio.CancelledError):
        await _service(platform=_platform, platform_efficiency=cancelled).get(
            MetricsSubject("platform", "canfar")
        )


@pytest.mark.anyio
async def test_efficiency_cancellation_cancels_primary_without_waiting_for_timeout() -> None:
    """An optional cancellation immediately releases a blocked Kueue read."""
    primary_started = asyncio.Event()
    primary_cancelled = asyncio.Event()
    request_finished = asyncio.Event()

    async def primary() -> PlatformObservation:
        primary_started.set()
        try:
            await asyncio.Future()
        except asyncio.CancelledError:
            primary_cancelled.set()
            raise

    async def cancelled() -> EfficiencyObservation:
        await asyncio.sleep(0)
        raise asyncio.CancelledError

    request = asyncio.create_task(
        _service(platform=primary, platform_efficiency=cancelled).get(
            MetricsSubject("platform", "canfar")
        )
    )
    request.add_done_callback(lambda _task: request_finished.set())
    await primary_started.wait()

    try:
        await asyncio.wait_for(request_finished.wait(), timeout=0.2)
        with pytest.raises(asyncio.CancelledError):
            await request
    finally:
        if not request.done():
            request.cancel()
        await asyncio.gather(request, return_exceptions=True)

    assert primary_cancelled.is_set()


@pytest.mark.anyio
async def test_primary_failure_cancels_and_awaits_optional_work() -> None:
    """A primary failure releases a blocked optional task before it returns."""
    optional_started = asyncio.Event()
    optional_cancelled = asyncio.Event()

    async def primary() -> PlatformObservation:
        await asyncio.sleep(0)
        raise ProviderUnavailableError("Kueue unavailable")

    async def optional() -> EfficiencyObservation:
        optional_started.set()
        try:
            await asyncio.Future()
        except asyncio.CancelledError:
            optional_cancelled.set()
            raise

    request = asyncio.create_task(
        _service(platform=primary, platform_efficiency=optional).get(
            MetricsSubject("platform", "canfar")
        )
    )
    await optional_started.wait()

    with pytest.raises(AppError) as caught:
        await asyncio.wait_for(request, timeout=0.2)
    assert caught.value.code == "platform_metrics_unavailable"
    assert optional_cancelled.is_set()


@pytest.mark.anyio
@pytest.mark.parametrize(
    ("surface", "provider_error"),
    [
        ("platform", ProviderUnavailableError("Kueue unavailable")),
        ("platform", ProviderExecutionError("Kueue response invalid")),
        ("user", ProviderUnavailableError("LocalQueue unavailable")),
        ("user", ProviderExecutionError("LocalQueue response invalid")),
    ],
    ids=[
        "platform-unavailable",
        "platform-execution",
        "user-unavailable",
        "user-execution",
    ],
)
async def test_expected_provider_failure_is_source_unavailable_at_cache_owner(
    surface: str,
    provider_error: Exception,
) -> None:
    """Expected provider failures retain source-unavailable cache semantics."""

    class OwnerCache(FakeCacheCoordinator):
        """Observe the exception category crossing the cache-fill boundary."""

        def __init__(self, cache_surface: str) -> None:
            super().__init__(
                policy=FRESHNESS_POLICIES[cache_surface],
                created=lambda snapshot: snapshot.created,
            )
            self.source_failures = 0
            self.internal_failures = 0

        async def get_or_fill(self, identity, fill):
            """Classify the fill exception as a cache owner would."""
            try:
                return await super().get_or_fill(identity, fill)
            except CacheUnavailable:
                self.source_failures += 1
                raise
            except Exception:
                self.internal_failures += 1
                raise

    class Recorder(MetricsRecorder):
        """Capture the provider outcome without constructing OTLP instruments."""

        def __init__(self) -> None:
            self.provider_statuses: list[tuple[str, str]] = []

        def record_provider_duration(
            self,
            *,
            provider: str,
            scope: str,
            status: str,
            seconds: float,
        ) -> None:
            """Record one provider status while ignoring dimensions not under test."""
            del provider, seconds
            self.provider_statuses.append((scope, status))

    async def failed_platform() -> PlatformObservation:
        raise provider_error

    async def failed_user(_username: str) -> UserObservation:
        raise provider_error

    owner_cache = OwnerCache(surface)
    recorder = Recorder()
    if surface == "platform":
        service = _service(
            platform=failed_platform,
            platform_cache=owner_cache,
            telemetry=recorder,
        )
        subject = MetricsSubject("platform", "canfar")
    else:
        service = _service(
            platform=_platform,
            user_loader=failed_user,
            user_cache=owner_cache,
            telemetry=recorder,
        )
        subject = MetricsSubject("user", "bob")

    with pytest.raises(AppError) as caught:
        await service.get(subject)

    assert caught.value.status_code == 503
    assert caught.value.code == f"{surface}_metrics_unavailable"
    assert owner_cache.source_failures == 1
    assert owner_cache.internal_failures == 0
    assert recorder.provider_statuses == [(surface, "error")]


@pytest.mark.anyio
async def test_user_snapshot_is_not_global_readiness_evidence() -> None:
    """One User subject cannot establish a service-wide cached snapshot."""
    service = _service(platform=_platform)

    await service.get(MetricsSubject("user", "bob"))

    state = service.readiness._surfaces["user"]  # noqa: SLF001
    assert state.source_reachable
    assert not state.snapshot_complete
    assert not state.snapshot_serviceable


@pytest.mark.anyio
@pytest.mark.parametrize("surface", ["user", "community"])
async def test_workload_source_failure_does_not_flap_shared_readiness(surface: str) -> None:
    """A User or Community subject read error stays off the global probe."""

    async def unavailable_user(_username: str) -> UserObservation:
        raise ProviderUnavailableError("LocalQueue read failed")

    async def unavailable_community(_community: str) -> CommunityObservation:
        raise ProviderUnavailableError("ClusterQueue read failed")

    loader = unavailable_user if surface == "user" else unavailable_community
    service = _service(
        platform=_platform,
        **{f"{surface}_loader": loader},
    )
    service.readiness.start()
    for tracked_surface in service.readiness.surfaces:
        service.readiness.mark_source(tracked_surface, reachable=True)

    subject = (
        MetricsSubject("user", "bob")
        if surface == "user"
        else MetricsSubject("community", "astronomy")
    )
    with pytest.raises(AppError) as caught:
        await service.get(subject)

    assert caught.value.code == f"{surface}_metrics_unavailable"
    assert service.readiness.ready
    assert service.readiness._surfaces[surface].source_reachable  # noqa: SLF001


class _RecoverableCacheOutage:
    """Expose one mutable shared-cache failure to the service seam."""

    backend_name = "redis"

    def __init__(self, surface: str) -> None:
        self.policy = FRESHNESS_POLICIES[surface]
        self.available = True

    async def get_or_fill(self, _identity, _fill):
        """Fail the request and record the cache outage."""
        self.available = False
        raise CacheUnavailable("shared cache unavailable")


@pytest.mark.anyio
@pytest.mark.parametrize("surface", ["user", "community"])
async def test_workload_cache_failure_flaps_readiness_until_recovery(surface: str) -> None:
    """A shared User or Community cache outage blocks readiness until synced."""
    cache = _RecoverableCacheOutage(surface)
    service = _service(platform=_platform, **{f"{surface}_cache": cache})
    service.readiness.start()
    for tracked_surface in service.readiness.surfaces:
        service.readiness.mark_source(tracked_surface, reachable=True)
        service.readiness.mark_cache(tracked_surface, available=True)

    subject = (
        MetricsSubject("user", "bob")
        if surface == "user"
        else MetricsSubject("community", "astronomy")
    )
    assert service.readiness.ready

    with pytest.raises(AppError) as caught:
        await service.get(subject)

    assert caught.value.code == "metrics_cache_unavailable"
    assert not service.readiness._surfaces[surface].cache_available  # noqa: SLF001
    assert not service.readiness.cache_available
    assert not service.readiness.ready

    cache.available = True
    service.sync_cache_readiness()

    assert service.readiness.cache_available
    assert service.readiness.ready


@pytest.mark.anyio
async def test_source_timeout_preserves_cache_availability_provenance() -> None:
    """A bounded source timeout is not misreported as a Redis outage."""

    class TimedOutCache:
        """Expose a source-fill timeout through the cache interface."""

        backend_name = "redis"
        policy = FRESHNESS_POLICIES["platform"]
        available = True

        async def get_or_fill(self, _identity, _fill):
            """Return the typed source timeout outcome."""
            raise CacheFillTimeout()

    service = _service(platform=_platform)
    service._cache = TimedOutCache()  # noqa: SLF001

    with pytest.raises(AppError) as caught:
        await service.get(MetricsSubject("platform", "canfar"))

    assert caught.value.code == "platform_metrics_unavailable"
    state = service.readiness._surfaces["platform"]  # noqa: SLF001
    assert state.cache_available
    assert not state.source_reachable


@pytest.mark.anyio
async def test_coordinator_is_the_only_cache_lookup_telemetry_owner() -> None:
    """Service orchestration does not duplicate coordinator cache lookups."""

    class Recorder(MetricsRecorder):
        """Count cache lookup observations from the test coordinator."""

        def __init__(self) -> None:
            self.lookups = 0

        def record_cache_lookup(self, **_details: object) -> None:
            """Count one lookup observation."""
            self.lookups += 1

    class RecordingCache(FakeCacheCoordinator):
        """Record the lookup that a real coordinator would own."""

        def __init__(self, recorder: Recorder) -> None:
            super().__init__(
                policy=FRESHNESS_POLICIES["platform"],
                created=lambda observation: observation.created,
            )
            self.recorder = recorder

        async def get_or_fill(self, identity, fill):
            """Return the value and emit one coordinator-owned lookup."""
            result = await super().get_or_fill(identity, fill)
            self.recorder.record_cache_lookup(
                backend=self.backend_name,
                result="hit" if result.cached else "miss",
                scope=identity.subject_kind,
            )
            return result

    recorder = Recorder()
    service = _service(
        platform=_platform,
        platform_cache=RecordingCache(recorder),
        telemetry=recorder,
    )

    await service.get(MetricsSubject("platform", "canfar"))

    assert recorder.lookups == 1


def test_efficiency_observation_requires_cpu_and_memory_together() -> None:
    """Partial efficiency vectors are rejected at the shared model boundary."""
    with pytest.raises(ValueError, match="cpu and memory together"):
        EfficiencyObservation(NOW, {"cpu": Decimal("0.5")})
