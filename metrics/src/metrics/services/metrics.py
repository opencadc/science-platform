"""Framework-neutral Metrics orchestration behind HTTP adapters."""

from __future__ import annotations

import logging
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, replace
from datetime import UTC, datetime
from time import perf_counter

from metrics.cache import CacheCoordinator, CacheIdentity, CacheResult, CacheUnavailable
from metrics.errors import AppError, ProviderExecutionError, ProviderUnavailableError
from metrics.services.models import (
    DEFAULT_PLATFORM_NAME,
    AccountingSnapshot,
    AccountingState,
    CachedSnapshot,
    CommunityObservation,
    LifetimeIssue,
    MetricsResult,
    MetricsSubject,
    PlatformObservation,
    UserObservation,
)
from metrics.telemetry import MetricsRecorder, NoopMetricsRecorder

logger = logging.getLogger(__name__)


WorkloadObservation = UserObservation | CommunityObservation


@dataclass(frozen=True, slots=True)
class _WorkloadBinding:
    """Bundle one subject loader with its observation and accounting caches."""

    loader: Callable[[str], Awaitable[WorkloadObservation]]
    cache: CacheCoordinator[CachedSnapshot]
    identity: Callable[[str], CacheIdentity]
    accounting: Callable[[str, datetime], Awaitable[CacheResult[AccountingSnapshot]]] | None = None


class MetricsService:
    """Cache-first Metrics reads with subject dispatch and provider error mapping.

    Routes call :meth:`get` only. Cache orchestration and source selection stay here.
    """

    def __init__(
        self,
        *,
        platform: Callable[[], Awaitable[PlatformObservation]],
        cache: CacheCoordinator[CachedSnapshot],
        identity: Callable[[], CacheIdentity],
        platform_name: str = DEFAULT_PLATFORM_NAME,
        user: Callable[[str], Awaitable[UserObservation]] | None = None,
        user_cache: CacheCoordinator[CachedSnapshot] | None = None,
        user_identity: Callable[[str], CacheIdentity] | None = None,
        user_accounting: (
            Callable[[str, datetime], Awaitable[CacheResult[AccountingSnapshot]]] | None
        ) = None,
        community: Callable[[str], Awaitable[CommunityObservation]] | None = None,
        community_cache: CacheCoordinator[CachedSnapshot] | None = None,
        community_identity: Callable[[str], CacheIdentity] | None = None,
        community_accounting: (
            Callable[[str, datetime], Awaitable[CacheResult[AccountingSnapshot]]] | None
        ) = None,
        telemetry: MetricsRecorder | None = None,
        provider: str = "unknown",
        user_provider: str = "kubernetes",
    ) -> None:
        """Wire cache, platform loader, and optional telemetry.

        Args:
            platform: Async callable that fetches a fresh platform observation.
            cache: Required cache coordinator storing :class:`CachedSnapshot`.
            identity: Sync callable returning the platform cache identity.
            platform_name: Configured public platform path segment (default
                ``canfar``). Requests for other platform names return 404.
            user: Optional callable that fetches one user observation.
            user_cache: Coordinator using the User freshness policy.
            user_identity: Callable returning an opaque user cache identity.
            user_accounting: Optional cache-coordinated lifetime accounting read.
            community: Optional callable that fetches one community observation.
            community_cache: Coordinator using the Community freshness policy.
            community_identity: Callable returning an opaque community cache identity.
            community_accounting: Optional cache-coordinated lifetime accounting read.
            telemetry: Optional cache/provider timing recorder.
            provider: Adapter name for provider duration telemetry.
            user_provider: User adapter name for provider duration telemetry.
        """
        self._platform = platform
        self._cache = cache
        self._identity = identity
        self._platform_name = platform_name
        self._workloads: dict[str, _WorkloadBinding] = {}
        if user is not None and user_cache is not None and user_identity is not None:
            self._workloads["user"] = _WorkloadBinding(
                user, user_cache, user_identity, user_accounting
            )
        if community is not None and community_cache is not None and community_identity is not None:
            self._workloads["community"] = _WorkloadBinding(
                community, community_cache, community_identity, community_accounting
            )
        self._metrics_recorder = telemetry or NoopMetricsRecorder()
        self._provider = provider
        self._user_provider = user_provider

    @property
    def cache_ttl_seconds(self) -> int:
        """Return the Platform report freshness window."""
        return self._cache.policy.fresh_seconds

    @property
    def user_cache_ttl_seconds(self) -> int:
        """Return the User report freshness window."""
        binding = self._workloads.get("user")
        return binding.cache.policy.fresh_seconds if binding else 0

    @property
    def community_cache_ttl_seconds(self) -> int:
        """Return the Community report freshness window."""
        binding = self._workloads.get("community")
        return binding.cache.policy.fresh_seconds if binding else 0

    async def get(self, subject: MetricsSubject) -> MetricsResult:
        """Return Metrics for ``subject``, using cache on hit and the source on miss.

        Concurrent cache misses coalesce onto one in-flight backend load per key
        (single-flight). Cancelling one waiter does not cancel the shared load.

        Args:
            subject: Subject selector (``platform``, ``user``, or ``community``).

        Returns:
            Observation, snapshot creation time, and whether it came from cache.

        Raises:
            AppError: Unsupported subject, unknown platform name, provider
                unavailability (503), or execution failure. Details stay in
                server logs.
        """
        with self._metrics_recorder.span(
            "metrics.get",
            {"metrics.operation": "get", "metrics.subject.type": subject.kind},
        ):
            if subject.kind == "platform":
                if subject.value != self._platform_name:
                    raise AppError(
                        code="platform_not_found",
                        message="Requested platform metrics subject is not configured",
                        status_code=404,
                    )
                return await self._get_platform()
            if subject.kind in self._workloads:
                return await self._get_workload(subject.kind, subject.value)
            raise AppError(
                code="subject_unsupported",
                message="Requested metrics subject is not supported",
                status_code=404,
            )

    async def _get_platform(self) -> MetricsResult:
        """Resolve a platform snapshot and map cache failure to API semantics."""
        try:
            result = await self._cache.get_or_fill(self._identity(), self._load_snapshot)
        except CacheUnavailable as exc:
            raise AppError(
                code="metrics_cache_unavailable",
                message="Platform metrics are temporarily unavailable",
                status_code=503,
                retry_after=1,
            ) from exc
        snapshot = result.value
        cache_result = "stale" if result.stale else ("hit" if result.cached else "miss")
        self._metrics_recorder.record_cache_lookup(
            backend=self._cache.backend_name,
            result=cache_result,
            scope="platform",
            age_seconds=(datetime.now(UTC) - snapshot.created).total_seconds(),
        )
        logger.info("Platform metrics request completed")
        return MetricsResult(
            observation=snapshot.observation,
            created=snapshot.created,
            cached=result.cached,
            stale=result.stale,
            cache_available=self._cache.available,
        )

    async def _load_snapshot(self) -> CachedSnapshot:
        """Load and timestamp a fresh platform observation for the cache."""
        scope = "platform"
        started = perf_counter()
        status = "ok"
        try:
            observation = await self._timed_platform_load()
            created = datetime.now(UTC)
            return CachedSnapshot(observation=observation, created=created)
        except AppError as exc:
            status = exc.code
            raise
        except Exception:
            status = "unexpected_error"
            raise
        finally:
            self._metrics_recorder.record_compute_duration(
                seconds=perf_counter() - started,
                status=status,
                scope=scope,
            )

    async def _get_workload(self, kind: str, subject: str) -> MetricsResult:
        """Resolve one cached user or community workload report.

        Args:
            kind: Supported workload subject kind.
            subject: Exact canonical subject value.

        Returns:
            Workload observation with cache provenance.
        """
        binding = self._workloads[kind]
        try:
            result = await binding.cache.get_or_fill(
                binding.identity(subject),
                lambda: self._load_workload_snapshot(kind, subject, binding),
            )
        except CacheUnavailable as exc:
            raise AppError(
                code="metrics_cache_unavailable",
                message=f"{kind.title()} metrics are temporarily unavailable",
                status_code=503,
                retry_after=1,
            ) from exc
        snapshot = result.value
        self._record_cache_result(kind, binding.cache, snapshot, result.cached, result.stale)
        observation = snapshot.observation
        if not isinstance(observation, (UserObservation, CommunityObservation)):
            raise TypeError("Workload cache returned a platform observation")
        return MetricsResult(
            observation=observation,
            created=snapshot.created,
            cached=result.cached,
            stale=result.stale or observation.accounting_stale,
            cache_available=binding.cache.available,
        )

    def _record_cache_result(
        self,
        scope: str,
        cache: CacheCoordinator[CachedSnapshot],
        snapshot: CachedSnapshot,
        cached: bool,
        stale: bool,
    ) -> None:
        """Record bounded lookup provenance for a returned snapshot.

        Args:
            scope: Metrics subject kind.
            cache: Coordinator that served the request.
            snapshot: Returned observation snapshot.
            cached: Whether the coordinator reported a hit.
            stale: Whether the snapshot is stale-serviceable.
        """
        cache_result = "stale" if stale else ("hit" if cached else "miss")
        self._metrics_recorder.record_cache_lookup(
            backend=cache.backend_name,
            result=cache_result,
            scope=scope,
            age_seconds=(datetime.now(UTC) - snapshot.created).total_seconds(),
        )

    async def _load_workload_snapshot(
        self,
        kind: str,
        subject: str,
        binding: _WorkloadBinding,
    ) -> CachedSnapshot:
        """Load workload requests and merge matching optional accounting.

        Args:
            kind: User or community subject kind.
            subject: Exact canonical subject value.
            binding: Provider and cache functions for the subject kind.

        Returns:
            Observation snapshot suitable for the workload cache.
        """
        started = perf_counter()
        status = "ok"
        try:
            observation = await self._timed_workload_load(kind, subject, binding.loader)
            if binding.accounting is not None:
                try:
                    result = await binding.accounting(subject, observation.observed_at)
                    observation = self._merge_accounting(observation, result)
                except (
                    CacheUnavailable,
                    ProviderExecutionError,
                    ProviderUnavailableError,
                ):
                    logger.warning("%s lifetime accounting unavailable", kind.title())
                    observation = replace(
                        observation,
                        accounting_state=AccountingState.UNAVAILABLE,
                    )
            return CachedSnapshot(observation=observation, created=observation.observed_at)
        except AppError as exc:
            status = exc.code
            raise
        except Exception:
            status = "unexpected_error"
            raise
        finally:
            self._metrics_recorder.record_compute_duration(
                seconds=perf_counter() - started,
                status=status,
                scope=kind,
            )

    @staticmethod
    def _merge_accounting(
        observation: WorkloadObservation,
        result: CacheResult[AccountingSnapshot],
    ) -> WorkloadObservation:
        """Merge accounting only when time, Pods, and resource coverage match.

        Args:
            observation: Current Kubernetes workload population.
            result: Cache-coordinated accounting for that population.

        Returns:
            Observation with complete, incomplete, or stale accounting state.
        """
        accounting = result.value
        if (
            accounting.lifetime.pod_uids != observation.pod_uids
            or accounting.created != observation.observed_at
        ):
            return replace(
                observation,
                accounting_state=AccountingState.INCOMPLETE,
                accounting_stale=result.stale,
            )
        uncovered = {
            resource
            for resource in accounting.lifetime.resources
            if accounting.lifetime.coverage.get(resource, frozenset()) != observation.pod_uids
        }
        lifetime = replace(
            accounting.lifetime,
            resources={
                resource: hours
                for resource, hours in accounting.lifetime.resources.items()
                if resource not in uncovered
            },
            incomplete=accounting.lifetime.incomplete
            | {resource: frozenset({LifetimeIssue.MISSING_SERIES}) for resource in uncovered},
        )
        return replace(
            observation,
            accounting=lifetime,
            accounting_state=(
                AccountingState.COMPLETE if lifetime.ready else AccountingState.INCOMPLETE
            ),
            accounting_stale=result.stale,
        )

    async def _timed_workload_load(
        self,
        kind: str,
        subject: str,
        loader: Callable[[str], Awaitable[WorkloadObservation]],
    ) -> WorkloadObservation:
        """Time one workload provider call and map expected failures.

        Args:
            kind: User or community subject kind.
            subject: Exact canonical subject value.
            loader: Provider callable for the subject kind.

        Returns:
            Fresh workload observation.
        """
        started = perf_counter()
        status = "ok"
        try:
            with self._metrics_recorder.span(
                "source.read",
                {
                    "metrics.scope": kind,
                    "provider.name": self._user_provider,
                    "source.operation": "read",
                },
            ):
                return await loader(subject)
        except (ProviderUnavailableError, ProviderExecutionError) as exc:
            status = "error"
            logger.warning("%s metrics collection failed", kind.title())
            raise AppError(
                code=f"{kind}_metrics_unavailable",
                message=f"Could not load {kind} metrics from Kubernetes",
                status_code=503,
            ) from exc
        finally:
            self._metrics_recorder.record_provider_duration(
                provider=self._user_provider,
                scope=kind,
                status=status,
                seconds=perf_counter() - started,
            )

    async def _timed_platform_load(self) -> PlatformObservation:
        """Time a platform provider call and map expected failures."""
        started = perf_counter()
        status = "ok"
        try:
            with self._metrics_recorder.span(
                "source.read",
                {
                    "metrics.scope": "platform",
                    "provider.name": self._provider,
                    "source.operation": "read",
                },
            ):
                return await self._platform()
        except ProviderUnavailableError as exc:
            status = "error"
            logger.warning("Platform metrics unavailable")
            raise AppError(
                code="platform_metrics_unavailable",
                message="Could not load platform metrics from Kubernetes",
                status_code=503,
            ) from exc
        except ProviderExecutionError as exc:
            status = "error"
            logger.error("Platform metrics collection failed")
            raise AppError(
                code="platform_metrics_error",
                message="Platform metrics collection failed",
                status_code=503,
            ) from exc
        except Exception:
            status = "error"
            raise
        finally:
            self._metrics_recorder.record_provider_duration(
                provider=self._provider,
                scope="platform",
                status=status,
                seconds=perf_counter() - started,
            )
