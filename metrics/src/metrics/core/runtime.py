"""App-level :class:`MetricsRuntime`: provider lifecycle, cache, and Metrics service."""

from __future__ import annotations

import asyncio
import logging
from time import perf_counter
from typing import Literal

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
from metrics.providers.kubernetes import KubernetesProvider
from metrics.providers.kueue import KueueProvider
from metrics.providers.promql import SOURCE_REVISION, PromQLProvider
from metrics.services.metrics import MetricsService
from metrics.services.models import AccountingSnapshot, CachedSnapshot
from metrics.telemetry import MetricsRecorder, NoopMetricsRecorder

_logger = logging.getLogger(__name__)

_SCHEMA_REVISION = "5"
_SOURCE_REVISION = "1"
_QUERY_REVISION = "0"
_ACCOUNTING_QUERY_REVISION = "1"


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
        subject_value="canfar",
        cluster=cluster_name,
        source=source,
        fingerprint=fingerprint.strip(),
    )


def build_cache(
    settings: Settings,
    recorder: MetricsRecorder | None = None,
    *,
    surface: Literal["platform", "user", "community"] = "platform",
    redis: Redis | None = None,
) -> tuple[CacheCoordinator[CachedSnapshot], Redis | None]:
    """Construct the configured cache coordinator and owned Redis client."""
    policy = FRESHNESS_POLICIES[surface]
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
    redis_client = redis or Redis.from_url(
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
        telemetry=recorder,
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
            telemetry=recorder,
        ),
        None if redis is not None else redis_client,
    )


def build_accounting_cache(
    settings: Settings,
    *,
    surface: Literal["user", "community"],
    redis: Redis | None,
    recorder: MetricsRecorder,
) -> CacheCoordinator[AccountingSnapshot]:
    """Build an accounting cache over the shared Redis connection."""
    policy = FRESHNESS_POLICIES[surface]
    if settings.cache.backend == "memory":
        return InMemoryCoordinator[AccountingSnapshot](
            policy=policy,
            created=lambda snapshot: snapshot.created,
        )
    if redis is None or settings.cache.key_secret is None:
        raise RuntimeStartupError("Accounting requires the configured Redis cache")
    secret = settings.cache.key_secret.get_secret_value().encode()
    store = RedisSnapshots[AccountingSnapshot](
        redis=redis,
        value_type=AccountingSnapshot,
        secret=secret,
        command_timeout=settings.cache.redis_command_timeout_seconds,
        retention_seconds=policy.retention_seconds,
        schema_revision=_SCHEMA_REVISION,
        source_revision=SOURCE_REVISION,
        query_revision=_ACCOUNTING_QUERY_REVISION,
        telemetry=recorder,
    )
    return RedisCoordinator[AccountingSnapshot](
        store=store,
        key_prefix=settings.redis_key_prefix,
        key_secret=secret,
        policy=policy,
        created=lambda snapshot: snapshot.created,
        fill_timeout=settings.cache.fill_timeout_seconds,
        cold_timeout=settings.cache.cold_get_timeout_seconds,
        max_l1_entries=settings.cache.l1_max_entries,
        max_fills=settings.cache.max_fills,
        telemetry=recorder,
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
        user_provider: KubernetesProvider | None = None,
        user_cache: CacheCoordinator[CachedSnapshot] | None = None,
        community_cache: CacheCoordinator[CachedSnapshot] | None = None,
        accounting_provider: PromQLProvider | None = None,
        accounting_caches: tuple[
            CacheCoordinator[AccountingSnapshot],
            CacheCoordinator[AccountingSnapshot],
        ]
        | None = None,
        redis: Redis | None = None,
        telemetry: MetricsRecorder | None = None,
    ) -> None:
        """Attach the provider, Metrics service, and optional Redis client.

        Production callers use :meth:`from_settings`; tests may inject doubles.

        Args:
            settings: Validated :class:`Settings` for the process.
            provider: Active provider; it owns its Kubernetes access handle.
            metrics_service: Shared Metrics service exposed to HTTP adapters.
            cache: Cache coordinator and readiness dependency.
            user_provider: Optional Kubernetes workload provider.
            user_cache: Cache coordinator using User freshness boundaries.
            community_cache: Cache coordinator using Community freshness boundaries.
            accounting_provider: Optional controlled PromQL source.
            accounting_caches: User and Community accounting coordinators.
            redis: Redis client when configured; closed on shutdown.
            telemetry: Bounded lifecycle and readiness recorder.
        """
        self._settings = settings
        self._providers = [
            current
            for current in (provider, user_provider, accounting_provider)
            if current is not None
        ]
        self._started = False
        self._cache = cache
        self._caches = tuple(
            current
            for current in (
                cache,
                user_cache,
                community_cache,
                *(accounting_caches or ()),
            )
            if current is not None
        )
        self._redis_coordinators = tuple(
            current for current in self._caches if isinstance(current, RedisCoordinator)
        )
        self._redis: Redis | None = redis
        self._metrics: MetricsService | None = metrics_service
        self._telemetry = telemetry or NoopMetricsRecorder()

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
        cache, redis_client = build_cache(settings, recorder)
        user_cache, _ = build_cache(
            settings,
            recorder,
            surface="user",
            redis=redis_client,
        )
        community_cache, _ = build_cache(
            settings,
            recorder,
            surface="community",
            redis=redis_client,
        )
        provider = KueueProvider(settings)
        user_provider = KubernetesProvider(settings)
        fingerprint = provider.cache_fingerprint()
        user_fingerprint = user_provider.cache_fingerprint()
        accounting_provider = None
        accounting_caches = None
        if settings.providers.promql.enabled:
            accounting_provider = PromQLProvider(settings, telemetry=recorder)
            accounting_caches = (
                build_accounting_cache(
                    settings, surface="user", redis=redis_client, recorder=recorder
                ),
                build_accounting_cache(
                    settings, surface="community", redis=redis_client, recorder=recorder
                ),
            )
            accounting_fingerprint = accounting_provider.cache_fingerprint()

            async def user_accounting(username, observed_at):
                return await accounting_caches[0].get_or_fill(
                    CacheIdentity(
                        subject_kind="user",
                        subject_value=username,
                        cluster=settings.cluster_name,
                        source=accounting_provider.name,
                        fingerprint=f"{accounting_fingerprint}:{observed_at.isoformat()}",
                    ),
                    lambda: accounting_provider.read_user(username, observed_at),
                )

            async def community_accounting(community, observed_at):
                return await accounting_caches[1].get_or_fill(
                    CacheIdentity(
                        subject_kind="community",
                        subject_value=community,
                        cluster=settings.cluster_name,
                        source=accounting_provider.name,
                        fingerprint=f"{accounting_fingerprint}:{observed_at.isoformat()}",
                    ),
                    lambda: accounting_provider.read_community(community, observed_at),
                )
        else:
            user_accounting = None
            community_accounting = None
        metrics_service = MetricsService(
            platform=provider.read_platform,
            cache=cache,
            identity=lambda: platform_cache_identity(
                cluster_name=settings.cluster_name,
                source=provider.name,
                fingerprint=fingerprint,
            ),
            user=user_provider.read_user,
            user_cache=user_cache,
            user_identity=lambda username: CacheIdentity(
                subject_kind="user",
                subject_value=username,
                cluster=settings.cluster_name,
                source=user_provider.name,
                fingerprint=user_fingerprint,
            ),
            user_accounting=user_accounting,
            community=user_provider.read_community,
            community_cache=community_cache,
            community_identity=lambda community: CacheIdentity(
                subject_kind="community",
                subject_value=community,
                cluster=settings.cluster_name,
                source=user_provider.name,
                fingerprint=user_fingerprint,
            ),
            community_accounting=community_accounting,
            telemetry=recorder,
            provider=provider.name,
            user_provider=user_provider.name,
        )
        return cls(
            settings,
            provider=provider,
            user_provider=user_provider,
            metrics_service=metrics_service,
            cache=cache,
            user_cache=user_cache,
            community_cache=community_cache,
            accounting_provider=accounting_provider,
            accounting_caches=accounting_caches,
            redis=redis_client,
            telemetry=recorder,
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
        return self._started and all(cache.available for cache in self._caches)

    async def start(self) -> None:
        """Start the active provider once, cleaning up all resources on failure."""
        if not self._providers or self._started:
            return
        started = perf_counter()
        outcome = "ok"
        try:
            with self._telemetry.span("application.startup"):
                for coordinator in self._redis_coordinators:
                    await coordinator.ping()
                for provider in self._providers:
                    await provider.startup()
                self._started = True
                self._telemetry.record_readiness(True)
                _logger.info("Runtime startup completed")
        except asyncio.CancelledError:
            outcome = "cancelled"
            try:
                await self.shutdown()
            finally:
                raise
        except RedisUnavailable:
            outcome = "error"
            _logger.error("Runtime startup validation failed")
            await self.shutdown()
            raise RuntimeStartupError("Required metrics dependency is unavailable") from None
        except RuntimeStartupError:
            outcome = "error"
            _logger.error("Provider startup validation failed")
            await self.shutdown()
            raise
        except Exception as exc:
            outcome = "error"
            _logger.error("Unexpected provider startup failure")
            await self.shutdown()
            raise RuntimeStartupError("Unexpected error during metrics runtime startup") from exc
        finally:
            self._telemetry.record_lifecycle(
                operation="startup",
                outcome=outcome,
                seconds=perf_counter() - started,
            )

    async def shutdown(self) -> None:
        """Close each owned resource once without allowing one failure to skip another.

        After this returns, :attr:`_metrics` is ``None`` so :attr:`metrics_service`
        surfaces an invalid state instead of reusing closed resources.
        """
        with self._telemetry.span("application.shutdown"):
            await self._shutdown()

    async def _shutdown(self) -> None:
        started = perf_counter()
        outcome = "ok"
        cancellation: asyncio.CancelledError | None = None
        self._telemetry.record_readiness(False)
        providers, self._providers = self._providers, []
        coordinators, self._redis_coordinators = self._redis_coordinators, ()
        self._started = False
        for provider in providers:
            try:
                await provider.shutdown()
            except asyncio.CancelledError as exc:
                outcome = "cancelled"
                cancellation = cancellation or exc
            except Exception:
                outcome = "error"
                _logger.error("Provider shutdown failed; closing remaining resources")
        for coordinator in coordinators:
            try:
                await coordinator.shutdown()
            except asyncio.CancelledError as exc:
                outcome = "cancelled"
                cancellation = cancellation or exc
            except Exception:
                outcome = "error"
                _logger.error("Cache coordinator shutdown failed; closing Redis")
        redis, self._redis = self._redis, None
        if redis is not None:
            try:
                await redis.aclose()
            except asyncio.CancelledError as exc:
                outcome = "cancelled"
                cancellation = cancellation or exc
            except Exception:
                outcome = "error"
                _logger.error("Redis shutdown failed")
        self._metrics = None
        self._telemetry.record_lifecycle(
            operation="shutdown",
            outcome=outcome,
            seconds=perf_counter() - started,
        )
        _logger.info("Runtime shutdown completed")
        if cancellation is not None:
            raise cancellation
