"""Business logic for computing platform usage metrics."""

from __future__ import annotations

import asyncio
from dataclasses import dataclass
from datetime import UTC, datetime
from time import perf_counter
from typing import Awaitable, Callable, cast

from metrics.cache import TTLCacheBackend
from metrics.errors import AppError, ProviderExecutionError, ProviderUnavailableError
from metrics.models import (
    PlatformMetricsData,
    ResourceSnapshot,
    SessionMetricsData,
    UserMetricsData,
    UsageSnapshot,
    UtilizationSnapshot,
)
from metrics.providers.base import CapacityProvider, UsageProvider
from metrics.telemetry import MetricsRecorder, NoopMetricsRecorder

type MetricsData = PlatformMetricsData | UserMetricsData | SessionMetricsData


@dataclass(slots=True)
class CachedMetrics:
    """Cached payload with metric creation timestamp."""

    data: MetricsData
    created: datetime


@dataclass(slots=True)
class ServiceResult[T]:
    """Service response wrapper with cache and creation metadata."""

    data: T
    created: datetime
    cached: bool


class PlatformMetricsService:
    """Compute platform metrics from capacity and usage providers."""

    def __init__(
        self,
        *,
        cluster_name: str,
        capacity_provider: CapacityProvider,
        usage_provider: UsageProvider,
        cache: TTLCacheBackend[CachedMetrics],
        metrics_recorder: MetricsRecorder | None = None,
    ) -> None:
        self._cluster_name = cluster_name
        self._capacity_provider = capacity_provider
        self._usage_provider = usage_provider
        self._cache = cache
        self._metrics_recorder = metrics_recorder or NoopMetricsRecorder()

    @property
    def cluster_name(self) -> str:
        return self._cluster_name

    @property
    def cache_ttl_seconds(self) -> int:
        return self._cache.ttl_seconds

    async def get_platform_metrics(self) -> ServiceResult[PlatformMetricsData]:
        return await self._compute_metrics(
            scope="platform",
            cache_key=f"platform:{self._cluster_name}",
            usage_loader=self._usage_provider.get_usage,
            data_builder=lambda capacity, usage, sources: PlatformMetricsData(
                cluster=self._cluster_name,
                capacity=capacity,
                usage=usage,
                sources=sources,
            ),
            unavailable_error_code="usage_unavailable",
            unavailable_message="Could not calculate platform usage",
            execution_error_code="usage_provider_error",
            execution_message="Usage provider failed during execution",
        )

    async def get_user_metrics(self, user_id: str) -> ServiceResult[UserMetricsData]:
        cache_token = _cache_token(user_id)
        return await self._compute_metrics(
            scope="user",
            cache_key=f"user:{self._cluster_name}:{cache_token}",
            usage_loader=lambda: self._usage_provider.get_usage_for_user(user_id),
            data_builder=lambda capacity, usage, sources: UserMetricsData(
                cluster=self._cluster_name,
                user_id=user_id,
                capacity=capacity,
                usage=usage,
                sources=sources,
            ),
            unavailable_error_code="user_usage_unavailable",
            unavailable_message="Could not calculate user usage",
            execution_error_code="user_usage_provider_error",
            execution_message="User usage provider failed during execution",
        )

    async def get_session_metrics(
        self,
        *,
        user_id: str,
        session_id: str,
    ) -> ServiceResult[SessionMetricsData]:
        user_token = _cache_token(user_id)
        session_token = _cache_token(session_id)
        return await self._compute_metrics(
            scope="session",
            cache_key=f"session:{self._cluster_name}:{user_token}:{session_token}",
            usage_loader=lambda: self._usage_provider.get_usage_for_session(
                user_id,
                session_id,
            ),
            data_builder=lambda capacity, usage, sources: SessionMetricsData(
                cluster=self._cluster_name,
                user_id=user_id,
                session_id=session_id,
                capacity=capacity,
                usage=usage,
                sources=sources,
            ),
            unavailable_error_code="session_usage_unavailable",
            unavailable_message="Could not calculate session usage",
            execution_error_code="session_usage_provider_error",
            execution_message="Session usage provider failed during execution",
        )

    async def _compute_metrics[T](
        self,
        *,
        scope: str,
        cache_key: str,
        usage_loader: Callable[[], Awaitable],
        data_builder: Callable[[ResourceSnapshot, UsageSnapshot, list[str]], T],
        unavailable_error_code: str,
        unavailable_message: str,
        execution_error_code: str,
        execution_message: str,
    ) -> ServiceResult[T]:
        cached = await self._cache.get(cache_key)
        if cached is not None:
            self._metrics_recorder.record_cache_lookup(
                backend=self._cache.backend_name,
                hit=True,
                scope=scope,
            )
            return ServiceResult(
                data=cast(T, cached.data),
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
            capacity_result, usage_result = await asyncio.gather(
                self._timed_provider_call(
                    provider=self._capacity_provider.source_name,
                    scope=scope,
                    loader=self._capacity_provider.get_capacity,
                ),
                self._timed_provider_call(
                    provider=self._usage_provider.source_name,
                    scope=scope,
                    loader=usage_loader,
                ),
                return_exceptions=True,
            )

            capacity_value = _resolve_provider_result(
                result=capacity_result,
                unavailable_error_code="capacity_unavailable",
                unavailable_message="Could not calculate platform capacity",
                execution_error_code="capacity_provider_error",
                execution_message="Capacity provider failed during execution",
            )
            usage_value = _resolve_provider_result(
                result=usage_result,
                unavailable_error_code=unavailable_error_code,
                unavailable_message=unavailable_message,
                execution_error_code=execution_error_code,
                execution_message=execution_message,
            )

            utilization_cpu = _safe_ratio(
                usage_value.requested_cpu_cores,
                capacity_value.cpu_cores,
            )
            utilization_memory = _safe_ratio(
                usage_value.requested_memory_gib,
                capacity_value.memory_gib,
            )

            capacity_snapshot = ResourceSnapshot(
                cpu=_format_decimal(capacity_value.cpu_cores),
                memory=f"{_format_decimal(capacity_value.memory_gib)} GiB",
                ephemeral_memory="0 GiB",
                gpu="0",
            )
            usage_snapshot = UsageSnapshot(
                requested=ResourceSnapshot(
                    cpu=_format_decimal(usage_value.requested_cpu_cores),
                    memory=f"{_format_decimal(usage_value.requested_memory_gib)} GiB",
                    ephemeral_memory="0 GiB",
                    gpu="0",
                ),
                utilization=UtilizationSnapshot(
                    cpu=utilization_cpu,
                    memory=utilization_memory,
                    ephemeral_memory=0.0,
                    gpu=0.0,
                ),
            )
            created = datetime.now(UTC)
            data = data_builder(
                capacity_snapshot,
                usage_snapshot,
                [capacity_value.source, usage_value.source],
            )
            await self._cache.set(
                cache_key,
                CachedMetrics(
                    data=data,
                    created=created,
                ),
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

    async def _timed_provider_call(
        self,
        *,
        provider: str,
        scope: str,
        loader: Callable[[], Awaitable],
    ):
        started = perf_counter()
        status = "ok"
        try:
            return await loader()
        except Exception as exc:
            status = exc.__class__.__name__
            raise
        finally:
            self._metrics_recorder.record_provider_duration(
                provider=provider,
                scope=scope,
                status=status,
                seconds=perf_counter() - started,
            )


def _resolve_provider_result[T](
    *,
    result: T | Exception,
    unavailable_error_code: str,
    unavailable_message: str,
    execution_error_code: str,
    execution_message: str,
) -> T:
    if isinstance(result, ProviderUnavailableError):
        raise AppError(
            code=unavailable_error_code,
            message=unavailable_message,
            status_code=503,
            details={"cause": str(result)},
        ) from result
    if isinstance(result, (ProviderExecutionError, Exception)):
        raise AppError(
            code=execution_error_code,
            message=execution_message,
            status_code=502,
            details={"cause": str(result)},
        ) from result
    return result


def _safe_ratio(numerator: float, denominator: float) -> float:
    if denominator <= 0:
        return 0.0
    return min(max(numerator / denominator, 0.0), 1.0)


def _format_decimal(value: float) -> str:
    return f"{value:.6f}".rstrip("0").rstrip(".") or "0"


def _cache_token(value: str) -> str:
    return value.replace(":", "_").replace("/", "_").replace(" ", "_")
