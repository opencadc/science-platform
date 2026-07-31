"""App-level :class:`MetricsRuntime`: provider lifecycle, cache, and platform reads."""

from __future__ import annotations

import asyncio
import logging
from typing import Protocol

from pydantic import TypeAdapter
from redis.asyncio import Redis

from metrics.cache import InMemoryTTLCache, RedisJSONTTLCache, TTLCacheBackend
from metrics.core.provider_registry import bind_platform_metrics, build_platform_provider
from metrics.core.settings import Settings
from metrics.errors import RuntimeStartupError
from metrics.providers.base import Provider
from metrics.schemas.metrics import PlatformMetricsData
from metrics.services.platform import CachedMetrics, PlatformMetricsService, ServiceResult
from metrics.telemetry import MetricsRecorder

_logger = logging.getLogger(__name__)

_PLATFORM_CACHE_SCHEMA_VERSION = "4"


class _AsyncCloser(Protocol):
    async def aclose(self) -> None:
        """Close an asynchronous resource."""


def platform_metrics_cache_key(*, cluster_name: str, fingerprint: str = "") -> str:
    """Build the app-level cache key for the platform metric scope.

    Args:
        cluster_name: Cluster identifier from settings.
        fingerprint: Optional provider fingerprint segment.

    Returns:
        Stable Redis/memory cache key for platform snapshots.
    """
    schema = _PLATFORM_CACHE_SCHEMA_VERSION
    fp = fingerprint.strip()
    if fp:
        return f"platform:{schema}:{cluster_name}:{fp}"
    return f"platform:{schema}:{cluster_name}"


def build_cache_backend(
    settings: Settings,
) -> tuple[TTLCacheBackend[CachedMetrics], Redis | None]:
    """Construct the TTL cache backend selected by ``settings.cache``."""
    ttl = settings.cache.platform_ttl()
    if settings.cache.backend == "memory":
        return (InMemoryTTLCache[CachedMetrics](ttl_seconds=ttl), None)

    redis_client = Redis.from_url(settings.redis_url)
    adapter = TypeAdapter(CachedMetrics)
    return (
        RedisJSONTTLCache[CachedMetrics](
            ttl_seconds=ttl,
            redis=redis_client,
            key_prefix=settings.redis_key_prefix,
            serializer=lambda value: adapter.dump_json(value).decode("utf-8"),
            deserializer=adapter.validate_json,
        ),
        redis_client,
    )


class MetricsRuntime:
    """Own the active provider, cache resources, and platform metric reads."""

    def __init__(self, settings: Settings) -> None:
        """Create an empty runtime; production callers use :meth:`from_settings`.

        Args:
            settings: Validated :class:`Settings` for the process.
        """
        self._settings = settings
        self._provider: Provider | None = None
        self._provider_started = False
        self._redis: _AsyncCloser | None = None
        self._platform: PlatformMetricsService | None = None

    @classmethod
    def from_settings(cls, settings: Settings, *, recorder: MetricsRecorder) -> MetricsRuntime:
        """Wire the provider, cache, and :class:`PlatformMetricsService`.

        The registry transfers its client-owning provider to this runtime. This
        method does not run provider startup; call :meth:`start` during the
        application lifespan.

        Args:
            settings: Validated application settings.
            recorder: Telemetry recorder for cache and provider timings.

        Returns:
            A fully wired runtime ready for :meth:`start`.
        """
        cache, redis_client = build_cache_backend(settings)
        provider = build_platform_provider(settings)
        platform = bind_platform_metrics(provider)
        ttl = settings.cache.platform_ttl()
        fp = provider.cache_fingerprint()

        def cache_key() -> str:
            return platform_metrics_cache_key(
                cluster_name=settings.cluster_name,
                fingerprint=fp,
            )

        async def load_platform() -> PlatformMetricsData:
            return await platform.platform()

        platform_service = PlatformMetricsService(
            platform=load_platform,
            cache=cache,
            key=cache_key,
            telemetry=recorder,
            ttl=ttl,
            provider=provider.name,
        )
        runtime = cls(settings)
        runtime.wire(
            provider=provider,
            platform_service=platform_service,
            redis=redis_client,
        )
        return runtime

    @property
    def platform_service(self) -> PlatformMetricsService:
        """Return the platform metrics service, once wired and available."""
        if self._platform is None:
            msg = "Platform service is not initialised for this runtime"
            raise RuntimeError(msg)
        return self._platform

    @property
    def settings(self) -> Settings:
        """Process settings associated with this runtime."""
        return self._settings

    @property
    def cache_ttl_seconds(self) -> int:
        """TTL used for ``Cache-Control`` on successful platform responses."""
        return self.platform_service.cache_ttl_seconds

    async def get_platform_metrics(self) -> ServiceResult[PlatformMetricsData]:
        """Return cached or fresh platform metrics (same contract as the inner service)."""
        return await self.platform_service.get_platform_metrics()

    def wire(
        self,
        *,
        provider: Provider,
        platform_service: PlatformMetricsService,
        redis: _AsyncCloser | None,
    ) -> None:
        """Inject runtime dependencies (advanced/testing); prefer :meth:`from_settings`.

        Args:
            provider: Active provider. It owns and closes its upstream client.
            platform_service: Cached platform metrics service exposed to HTTP.
            redis: Optional Redis client when the cache backend is Redis; closed on
                shutdown.
        """
        self._provider = provider
        self._platform = platform_service
        self._redis = redis

    async def start(self) -> None:
        """Start the active provider once, cleaning up all resources on failure."""
        if self._provider is None or self._provider_started:
            return
        try:
            await self._provider.startup()
            self._provider_started = True
        except asyncio.CancelledError:
            try:
                await self.shutdown()
            finally:
                raise
        except RuntimeStartupError:
            _logger.exception("Provider startup validation failed")
            await self.shutdown()
            raise
        except Exception as exc:
            _logger.exception("Unexpected provider startup failure")
            await self.shutdown()
            raise RuntimeStartupError("Unexpected error during metrics runtime startup") from exc

    async def shutdown(self) -> None:
        """Close each owned resource once without allowing one failure to skip another.

        After this returns, :attr:`_platform` is ``None`` so :meth:`get_platform_metrics` and
        :attr:`platform_service` surface an invalid state (raises ``RuntimeError``) instead of
        reusing closed resources or a stale :class:`PlatformMetricsService` graph.
        """
        cancellation: asyncio.CancelledError | None = None
        provider, self._provider = self._provider, None
        self._provider_started = False
        if provider is not None:
            try:
                await provider.shutdown()
            except asyncio.CancelledError as exc:
                cancellation = exc
            except Exception:
                _logger.exception("Platform provider shutdown failed; closing remaining resources")
        redis, self._redis = self._redis, None
        if redis is not None:
            try:
                await redis.aclose()
            except asyncio.CancelledError as exc:
                cancellation = cancellation or exc
            except Exception:
                _logger.exception("Redis shutdown failed")
        self._platform = None
        if cancellation is not None:
            raise cancellation
