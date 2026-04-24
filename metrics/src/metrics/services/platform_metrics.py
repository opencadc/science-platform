"""Business logic for computing metrics payloads and coordinating providers."""

from __future__ import annotations

import asyncio
import hashlib
from dataclasses import dataclass
from datetime import UTC, datetime
from time import perf_counter
from typing import Awaitable, Callable, cast

from metrics.cache import TTLCacheBackend
from metrics.errors import AppError, ProviderExecutionError, ProviderUnavailableError
from metrics.providers.base import CapacityProvider, UsageProvider
from metrics.providers.kueue_platform import PlatformResourceMaps
from metrics.quantity import format_resource_amount
from metrics.schemas.metrics import (
    PlatformMetricsData,
    ResourceSnapshot,
    SessionMetricsData,
    UserMetricsData,
    UsageSnapshot,
    UtilizationSnapshot,
)
from metrics.telemetry import MetricsRecorder, NoopMetricsRecorder

type MetricsData = PlatformMetricsData | UserMetricsData | SessionMetricsData

_PLATFORM_CACHE_SCHEMA_VERSION = "3"


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
    """Compute platform, user, and session metrics with shared caching semantics."""

    def __init__(
        self,
        *,
        cluster_name: str,
        capacity_provider: CapacityProvider,
        usage_provider: UsageProvider,
        cache: TTLCacheBackend[CachedMetrics],
        metrics_recorder: MetricsRecorder | None = None,
        platform_resource_loader: Callable[[], Awaitable[PlatformResourceMaps]]
        | None = None,
        platform_cache_fingerprint: str | None = None,
    ) -> None:
        self._cluster_name = cluster_name
        self._capacity_provider = capacity_provider
        self._usage_provider = usage_provider
        self._cache = cache
        self._metrics_recorder = metrics_recorder or NoopMetricsRecorder()
        self._platform_resource_loader = platform_resource_loader
        self._platform_cache_fingerprint = platform_cache_fingerprint or ""

    @property
    def cluster_name(self) -> str:
        return self._cluster_name

    @property
    def cache_ttl_seconds(self) -> int:
        return self._cache.ttl_seconds

    async def get_platform_metrics(self) -> ServiceResult[PlatformMetricsData]:
        """Return cached or freshly computed platform metrics."""
        fp = self._platform_cache_fingerprint
        schema = _PLATFORM_CACHE_SCHEMA_VERSION
        cache_key = (
            f"platform:{schema}:{self._cluster_name}:{fp}"
            if fp
            else f"platform:{schema}:{self._cluster_name}"
        )
        scope = "platform"
        cached = await self._cache.get(cache_key)
        if cached is not None:
            self._metrics_recorder.record_cache_lookup(
                backend=self._cache.backend_name,
                hit=True,
                scope=scope,
            )
            return ServiceResult(
                data=cast(PlatformMetricsData, cached.data),
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
            if self._platform_resource_loader is not None:
                maps_result = await self._timed_provider_call(
                    provider="kueue",
                    scope=scope,
                    loader=self._platform_resource_loader,
                )
                maps = _resolve_provider_result(
                    result=maps_result,
                    unavailable_error_code="platform_metrics_unavailable",
                    unavailable_message="Could not load platform metrics from Kubernetes",
                    execution_error_code="platform_metrics_error",
                    execution_message="Platform metrics collection failed",
                )
                data = PlatformMetricsData(
                    cluster=self._cluster_name,
                    capacity=maps.capacity,
                    allocated=maps.allocated,
                )
            else:
                capacity_result, usage_result = await asyncio.gather(
                    self._timed_provider_call(
                        provider=self._capacity_provider.source_name,
                        scope=scope,
                        loader=self._capacity_provider.get_capacity,
                    ),
                    self._timed_provider_call(
                        provider=self._usage_provider.source_name,
                        scope=scope,
                        loader=self._usage_provider.get_usage,
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
                    unavailable_error_code="usage_unavailable",
                    unavailable_message="Could not calculate platform usage",
                    execution_error_code="usage_provider_error",
                    execution_message="Usage provider failed during execution",
                )
                data = PlatformMetricsData(
                    cluster=self._cluster_name,
                    capacity={
                        "cpu": format_resource_amount("cpu", capacity_value.cpu_cores),
                        "memory": format_resource_amount(
                            "memory", capacity_value.memory_gib
                        ),
                    },
                    allocated={
                        "cpu": format_resource_amount(
                            "cpu", usage_value.requested_cpu_cores
                        ),
                        "memory": format_resource_amount(
                            "memory", usage_value.requested_memory_gib
                        ),
                    },
                )

            created = datetime.now(UTC)
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

    async def get_user_metrics(self, user_id: str) -> ServiceResult[UserMetricsData]:
        cache_token = _cache_token(user_id)
        return await self._compute_metrics(
            scope="user",
            cache_key=f"user:{self._cluster_name}:{cache_token}",
            usage_loader=lambda: self._usage_provider.get_usage_for_user(user_id),
            data_builder=lambda capacity, usage: UserMetricsData(
                cluster=self._cluster_name,
                user_id=user_id,
                capacity=capacity,
                usage=usage,
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
            data_builder=lambda capacity, usage: SessionMetricsData(
                cluster=self._cluster_name,
                user_id=user_id,
                session_id=session_id,
                capacity=capacity,
                usage=usage,
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
        data_builder: Callable[[ResourceSnapshot, UsageSnapshot], T],
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
    return hashlib.sha256(value.encode("utf-8")).hexdigest()
