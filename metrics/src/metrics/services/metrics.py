"""Framework-neutral Metrics orchestration behind HTTP adapters."""

from __future__ import annotations

import asyncio
import logging
from collections.abc import Awaitable, Callable
from datetime import UTC, datetime
from time import perf_counter

from metrics.cache import TTLCacheBackend
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
        cache: TTLCacheBackend[CachedSnapshot],
        key: Callable[[], str],
        telemetry: MetricsRecorder | None = None,
        ttl: int | None = None,
        provider: str = "unknown",
    ) -> None:
        """Wire cache, platform loader, and optional telemetry.

        Args:
            platform: Async callable that fetches a fresh platform observation.
            cache: Async TTL backend storing :class:`CachedSnapshot`.
            key: Sync callable returning the platform cache key.
            telemetry: Optional cache/provider timing recorder.
            ttl: Override cache TTL; defaults to the backend's TTL.
            provider: Adapter name for provider duration telemetry.
        """
        self._platform = platform
        self._cache = cache
        self._key = key
        self._metrics_recorder = telemetry or NoopMetricsRecorder()
        self._platform_ttl_seconds = ttl if ttl is not None else self._cache.ttl_seconds
        self._provider = provider
        self._inflight: dict[str, asyncio.Task[MetricsResult]] = {}

    @property
    def cache_ttl_seconds(self) -> int:
        """Effective TTL in seconds for ``Cache-Control`` and cache writes."""
        return self._platform_ttl_seconds

    async def get(self, subject: MetricsSubject) -> MetricsResult:
        """Return Metrics for ``subject``, using cache on hit and the source on miss.

        Concurrent cache misses coalesce onto one in-flight backend load per key
        (single-flight). Cancelling one waiter does not cancel the shared load.

        Args:
            subject: Subject selector. Only ``platform`` is supported in package 02.

        Returns:
            Observation, snapshot creation time, and whether it came from cache.

        Raises:
            AppError: Unsupported subject, provider unavailability (503), or
                execution failure (502). Details stay in server logs.
        """
        if subject.kind != PLATFORM_SUBJECT.kind:
            raise AppError(
                code="subject_unsupported",
                message="Requested metrics subject is not supported",
                status_code=404,
            )
        return await self._get_platform()

    async def _get_platform(self) -> MetricsResult:
        cache_key = self._key()
        cached = await self._cache.get(cache_key)
        if cached is not None:
            self._metrics_recorder.record_cache_lookup(
                backend=self._cache.backend_name,
                hit=True,
                scope="platform",
            )
            return MetricsResult(
                observation=cached.observation,
                created=cached.created,
                cached=True,
            )
        self._metrics_recorder.record_cache_lookup(
            backend=self._cache.backend_name,
            hit=False,
            scope="platform",
        )
        task = self._inflight.get(cache_key)
        if task is None or task.done():
            task = asyncio.create_task(self._load_and_cache(cache_key))
            self._inflight[cache_key] = task
            task.add_done_callback(self._make_inflight_reaper(cache_key))
        return await asyncio.shield(task)

    def _make_inflight_reaper(self, cache_key: str) -> Callable[[asyncio.Task], None]:
        def reap(done: asyncio.Task) -> None:
            if self._inflight.get(cache_key) is done:
                del self._inflight[cache_key]
            if not done.cancelled():
                done.exception()

        return reap

    async def _load_and_cache(self, cache_key: str) -> MetricsResult:
        scope = "platform"
        started = perf_counter()
        status = "ok"
        try:
            observation = await self._timed_platform_load()
            created = datetime.now(UTC)
            await self._cache.set(
                cache_key,
                CachedSnapshot(observation=observation, created=created),
            )
            return MetricsResult(observation=observation, created=created, cached=False)
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
            return await self._platform()
        except Exception as exc:
            pstatus = exc.__class__.__name__
            if isinstance(exc, ProviderUnavailableError):
                logger.warning(
                    "Platform metrics unavailable: %s",
                    exc,
                    exc_info=exc,
                )
                raise AppError(
                    code="platform_metrics_unavailable",
                    message="Could not load platform metrics from Kubernetes",
                    status_code=503,
                ) from exc
            if isinstance(exc, ProviderExecutionError):
                logger.error(
                    "Platform metrics collection failed: %s",
                    exc,
                    exc_info=exc,
                )
                raise AppError(
                    code="platform_metrics_error",
                    message="Platform metrics collection failed",
                    status_code=502,
                ) from exc
            raise
        finally:
            self._metrics_recorder.record_provider_duration(
                provider=self._provider,
                scope="platform",
                status=pstatus,
                seconds=perf_counter() - started,
            )
