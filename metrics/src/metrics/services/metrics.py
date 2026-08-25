"""Framework-neutral Metrics orchestration behind HTTP adapters."""

from __future__ import annotations

import logging
from collections.abc import Awaitable, Callable
from datetime import UTC, datetime
from time import perf_counter

from metrics.cache import CacheCoordinator, CacheIdentity, CacheUnavailable
from metrics.errors import AppError, ProviderExecutionError, ProviderUnavailableError
from metrics.services.models import (
    PLATFORM_SUBJECT,
    CachedSnapshot,
    MetricsResult,
    MetricsSubject,
    PlatformObservation,
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
        telemetry: MetricsRecorder | None = None,
        provider: str = "unknown",
    ) -> None:
        """Wire cache, platform loader, and optional telemetry.

        Args:
            platform: Async callable that fetches a fresh platform observation.
            cache: Required cache coordinator storing :class:`CachedSnapshot`.
            identity: Sync callable returning the platform cache identity.
            telemetry: Optional cache/provider timing recorder.
            provider: Adapter name for provider duration telemetry.
        """
        self._platform = platform
        self._cache = cache
        self._identity = identity
        self._metrics_recorder = telemetry or NoopMetricsRecorder()
        self._provider = provider

    @property
    def cache_ttl_seconds(self) -> int:
        """Return the Platform report freshness window."""
        return self._cache.policy.fresh_seconds

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
            if subject != PLATFORM_SUBJECT:
                raise AppError(
                    code="subject_unsupported",
                    message="Requested metrics subject is not supported",
                    status_code=404,
                )
            return await self._get_platform()

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

    async def _timed_platform_load(self) -> PlatformObservation:
        started = perf_counter()
        pstatus = "ok"
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
        except Exception as exc:
            pstatus = "error"
            if isinstance(exc, ProviderUnavailableError):
                logger.warning("Platform metrics unavailable")
                raise AppError(
                    code="platform_metrics_unavailable",
                    message="Could not load platform metrics from Kubernetes",
                    status_code=503,
                ) from exc
            if isinstance(exc, ProviderExecutionError):
                logger.error("Platform metrics collection failed")
                raise AppError(
                    code="platform_metrics_error",
                    message="Platform metrics collection failed",
                    status_code=503,
                ) from exc
            raise
        finally:
            self._metrics_recorder.record_provider_duration(
                provider=self._provider,
                scope="platform",
                status=pstatus,
                seconds=perf_counter() - started,
            )
