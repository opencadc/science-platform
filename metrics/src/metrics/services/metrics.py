"""Framework-neutral Metrics orchestration behind HTTP adapters."""

from __future__ import annotations

import logging
from collections.abc import Awaitable, Callable
from dataclasses import replace
from datetime import UTC, datetime
from time import perf_counter

from metrics.cache import CacheCoordinator, CacheIdentity, CacheResult, CacheUnavailable
from metrics.errors import AppError, ProviderExecutionError, ProviderUnavailableError
from metrics.services.models import (
    PLATFORM_SUBJECT,
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
        user: Callable[[str], Awaitable[UserObservation]] | None = None,
        user_cache: CacheCoordinator[CachedSnapshot] | None = None,
        user_identity: Callable[[str], CacheIdentity] | None = None,
        user_accounting: Callable[[str], Awaitable[CacheResult]] | None = None,
        community: Callable[[str], Awaitable[CommunityObservation]] | None = None,
        community_cache: CacheCoordinator[CachedSnapshot] | None = None,
        community_identity: Callable[[str], CacheIdentity] | None = None,
        community_accounting: Callable[[str], Awaitable[CacheResult]] | None = None,
        telemetry: MetricsRecorder | None = None,
        provider: str = "unknown",
        user_provider: str = "kubernetes",
    ) -> None:
        """Wire cache, platform loader, and optional telemetry.

        Args:
            platform: Async callable that fetches a fresh platform observation.
            cache: Required cache coordinator storing :class:`CachedSnapshot`.
            identity: Sync callable returning the platform cache identity.
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
        self._user = user
        self._user_cache = user_cache
        self._user_identity = user_identity
        self._user_accounting = user_accounting
        self._community = community
        self._community_cache = community_cache
        self._community_identity = community_identity
        self._community_accounting = community_accounting
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
        return self._user_cache.policy.fresh_seconds if self._user_cache else 0

    @property
    def community_cache_ttl_seconds(self) -> int:
        """Return the Community report freshness window."""
        return self._community_cache.policy.fresh_seconds if self._community_cache else 0

    async def get(self, subject: MetricsSubject) -> MetricsResult:
        """Return Metrics for ``subject``, using cache on hit and the source on miss.

        Concurrent cache misses coalesce onto one in-flight backend load per key
        (single-flight). Cancelling one waiter does not cancel the shared load.

        Args:
            subject: Subject selector. Only ``platform`` is supported initially.

        Returns:
            Observation, snapshot creation time, and whether it came from cache.

        Raises:
            AppError: Unsupported subject, provider unavailability (503), or
                execution failure (502). Details stay in server logs.
        """
        with self._metrics_recorder.span(
            "metrics.get",
            {"metrics.operation": "get", "metrics.subject.type": subject.kind},
        ):
            if subject == PLATFORM_SUBJECT:
                return await self._get_platform()
            if (
                subject.kind == "user"
                and self._user is not None
                and self._user_cache is not None
                and self._user_identity is not None
            ):
                return await self._get_user(subject.value)
            if (
                subject.kind == "community"
                and self._community is not None
                and self._community_cache is not None
                and self._community_identity is not None
            ):
                return await self._get_community(subject.value)
            raise AppError(
                code="subject_unsupported",
                message="Requested metrics subject is not supported",
                status_code=404,
            )

    async def _get_platform(self) -> MetricsResult:
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

    async def _get_user(self, username: str) -> MetricsResult:
        assert self._user_cache is not None
        assert self._user_identity is not None
        try:
            result = await self._user_cache.get_or_fill(
                self._user_identity(username),
                lambda: self._load_user_snapshot(username),
            )
        except CacheUnavailable as exc:
            raise AppError(
                code="metrics_cache_unavailable",
                message="User metrics are temporarily unavailable",
                status_code=503,
                retry_after=1,
            ) from exc
        snapshot = result.value
        self._record_cache_result("user", snapshot, result.cached, result.stale)
        observation = snapshot.observation
        accounting_stale = isinstance(observation, UserObservation) and observation.accounting_stale
        return MetricsResult(
            observation=observation,
            created=snapshot.created,
            cached=result.cached,
            stale=result.stale or accounting_stale,
            cache_available=self._user_cache.available,
        )

    async def _get_community(self, community: str) -> MetricsResult:
        assert self._community_cache is not None
        assert self._community_identity is not None
        try:
            result = await self._community_cache.get_or_fill(
                self._community_identity(community),
                lambda: self._load_community_snapshot(community),
            )
        except CacheUnavailable as exc:
            raise AppError(
                code="metrics_cache_unavailable",
                message="Community metrics are temporarily unavailable",
                status_code=503,
                retry_after=1,
            ) from exc
        snapshot = result.value
        self._record_cache_result("community", snapshot, result.cached, result.stale)
        observation = snapshot.observation
        accounting_stale = (
            isinstance(observation, CommunityObservation) and observation.accounting_stale
        )
        return MetricsResult(
            observation=observation,
            created=snapshot.created,
            cached=result.cached,
            stale=result.stale or accounting_stale,
            cache_available=self._community_cache.available,
        )

    def _record_cache_result(
        self,
        scope: str,
        snapshot: CachedSnapshot,
        cached: bool,
        stale: bool,
    ) -> None:
        cache = {
            "platform": self._cache,
            "user": self._user_cache,
            "community": self._community_cache,
        }[scope]
        assert cache is not None
        cache_result = "stale" if stale else ("hit" if cached else "miss")
        self._metrics_recorder.record_cache_lookup(
            backend=cache.backend_name,
            result=cache_result,
            scope=scope,
            age_seconds=(datetime.now(UTC) - snapshot.created).total_seconds(),
        )

    async def _load_user_snapshot(self, username: str) -> CachedSnapshot:
        started = perf_counter()
        status = "ok"
        try:
            observation = await self._timed_user_load(username)
            created = datetime.now(UTC)
            if self._user_accounting is not None:
                try:
                    result = await self._user_accounting(username)
                    accounting = result.value
                    if not isinstance(accounting, AccountingSnapshot):
                        raise TypeError("Accounting source returned an invalid snapshot")
                    if accounting.lifetime.pod_uids != observation.pod_uids:
                        observation = replace(
                            observation,
                            accounting_state=AccountingState.INCOMPLETE,
                            accounting_stale=result.stale,
                        )
                    else:
                        uncovered = {
                            resource
                            for resource in accounting.lifetime.resources
                            if accounting.lifetime.coverage.get(resource, frozenset())
                            != observation.pod_uids
                        }
                        lifetime = replace(
                            accounting.lifetime,
                            resources={
                                resource: hours
                                for resource, hours in accounting.lifetime.resources.items()
                                if resource not in uncovered
                            },
                            incomplete=accounting.lifetime.incomplete
                            | {
                                resource: frozenset({LifetimeIssue.MISSING_SERIES})
                                for resource in uncovered
                            },
                        )
                        observation = replace(
                            observation,
                            accounting=lifetime,
                            accounting_state=(
                                AccountingState.COMPLETE
                                if lifetime.ready
                                else AccountingState.INCOMPLETE
                            ),
                            accounting_stale=result.stale,
                        )
                    created = min(created, accounting.created)
                except (
                    CacheUnavailable,
                    ProviderExecutionError,
                    ProviderUnavailableError,
                ):
                    logger.warning("User lifetime accounting unavailable")
                    observation = replace(
                        observation,
                        accounting_state=AccountingState.UNAVAILABLE,
                    )
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
                scope="user",
            )

    async def _load_community_snapshot(self, community: str) -> CachedSnapshot:
        started = perf_counter()
        status = "ok"
        try:
            observation = await self._timed_community_load(community)
            created = datetime.now(UTC)
            if self._community_accounting is not None:
                try:
                    result = await self._community_accounting(community)
                    accounting = result.value
                    if not isinstance(accounting, AccountingSnapshot):
                        raise TypeError("Accounting source returned an invalid snapshot")
                    if accounting.lifetime.pod_uids != observation.pod_uids:
                        observation = replace(
                            observation,
                            accounting_state=AccountingState.INCOMPLETE,
                            accounting_stale=result.stale,
                        )
                    else:
                        uncovered = {
                            resource
                            for resource in accounting.lifetime.resources
                            if accounting.lifetime.coverage.get(resource, frozenset())
                            != observation.pod_uids
                        }
                        lifetime = replace(
                            accounting.lifetime,
                            resources={
                                resource: hours
                                for resource, hours in accounting.lifetime.resources.items()
                                if resource not in uncovered
                            },
                            incomplete=accounting.lifetime.incomplete
                            | {
                                resource: frozenset({LifetimeIssue.MISSING_SERIES})
                                for resource in uncovered
                            },
                        )
                        observation = replace(
                            observation,
                            accounting=lifetime,
                            accounting_state=(
                                AccountingState.COMPLETE
                                if lifetime.ready
                                else AccountingState.INCOMPLETE
                            ),
                            accounting_stale=result.stale,
                        )
                    created = min(created, accounting.created)
                except (
                    CacheUnavailable,
                    ProviderExecutionError,
                    ProviderUnavailableError,
                ):
                    logger.warning("Community lifetime accounting unavailable")
                    observation = replace(
                        observation,
                        accounting_state=AccountingState.UNAVAILABLE,
                    )
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
                scope="community",
            )

    async def _timed_user_load(self, username: str) -> UserObservation:
        assert self._user is not None
        started = perf_counter()
        status = "ok"
        try:
            with self._metrics_recorder.span(
                "source.read",
                {
                    "metrics.scope": "user",
                    "provider.name": self._user_provider,
                    "source.operation": "read",
                },
            ):
                return await self._user(username)
        except (ProviderUnavailableError, ProviderExecutionError) as exc:
            status = "error"
            logger.warning("User metrics collection failed")
            raise AppError(
                code="user_metrics_unavailable",
                message="Could not load user metrics from Kubernetes",
                status_code=503,
            ) from exc
        finally:
            self._metrics_recorder.record_provider_duration(
                provider=self._user_provider,
                scope="user",
                status=status,
                seconds=perf_counter() - started,
            )

    async def _timed_community_load(self, community: str) -> CommunityObservation:
        assert self._community is not None
        started = perf_counter()
        status = "ok"
        try:
            with self._metrics_recorder.span(
                "source.read",
                {
                    "metrics.scope": "community",
                    "provider.name": self._user_provider,
                    "source.operation": "read",
                },
            ):
                return await self._community(community)
        except (ProviderUnavailableError, ProviderExecutionError) as exc:
            status = "error"
            logger.warning("Community metrics collection failed")
            raise AppError(
                code="community_metrics_unavailable",
                message="Could not load community metrics from Kubernetes",
                status_code=503,
            ) from exc
        finally:
            self._metrics_recorder.record_provider_duration(
                provider=self._user_provider,
                scope="community",
                status=status,
                seconds=perf_counter() - started,
            )

    async def _timed_platform_load(self) -> PlatformObservation:
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
