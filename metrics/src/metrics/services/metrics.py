"""Framework-neutral Metrics orchestration behind HTTP adapters."""

from __future__ import annotations

import asyncio
import logging
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from datetime import UTC, datetime
from time import perf_counter
from typing import Any, NoReturn

from metrics.cache import (
    CacheCoordinator,
    CacheIdentity,
    CacheNotFound,
    CacheResult,
    CacheUnavailable,
)
from metrics.errors import (
    AppError,
    ProviderExecutionError,
    ProviderUnavailableError,
    SubjectNotFoundError,
)
from metrics.services.models import (
    DEFAULT_PLATFORM_NAME,
    CachedSnapshot,
    CommunityObservation,
    EfficiencyObservation,
    MetricsResult,
    MetricsSubject,
    MetricsSurface,
    PlatformObservation,
    ReadinessState,
    SessionObservation,
    UserObservation,
)
from metrics.telemetry import MetricsRecorder, NoopMetricsRecorder


logger = logging.getLogger(__name__)

WorkloadObservation = UserObservation | CommunityObservation
AnyObservation = PlatformObservation | WorkloadObservation | SessionObservation
PlatformLoader = Callable[[], Awaitable[PlatformObservation]]
WorkloadLoader = Callable[[str], Awaitable[WorkloadObservation]]
SessionLoader = Callable[[str], Awaitable[SessionObservation]]
SessionUsageLoader = Callable[[str], Awaitable[dict[str, str]]]
PlatformEfficiencyLoader = Callable[[], Awaitable[EfficiencyObservation]]
WorkloadEfficiencyLoader = Callable[[str], Awaitable[EfficiencyObservation]]
SessionEfficiencyLoader = Callable[[SessionObservation], Awaitable[EfficiencyObservation]]


@dataclass(frozen=True, slots=True)
class _WorkloadBinding:
    """Bundle one workload loader with its cache and optional efficiency loader."""

    loader: WorkloadLoader
    cache: CacheCoordinator[CachedSnapshot]
    identity: Callable[[str], CacheIdentity]
    efficiency_loader: WorkloadEfficiencyLoader | None


class MetricsService:
    """Cache-first Metrics reads with per-subject source fills."""

    def __init__(
        self,
        *,
        platform: PlatformLoader,
        cache: CacheCoordinator[CachedSnapshot],
        identity: Callable[[], CacheIdentity],
        platform_name: str = DEFAULT_PLATFORM_NAME,
        platform_efficiency: PlatformEfficiencyLoader | None = None,
        user: WorkloadLoader,
        user_cache: CacheCoordinator[CachedSnapshot],
        user_identity: Callable[[str], CacheIdentity],
        user_efficiency: WorkloadEfficiencyLoader | None = None,
        community: WorkloadLoader,
        community_cache: CacheCoordinator[CachedSnapshot],
        community_identity: Callable[[str], CacheIdentity],
        community_efficiency: WorkloadEfficiencyLoader | None = None,
        session: SessionLoader | None = None,
        session_cache: CacheCoordinator[CachedSnapshot] | None = None,
        session_identity: Callable[[str], CacheIdentity] | None = None,
        session_usage: SessionUsageLoader | None = None,
        session_efficiency: SessionEfficiencyLoader | None = None,
        telemetry: MetricsRecorder | None = None,
        provider: str = "kueue",
        readiness: ReadinessState | None = None,
        efficiency_timeout_seconds: float = 5.0,
    ) -> None:
        """Wire all three Kueue surfaces and optional efficiency adapters.

        Efficiency is bounded independently from the Kueue observation and
        runs concurrently with it. A failed efficiency read leaves the queue
        observation serviceable, but marks that cached report ``Ready=False``
        with ``PartialData``.
        """
        if efficiency_timeout_seconds <= 0:
            raise ValueError("efficiency_timeout_seconds must be positive")

        self._platform = platform
        self._cache = cache
        self._identity = identity
        self._platform_name = platform_name
        self._platform_efficiency = platform_efficiency
        self._efficiency_timeout_seconds = efficiency_timeout_seconds
        self._workloads: dict[MetricsSurface, _WorkloadBinding] = {
            "user": _WorkloadBinding(user, user_cache, user_identity, user_efficiency),
            "community": _WorkloadBinding(
                community,
                community_cache,
                community_identity,
                community_efficiency,
            ),
        }
        if session is None or session_cache is None or session_identity is None:
            self._session = None
        else:
            self._session = (
                session,
                session_cache,
                session_identity,
                session_usage,
                session_efficiency,
            )
        self._metrics_recorder = telemetry or NoopMetricsRecorder()
        self._provider = provider
        self._readiness = readiness or ReadinessState(("platform", "user", "community"))
        self._serviceable_until: dict[MetricsSurface, datetime | None] = {
            surface: None for surface in self._readiness.surfaces
        }

    @property
    def readiness(self) -> ReadinessState:
        """Return the service's process readiness state."""
        return self._readiness

    def sync_cache_readiness(self) -> None:
        """Copy each cache coordinator's availability into readiness state."""
        caches: dict[MetricsSurface, CacheCoordinator[CachedSnapshot]] = {
            "platform": self._cache,
            **{surface: binding.cache for surface, binding in self._workloads.items()},
        }
        now = datetime.now(UTC)
        for surface in self._readiness.surfaces:
            self._readiness.mark_cache(surface, available=caches[surface].available)
            serviceable_until = self._global_serviceable_until(surface)
            self._readiness.mark_snapshot(
                surface,
                complete=serviceable_until is not None,
                serviceable=serviceable_until is not None and now <= serviceable_until,
            )

    def _global_serviceable_until(self, surface: MetricsSurface) -> datetime | None:
        """Return readiness evidence only for the global Platform snapshot."""
        if surface != "platform":
            return None
        return self._serviceable_until.get(surface)

    @property
    def cache_ttl_seconds(self) -> int:
        """Return the Platform fresh-cache window."""
        return self._cache.policy.fresh_seconds

    @property
    def user_cache_ttl_seconds(self) -> int:
        """Return the User fresh-cache window."""
        return self._workloads["user"].cache.policy.fresh_seconds

    @property
    def community_cache_ttl_seconds(self) -> int:
        """Return the Community fresh-cache window."""
        return self._workloads["community"].cache.policy.fresh_seconds

    @property
    def session_cache_ttl_seconds(self) -> int:
        """Return the Session fresh-cache window."""
        if self._session is None:
            raise RuntimeError("Session cache is not configured")
        return self._session[1].policy.fresh_seconds

    async def get(self, subject: MetricsSubject) -> MetricsResult:
        """Return one cached or freshly filled report for ``subject``."""
        started = perf_counter()
        status = "ok"
        try:
            if subject.kind == "platform":
                if subject.value != self._platform_name:
                    raise AppError(code="platform_not_found", status_code=404)
                return await self._get_platform()
            if subject.kind == "user":
                return await self._get_workload("user", subject.value)
            if subject.kind == "community":
                return await self._get_workload("community", subject.value)
            return await self._get_session(subject.value)
        except asyncio.CancelledError:
            status = "cancelled"
            raise
        except AppError as exc:
            status = "not_found" if exc.status_code == 404 else "error"
            raise
        except Exception:
            status = "error"
            raise
        finally:
            self._metrics_recorder.record_compute_duration(
                seconds=perf_counter() - started,
                status=status,
                scope=subject.kind,
            )

    def _raise_unavailable(self, kind: MetricsSurface, exc: CacheUnavailable) -> NoReturn:
        """Map a cache miss or source outage onto the HTTP error contract."""
        if exc.cache_available and exc.source_reachable is False:
            self._mark_surface_failure(kind)
            raise AppError(code=f"{kind}_metrics_unavailable", status_code=503) from exc
        self._mark_cache_failure(kind)
        raise AppError(code="metrics_cache_unavailable", status_code=503, retry_after=1) from exc

    async def _get_platform(self) -> MetricsResult:
        """Resolve one Platform cache identity and map failures to HTTP semantics."""
        try:
            result = await self._cache.get_or_fill(self._identity(), self._load_platform_snapshot)
        except CacheUnavailable as exc:
            self._raise_unavailable("platform", exc)
        except Exception:
            self._mark_surface_failure("platform")
            raise
        snapshot = self._require_snapshot(result.value, PlatformObservation)
        self._mark_surface_result("platform", result)
        return self._result(snapshot, result)

    async def _get_workload(self, kind: MetricsSurface, subject: str) -> MetricsResult:
        """Resolve one User or Community cache identity."""
        binding = self._workloads[kind]
        try:
            result = await binding.cache.get_or_fill(
                binding.identity(subject),
                lambda: self._load_workload_snapshot(kind, subject, binding),
            )
        except CacheUnavailable as exc:
            self._raise_unavailable(kind, exc)
        except CacheNotFound as exc:
            self._mark_subject_not_found(
                kind,
                cache_available=exc.cache_available,
                source_reachable=exc.source_reachable,
            )
            raise AppError(code=f"{kind}_not_found", status_code=404) from exc
        except Exception:
            self._mark_surface_failure(kind)
            raise
        expected_type = UserObservation if kind == "user" else CommunityObservation
        snapshot = self._require_snapshot(result.value, expected_type)
        self._mark_surface_result(kind, result)
        return self._result(snapshot, result)

    async def _get_session(self, session_id: str) -> MetricsResult:
        """Resolve one Session cache identity."""
        binding = self._session
        if binding is None:
            raise RuntimeError("Session metrics are not configured")
        loader, cache, identity, _usage, _efficiency = binding
        try:
            result = await cache.get_or_fill(
                identity(session_id),
                lambda: self._load_session_snapshot(session_id, binding),
            )
        except CacheUnavailable as exc:
            self._raise_unavailable("session", exc)
        except CacheNotFound as exc:
            raise AppError(code="session_not_found", status_code=404) from exc
        except Exception:
            raise
        snapshot = self._require_snapshot(result.value, SessionObservation)
        return self._result(snapshot, result)

    async def _load_session_snapshot(
        self,
        session_id: str,
        binding: tuple[
            SessionLoader,
            CacheCoordinator[CachedSnapshot],
            Callable[[str], CacheIdentity],
            SessionUsageLoader | None,
            SessionEfficiencyLoader | None,
        ],
    ) -> CachedSnapshot:
        """Fill one Session observation with optional usage and efficiency."""
        loader, _cache, _identity, usage_loader, efficiency_loader = binding
        observation = await self._timed_session_load(session_id, loader)
        usage_task: asyncio.Task[dict[str, str] | None] | None = None
        efficiency_task: asyncio.Task[EfficiencyObservation | None] | None = None
        tasks: list[asyncio.Future[Any]] = []
        if usage_loader is not None:
            usage_task = asyncio.create_task(
                self._bounded_session_usage_load(session_id, usage_loader)
            )
            tasks.append(usage_task)
        if efficiency_loader is not None and observation.start_time is not None:
            efficiency_task = asyncio.create_task(
                self._bounded_efficiency_load(lambda: efficiency_loader(observation))
            )
            tasks.append(efficiency_task)

        usage: dict[str, str] | None = None
        efficiency: EfficiencyObservation | None = None
        usage_failed = False
        efficiency_failed = False
        pending: set[asyncio.Future[Any]] = set(tasks)
        try:
            while pending:
                done, pending = await asyncio.wait(
                    pending,
                    return_when=asyncio.FIRST_COMPLETED,
                )
                if usage_task is not None and usage_task in done:
                    if usage_task.cancelled():
                        raise asyncio.CancelledError
                    usage_error = usage_task.exception()
                    if usage_error is not None:
                        if isinstance(usage_error, Exception):
                            usage_failed = True
                        else:
                            raise usage_error
                    else:
                        usage = usage_task.result()
                if efficiency_task is not None and efficiency_task in done:
                    if efficiency_task.cancelled():
                        raise asyncio.CancelledError
                    efficiency_error = efficiency_task.exception()
                    if efficiency_error is not None:
                        if isinstance(efficiency_error, Exception):
                            efficiency_failed = True
                        else:
                            raise efficiency_error
                    else:
                        efficiency_result = efficiency_task.result()
                        efficiency = efficiency_result
                        efficiency_failed = efficiency_result is None
        except BaseException:
            for task in pending:
                if not task.done():
                    task.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)
            raise

        ready = True
        ready_reason = "Available"
        if usage_failed and observation.has_running_pods:
            logger.warning("Session usage data unavailable for %s", session_id)
            ready = False
            ready_reason = "PartialData"
        if efficiency_failed:
            logger.warning("Session efficiency data unavailable for %s", session_id)
            ready = False
            ready_reason = "PartialData"

        created = observation.observed_at
        if efficiency is not None:
            created = min(created, efficiency.observed_at)
        usage_payload = None if not usage else usage
        return CachedSnapshot(
            observation=observation,
            created=created,
            efficiency=efficiency,
            usage=usage_payload,
            ready=ready,
            ready_reason=ready_reason,
        )

    async def _bounded_session_usage_load(
        self,
        session_id: str,
        loader: SessionUsageLoader,
    ) -> dict[str, str] | None:
        """Bound optional session usage without failing the primary Job read."""
        try:
            async with asyncio.timeout(self._efficiency_timeout_seconds):
                return await loader(session_id)
        except TimeoutError:
            return None

    async def _timed_session_load(
        self,
        session_id: str,
        loader: SessionLoader,
    ) -> SessionObservation:
        """Load one Session surface and map expected provider failures."""
        started = perf_counter()
        status = "ok"
        try:
            return await loader(session_id)
        except SubjectNotFoundError as exc:
            status = "not_found"
            raise CacheNotFound(source_reachable=True) from exc
        except ProviderUnavailableError as exc:
            status = "error"
            raise CacheUnavailable(
                "Session source is unavailable",
                cache_available=True,
                source_reachable=False,
            ) from exc
        except ProviderExecutionError as exc:
            status = "error"
            raise CacheUnavailable(
                "Session source response is unusable",
                cache_available=True,
                source_reachable=False,
            ) from exc
        finally:
            self._metrics_recorder.record_provider_duration(
                provider="session",
                scope="session",
                status=status,
                seconds=perf_counter() - started,
            )

    async def _load_platform_snapshot(self) -> CachedSnapshot:
        """Fill Platform Kueue data and optional attributed efficiency."""
        return await self._snapshot_with_efficiency(
            "platform",
            self._timed_platform_load,
            self._platform_efficiency,
        )

    async def _load_workload_snapshot(
        self,
        kind: MetricsSurface,
        subject: str,
        binding: _WorkloadBinding,
    ) -> CachedSnapshot:
        """Fill one workload observation and its optional efficiency."""
        efficiency_loader = binding.efficiency_loader
        loader = None if efficiency_loader is None else lambda: efficiency_loader(subject)
        return await self._snapshot_with_efficiency(
            kind,
            lambda: self._timed_workload_load(kind, subject, binding.loader),
            loader,
        )

    async def _snapshot_with_efficiency(
        self,
        kind: MetricsSurface,
        observation_loader: Callable[[], Awaitable[AnyObservation]],
        efficiency_loader: Callable[[], Awaitable[EfficiencyObservation]] | None,
    ) -> CachedSnapshot:
        """Collect Kueue and bounded optional efficiency in one fill."""
        observation_task = asyncio.ensure_future(observation_loader())
        tasks: list[asyncio.Future[Any]] = [observation_task]
        efficiency_task: asyncio.Task[EfficiencyObservation | None] | None = None
        if efficiency_loader is not None:
            efficiency_task = asyncio.create_task(self._bounded_efficiency_load(efficiency_loader))
            tasks.append(efficiency_task)
        observation: AnyObservation | None = None
        efficiency_result: EfficiencyObservation | None = None
        efficiency_failed = False
        pending: set[asyncio.Future[Any]] = set(tasks)
        try:
            while pending:
                done, pending = await asyncio.wait(
                    pending,
                    return_when=asyncio.FIRST_COMPLETED,
                )
                if observation_task in done:
                    if observation_task.cancelled():
                        raise asyncio.CancelledError
                    observation_error = observation_task.exception()
                    if observation_error is not None:
                        raise observation_error
                    observation = observation_task.result()
                if efficiency_task is not None and efficiency_task in done:
                    if efficiency_task.cancelled():
                        raise asyncio.CancelledError
                    efficiency_error = efficiency_task.exception()
                    if efficiency_error is not None:
                        if isinstance(efficiency_error, Exception):
                            efficiency_failed = True
                        else:
                            raise efficiency_error
                    else:
                        efficiency_result = efficiency_task.result()
                        efficiency_failed = efficiency_result is None
        except BaseException:
            for task in pending:
                if not task.done():
                    task.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)
            raise

        if observation is None:
            raise RuntimeError("Kueue observation task completed without a result")

        efficiency: EfficiencyObservation | None = efficiency_result
        ready = True
        ready_reason = "Available"
        if efficiency_task is not None and efficiency_failed:
            logger.warning("%s efficiency data unavailable", kind)
            ready = False
            ready_reason = "PartialData"
        created = observation.observed_at
        if efficiency is not None:
            created = min(created, efficiency.observed_at)
        return CachedSnapshot(
            observation=observation,
            created=created,
            efficiency=efficiency,
            ready=ready,
            ready_reason=ready_reason,
        )

    async def _bounded_efficiency_load(
        self,
        loader: Callable[[], Awaitable[EfficiencyObservation]],
    ) -> EfficiencyObservation | None:
        """Bound optional efficiency without changing Kueue cancellation."""
        try:
            async with asyncio.timeout(self._efficiency_timeout_seconds):
                return await loader()
        except TimeoutError:
            return None

    async def _timed_platform_load(self) -> PlatformObservation:
        """Load Platform data and map expected provider failures."""
        started = perf_counter()
        status = "ok"
        try:
            return await self._platform()
        except ProviderUnavailableError as exc:
            status = "error"
            raise CacheUnavailable(
                "Platform source is unavailable",
                cache_available=True,
                source_reachable=False,
            ) from exc
        except ProviderExecutionError as exc:
            status = "error"
            raise CacheUnavailable(
                "Platform source response is unusable",
                cache_available=True,
                source_reachable=False,
            ) from exc
        finally:
            self._metrics_recorder.record_provider_duration(
                provider=self._provider,
                scope="platform",
                status=status,
                seconds=perf_counter() - started,
            )

    async def _timed_workload_load(
        self,
        kind: MetricsSurface,
        subject: str,
        loader: WorkloadLoader,
    ) -> WorkloadObservation:
        """Load one workload surface and map expected provider failures."""
        started = perf_counter()
        status = "ok"
        try:
            return await loader(subject)
        except SubjectNotFoundError as exc:
            status = "not_found"
            raise CacheNotFound(source_reachable=True) from exc
        except ProviderUnavailableError as exc:
            status = "error"
            raise CacheUnavailable(
                f"{kind.capitalize()} source is unavailable",
                cache_available=True,
                source_reachable=False,
            ) from exc
        except ProviderExecutionError as exc:
            status = "error"
            raise CacheUnavailable(
                f"{kind.capitalize()} source response is unusable",
                cache_available=True,
                source_reachable=False,
            ) from exc
        finally:
            self._metrics_recorder.record_provider_duration(
                provider=self._provider,
                scope=kind,
                status=status,
                seconds=perf_counter() - started,
            )

    @staticmethod
    def _require_snapshot(
        snapshot: CachedSnapshot,
        expected_type: type[PlatformObservation]
        | type[UserObservation]
        | type[CommunityObservation]
        | type[SessionObservation],
    ) -> CachedSnapshot:
        """Reject a cache payload for the wrong Metrics surface."""
        if not isinstance(snapshot.observation, expected_type):
            raise TypeError("Metrics cache returned an observation for the wrong surface")
        return snapshot

    @staticmethod
    def _result(
        snapshot: CachedSnapshot,
        result: CacheResult[CachedSnapshot],
    ) -> MetricsResult:
        """Convert a generic cache result into the transport-neutral result."""
        return MetricsResult(
            observation=snapshot.observation,
            created=snapshot.created,
            cached=result.cached,
            stale=result.stale,
            cache_available=result.cache_available,
            efficiency=snapshot.efficiency,
            usage=snapshot.usage,
            ready=snapshot.ready,
            ready_reason=snapshot.ready_reason,
        )

    def _mark_surface_result(
        self,
        surface: MetricsSurface,
        result: CacheResult[CachedSnapshot],
    ) -> None:
        """Record a serviceable queue snapshot in readiness state."""
        self._readiness.mark_cache(
            surface,
            available=result.cache_available,
        )
        self._readiness.mark_snapshot(
            surface,
            complete=surface == "platform",
            serviceable=(
                surface == "platform"
                and result.serviceable_until is not None
                and datetime.now(UTC) <= result.serviceable_until
            ),
        )
        self._serviceable_until[surface] = (
            result.serviceable_until if surface == "platform" else None
        )
        if result.source_reachable is not None:
            self._readiness.mark_source(surface, reachable=result.source_reachable)

    def _mark_subject_not_found(
        self,
        surface: MetricsSurface,
        *,
        cache_available: bool,
        source_reachable: bool | None,
    ) -> None:
        """Record an authoritative subject miss without failing the source surface."""
        if surface not in self._readiness.surfaces:
            return
        self._serviceable_until[surface] = None
        self._readiness.mark_cache(surface, available=cache_available)
        self._readiness.mark_snapshot(surface, complete=False, serviceable=False)
        if source_reachable is not None:
            self._readiness.mark_source(surface, reachable=source_reachable)

    def _mark_surface_failure(
        self,
        surface: MetricsSurface,
    ) -> None:
        """Record a failed read when no report can satisfy the request."""
        if surface not in self._readiness.surfaces:
            return
        self._serviceable_until[surface] = None
        if surface == "platform":
            self._readiness.mark_source(surface, reachable=False)
        self._readiness.mark_snapshot(surface, complete=False, serviceable=False)

    def _mark_cache_failure(self, surface: MetricsSurface) -> None:
        """Record cache unavailability without discarding source state."""
        if surface not in self._readiness.surfaces:
            return
        self._readiness.mark_cache(surface, available=False)
