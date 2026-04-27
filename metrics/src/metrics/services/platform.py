"""Platform metrics: TTL cache and telemetry over a Kueue-backed platform loader."""

from __future__ import annotations

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

type MetricsData = PlatformMetricsData


@dataclass(slots=True)
class CachedMetrics:
    """JSON-serialisable snapshot and creation time stored in the TTL cache."""

    data: MetricsData
    created: datetime


@dataclass(slots=True)
class ServiceResult[T]:
    """Platform metrics payload with cache hit metadata for HTTP layers."""

    data: T
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
                (``bundle.provider.name`` at the :class:`MetricsRuntime` / registry Seam).
        """
        self._platform = platform
        self._cache = cache
        self._key = key
        self._metrics_recorder = telemetry or NoopMetricsRecorder()
        self._platform_ttl_seconds = ttl if ttl is not None else self._cache.ttl_seconds
        self._provider = provider

    @property
    def cache_ttl_seconds(self) -> int:
        """Effective TTL in seconds for ``Cache-Control`` and cache writes."""
        return self._platform_ttl_seconds

    async def get_platform_metrics(self) -> ServiceResult[PlatformMetricsData]:
        """Read platform metrics, using cache on hit and the loader on miss.

        Returns:
            Snapshot data, the snapshot creation time, and whether it came from cache.

        Raises:
            AppError: On provider unavailability (503) or execution failure (502), with
            details only in server logs.
        """
        cache_key = self._key()
        scope = "platform"
        cached = await self._cache.get(cache_key)
        if cached is not None:
            self._metrics_recorder.record_cache_lookup(
                backend=self._cache.backend_name,
                hit=True,
                scope=scope,
            )
            return ServiceResult(
                data=cached.data,
                created=cached.created,
                cached=True,
            )
        self._metrics_recorder.record_cache_lookup(
            backend=self._cache.backend_name,
            hit=False,
            scope=scope,
        )
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
