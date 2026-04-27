"""App-level :class:`MetricsRuntime`: sources, cache, HTTP clients, platform reads."""

from __future__ import annotations

import logging

import httpx
from pydantic import TypeAdapter
from redis.asyncio import Redis

from metrics.cache import InMemoryTTLCache, RedisJSONTTLCache, TTLCacheBackend
from metrics.core.provider_registry import build_platform_provider_bundle
from metrics.core.settings import Settings
from metrics.errors import RuntimeStartupError
from metrics.providers.base import Provider
from metrics.schemas.metrics import PlatformMetricsData
from metrics.services.platform import CachedMetrics, PlatformMetricsService, ServiceResult
from metrics.telemetry import MetricsRecorder, NoopMetricsRecorder

_logger = logging.getLogger(__name__)

_PLATFORM_CACHE_SCHEMA_VERSION = "4"


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
    """Selects the platform source, owns upstream clients, cache, and metric reads."""

    def __init__(self, settings: Settings) -> None:
        """Create an empty runtime; prefer :meth:`from_settings` or :meth:`for_injected_platform`.

        Args:
            settings: Validated :class:`Settings` for the process.
        """
        self._settings = settings
        self._platform_http_client: httpx.AsyncClient | None = None
        self._platform_provider: Provider | None = None
        self._redis: Redis | None = None
        self._platform: PlatformMetricsService | None = None
        self._recorder: MetricsRecorder = NoopMetricsRecorder()

    @classmethod
    def from_settings(cls, settings: Settings, *, recorder: MetricsRecorder) -> MetricsRuntime:
        """Wire cache, platform provider bundle, and :class:`PlatformMetricsService`.

        Constructs one long-lived ``httpx.AsyncClient`` for the active platform
        source (via the typed registry), builds the cache backend, and binds the
        platform loader. Does not run provider startup; call :meth:`start` during
        application lifespan.

        Args:
            settings: Validated application settings.
            recorder: Telemetry recorder for cache and provider timings.

        Returns:
            A fully wired runtime ready for :meth:`start`.
        """
        bundle = build_platform_provider_bundle(settings)
        cache, redis_client = build_cache_backend(settings)
        ttl = settings.cache.platform_ttl()
        fp = bundle.provider.cache_fingerprint()

        def cache_key() -> str:
            return platform_metrics_cache_key(
                cluster_name=settings.cluster_name,
                fingerprint=fp,
            )

        async def load_platform() -> PlatformMetricsData:
            return await bundle.provider.metrics().platform()

        platform_service = PlatformMetricsService(
            platform=load_platform,
            cache=cache,
            key=cache_key,
            telemetry=recorder,
            ttl=ttl,
            provider=bundle.provider.name,
        )
        runtime = cls(settings)
        runtime.set_recorder(recorder)
        runtime.wire(
            platform_client=bundle.http_client,
            platform_provider=bundle.provider,
            platform_service=platform_service,
            redis=redis_client,
        )
        return runtime

    @classmethod
    def for_injected_platform(
        cls,
        settings: Settings,
        platform_service: PlatformMetricsService,
        *,
        recorder: MetricsRecorder | None = None,
    ) -> MetricsRuntime:
        """Wrap a pre-built platform service (tests) without upstream HTTP clients."""
        runtime = cls(settings)
        runtime._platform = platform_service
        if recorder is not None:
            runtime.set_recorder(recorder)
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

    def set_recorder(self, recorder: MetricsRecorder) -> None:
        """Attach a :class:`MetricsRecorder` for cache and provider telemetry."""
        self._recorder = recorder

    @property
    def recorder(self) -> MetricsRecorder:
        """Active service-level metrics recorder (noop when OTel is off)."""
        return self._recorder

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
        platform_client: httpx.AsyncClient,
        platform_provider: Provider,
        platform_service: PlatformMetricsService,
        redis: Redis | None,
    ) -> None:
        """Inject runtime dependencies (advanced/testing); prefer :meth:`from_settings`.

        Args:
            platform_client: Long-lived client used by the platform metrics provider
                for upstream HTTP.
            platform_provider: Adapter that implements :class:`~metrics.providers.base.Provider`
                (startup checks and platform metrics).
            platform_service: Cached platform metrics service exposed to HTTP.
            redis: Optional Redis client when the cache backend is Redis; closed on
                shutdown.
        """
        self._platform_http_client = platform_client
        self._platform_provider = platform_provider
        self._platform = platform_service
        self._redis = redis

    async def start(self) -> None:
        """Run the active platform provider's startup checks when one is wired."""
        if self._platform_provider is None:
            return
        try:
            await self._platform_provider.startup()
        except RuntimeStartupError:
            _logger.exception("Platform provider startup validation failed")
            raise
        except Exception as exc:
            raise RuntimeStartupError(
                f"Unexpected error during platform provider startup: {exc}"
            ) from exc

    async def shutdown(self) -> None:
        """Close the platform provider (Adapter), then HTTP and Redis, then clear platform service.

        After this returns, :attr:`_platform` is ``None`` so :meth:`get_platform_metrics` and
        :attr:`platform_service` surface an invalid state (raises ``RuntimeError``) instead of
        reusing closed clients or a stale :class:`PlatformMetricsService` graph.
        """
        if self._platform_provider is not None:
            try:
                await self._platform_provider.shutdown()
            except Exception:
                _logger.exception("Platform provider shutdown failed; closing remaining resources")
        self._platform_provider = None
        if self._platform_http_client is not None:
            try:
                if not self._platform_http_client.is_closed:
                    await self._platform_http_client.aclose()
            finally:
                self._platform_http_client = None
        if self._redis is not None:
            try:
                await self._redis.aclose()
            finally:
                self._redis = None
        self._platform = None
