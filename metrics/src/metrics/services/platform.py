"""Platform metrics: TTL cache and telemetry over a Kueue-backed platform loader."""

from __future__ import annotations

import asyncio
import logging
from dataclasses import dataclass
from datetime import UTC, datetime
from time import perf_counter
from typing import Awaitable, Callable

from metrics.cache import TTLCacheBackend
from metrics.errors import AppError, ProviderExecutionError, ProviderUnavailableError
from metrics.schemas.metrics import PlatformMetricsData
from metrics.telemetry import MetricsRecorder, NoopMetricsRecorder

logger = logging.getLogger(__name__)


@dataclass(slots=True)
class CachedMetrics:
    """JSON-serialisable snapshot and creation time stored in the TTL cache."""

    data: PlatformMetricsData
    created: datetime


@dataclass(slots=True)
class ServiceResult:
    """Platform metrics payload with cache hit metadata for HTTP layers."""

    data: PlatformMetricsData
    created: datetime
    cached: bool


class PlatformMetricsService:
    """Cache-first platform metrics: Redis or memory, with provider error mapping."""

    def __init__(
        self,
        *,
        platform: Callable[[], Awaitable[PlatformMetricsData]],
        cache: TTLCacheBackend[CachedMetrics],
        key: Callable[[], str],
        telemetry: MetricsRecorder | None = None,
        ttl: int | None = None,
        provider: str = "unknown",
    ) -> None:
        """Wire cache, loader, and optional telemetry for platform scope reads.

        Args:
            platform: Async callable that fetches fresh :class:`PlatformMetricsData`.
            cache: Async TTL backend storing :class:`CachedMetrics`.
            key: Sync callable returning the cache key (owned by :class:`MetricsRuntime`).
            telemetry: Optional cache/provider timing recorder.
            ttl: Override cache TTL; defaults to the backend's TTL.
            provider: Adapter name for :meth:`MetricsRecorder.record_provider_duration`
                (the provider selected by :class:`MetricsRuntime`).
        """
        self._platform = platform
        self._cache = cache
        self._key = key
        self._metrics_recorder = telemetry or NoopMetricsRecorder()
        self._platform_ttl_seconds = ttl if ttl is not None else self._cache.ttl_seconds
        self._provider = provider
        self._inflight: dict[str, asyncio.Task[ServiceResult]] = {}

    @property
    def cache_ttl_seconds(self) -> int:
        """Effective TTL in seconds for ``Cache-Control`` and cache writes."""
        return self._platform_ttl_seconds

    async def get_platform_metrics(self) -> ServiceResult:
        """Read platform metrics, using cache on hit and the loader on miss.

        Concurrent cache misses coalesce onto one in-flight backend load per key
        (single-flight): every waiter shares that load's result or mapped error,
        so the provider sees at most one upstream request per miss window in this
        process. With the Redis backend the guarantee is per replica, not global.

        Returns:
            Snapshot data, the snapshot creation time, and whether it came from cache.

        Raises:
            AppError: On provider unavailability (503) or execution failure (502), with
            details only in server logs.
        """
        cache_key = self._key()
        cached = await self._cache.get(cache_key)
        if cached is not None:
            self._metrics_recorder.record_cache_lookup(
                backend=self._cache.backend_name,
                hit=True,
                scope="platform",
            )
            return ServiceResult(
                data=cached.data,
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
        # Shield: cancelling one waiter must not cancel the shared load for the rest.
        return await asyncio.shield(task)

    def _make_inflight_reaper(self, cache_key: str) -> Callable[[asyncio.Task], None]:
        def reap(done: asyncio.Task) -> None:
            if self._inflight.get(cache_key) is done:
                del self._inflight[cache_key]
            if not done.cancelled():
                done.exception()  # mark retrieved even if every waiter was cancelled

        return reap

    async def _load_and_cache(self, cache_key: str) -> ServiceResult:
        scope = "platform"
        started = perf_counter()
        status = "ok"
        try:
            data = await self._timed_platform_load()
            created = datetime.now(UTC)
            await self._cache.set(
                cache_key,
                CachedMetrics(data=data, created=created),
            )
            return ServiceResult(data=data, created=created, cached=False)
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

    async def _timed_platform_load(self) -> PlatformMetricsData:
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
