"""App-level :class:`MetricsRuntime`: provider lifecycle, cache, and Metrics service."""

from __future__ import annotations

import asyncio
import logging

from redis.asyncio import Redis

from metrics.cache import (
    FRESHNESS_POLICIES,
    CacheCoordinator,
    CacheIdentity,
    InMemoryCoordinator,
    RedisCoordinator,
    RedisSnapshots,
    RedisUnavailable,
)
from metrics.core.settings import Settings
from metrics.errors import RuntimeStartupError
from metrics.providers.kueue import KueueProvider
from metrics.services.metrics import MetricsService
from metrics.services.models import CachedSnapshot
from metrics.telemetry import MetricsRecorder

_logger = logging.getLogger(__name__)

_SCHEMA_REVISION = "4"
_SOURCE_REVISION = "1"
_QUERY_REVISION = "0"


def platform_cache_identity(
    *,
    cluster_name: str,
    source: str,
    fingerprint: str = "",
) -> CacheIdentity:
    """Build the cache identity for Platform observations.

    Args:
        cluster_name: Cluster identifier from settings.
        source: Active source adapter name.
        fingerprint: Optional provider configuration fingerprint.

    Returns:
        Stable dimensions passed to the opaque key builder.
    """
    return CacheIdentity(
        subject_kind="platform",
        subject_value="",
        cluster=cluster_name,
        source=source,
        fingerprint=fingerprint.strip(),
    )


def build_cache(
    settings: Settings,
) -> tuple[CacheCoordinator[CachedSnapshot], Redis | None]:
    """Construct the configured cache coordinator and owned Redis client."""
    policy = FRESHNESS_POLICIES["platform"]
    if settings.cache.backend == "memory":
        return (
            InMemoryCoordinator[CachedSnapshot](
                policy=policy,
                created=lambda snapshot: snapshot.created,
            ),
            None,
        )

    secret = settings.cache.key_secret
    if secret is None:  # Settings validation owns the user-facing error.
        raise RuntimeStartupError("Redis cache key secret is not configured")
    secret_bytes = secret.get_secret_value().encode()
    redis_client = Redis.from_url(
        settings.redis_url,
        socket_connect_timeout=settings.cache.redis_command_timeout_seconds,
        socket_timeout=settings.cache.redis_command_timeout_seconds,
    )
    store = RedisSnapshots[CachedSnapshot](
        redis=redis_client,
        value_type=CachedSnapshot,
        secret=secret_bytes,
        command_timeout=settings.cache.redis_command_timeout_seconds,
        retention_seconds=policy.retention_seconds,
        schema_revision=_SCHEMA_REVISION,
        source_revision=_SOURCE_REVISION,
        query_revision=_QUERY_REVISION,
    )
    return (
        RedisCoordinator[CachedSnapshot](
            store=store,
            key_prefix=settings.redis_key_prefix,
            key_secret=secret_bytes,
            policy=policy,
            created=lambda snapshot: snapshot.created,
            fill_timeout=settings.cache.fill_timeout_seconds,
            cold_timeout=settings.cache.cold_get_timeout_seconds,
            max_l1_entries=settings.cache.l1_max_entries,
            max_fills=settings.cache.max_fills,
        ),
        redis_client,
    )


class MetricsRuntime:
    """Own the active provider, cache resources, and Metrics service."""

    def __init__(
        self,
        settings: Settings,
        *,
        provider: KueueProvider,
        metrics_service: MetricsService,
        cache: CacheCoordinator[CachedSnapshot],
        redis: Redis | None = None,
    ) -> None:
        """Attach the provider, Metrics service, and optional Redis client.

        Production callers use :meth:`from_settings`; tests may inject doubles.

        Args:
            settings: Validated :class:`Settings` for the process.
            provider: Active provider; it owns its Kubernetes access handle.
            metrics_service: Shared Metrics service exposed to HTTP adapters.
            cache: Cache coordinator and readiness dependency.
            redis: Redis client when configured; closed on shutdown.
        """
        self._settings = settings
        self._provider: KueueProvider | None = provider
        self._provider_started = False
        self._cache = cache
        self._redis: Redis | None = redis
        self._metrics: MetricsService | None = metrics_service

    @classmethod
    def from_settings(cls, settings: Settings, *, recorder: MetricsRecorder) -> MetricsRuntime:
        """Wire the Kueue provider, cache, and :class:`MetricsService`.

        This method does not run provider startup; call :meth:`start` during the
        application lifespan.

        Args:
            settings: Validated application settings.
            recorder: Telemetry recorder for cache and provider timings.

        Returns:
            A fully wired runtime ready for :meth:`start`.
        """
        cache, redis_client = build_cache(settings)
        provider = KueueProvider(settings)
        fingerprint = provider.cache_fingerprint()
        metrics_service = MetricsService(
            platform=provider.read_platform,
            cache=cache,
            identity=lambda: platform_cache_identity(
                cluster_name=settings.cluster_name,
                source=provider.name,
                fingerprint=fingerprint,
            ),
            telemetry=recorder,
            provider=provider.name,
        )
        return cls(
            settings,
            provider=provider,
            metrics_service=metrics_service,
            cache=cache,
            redis=redis_client,
        )

    @property
    def metrics_service(self) -> MetricsService:
        """Return the shared Metrics service, once wired and available."""
        if self._metrics is None:
            msg = "Metrics service is not initialised for this runtime"
            raise RuntimeError(msg)
        return self._metrics

    @property
    def settings(self) -> Settings:
        """Process settings associated with this runtime."""
        return self._settings

    @property
    def ready(self) -> bool:
        """Whether Redis is reachable and the provider completed startup."""
        return self._cache.available and self._provider_started

    async def start(self) -> None:
        """Start the active provider once, cleaning up all resources on failure."""
        if self._provider is None or self._provider_started:
            return
        try:
            if isinstance(self._cache, RedisCoordinator):
                await self._cache.ping()
            await self._provider.startup()
            self._provider_started = True
        except asyncio.CancelledError:
            try:
                await self.shutdown()
            finally:
                raise
        except RedisUnavailable:
            _logger.exception("Runtime startup validation failed")
            await self.shutdown()
            raise RuntimeStartupError("Required metrics dependency is unavailable") from None
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

        After this returns, :attr:`_metrics` is ``None`` so :attr:`metrics_service`
        surfaces an invalid state instead of reusing closed resources.
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
        self._metrics = None
        if cancellation is not None:
            raise cancellation
