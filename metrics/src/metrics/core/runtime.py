"""Own Kueue, cache, and Metrics service lifecycle resources."""

from __future__ import annotations

import asyncio
import hashlib
import json
import logging
from datetime import datetime
from time import perf_counter
from typing import Any, Literal, Protocol, cast

from redis.asyncio import Redis

from metrics.cache import (
    FRESHNESS_POLICIES,
    CacheCoordinator,
    CacheIdentity,
    RedisCoordinator,
    RedisSnapshots,
    RedisUnavailable,
)
from metrics.core.settings import Settings
from metrics.errors import ProviderUnavailableError, RuntimeStartupError
from metrics.providers.kueue import KueueProvider
from metrics.providers.kubemetrics import KubeMetricsProvider
from metrics.providers.session import SessionProvider
from metrics.services.metrics import MetricsService
from metrics.services.models import (
    CachedSnapshot,
    EfficiencyObservation,
    MetricsSurface,
    SessionObservation,
)
from metrics.telemetry import MetricsRecorder, NoopMetricsRecorder


_logger = logging.getLogger(__name__)
_KUEUE_SURFACES: tuple[MetricsSurface, ...] = ("platform", "user", "community")
_SCHEMA_REVISION = "8"
_SOURCE_REVISION = "kueue-v2"
_QUERY_REVISION = "0"


class _ReadinessCoordinator(Protocol):
    """Define the cache health seam used by runtime readiness recovery."""

    backend_name: str
    available: bool

    async def ping(self) -> None:
        """Run one bounded cache health check."""


class _EfficiencyProvider(Protocol):
    """Define the optional PromQL efficiency provider lifecycle and loaders."""

    async def startup(self) -> None:
        """Start the optional efficiency provider client."""

    async def shutdown(self) -> None:
        """Close the optional efficiency provider client."""

    async def read_platform(self) -> EfficiencyObservation:
        """Read attributed platform efficiency."""

    async def read_user(self, username: str) -> EfficiencyObservation:
        """Read attributed user efficiency."""

    async def read_community(self, community: str) -> EfficiencyObservation:
        """Read attributed community efficiency."""

    async def read_session(
        self,
        session_id: str,
        *,
        start_time: datetime,
        window_end: datetime,
        observed_at: datetime | None = None,
    ) -> EfficiencyObservation:
        """Read attributed session duration efficiency."""


async def _close_resources(
    resources: tuple[Any, ...],
    failure_message: str,
) -> tuple[str, asyncio.CancelledError | None]:
    """Close resources independently so one failure cannot skip another."""
    outcome = "ok"
    cancellation: asyncio.CancelledError | None = None
    for resource in resources:
        try:
            await resource.shutdown()
        except asyncio.CancelledError as exc:
            outcome = "cancelled"
            cancellation = cancellation or exc
        except Exception:
            outcome = "error"
            _logger.error(failure_message)
    return outcome, cancellation


async def _close_redis(redis: Redis) -> tuple[str, asyncio.CancelledError | None]:
    """Close the shared Redis client while preserving cancellation."""
    try:
        await redis.aclose()
    except asyncio.CancelledError as exc:
        return "cancelled", exc
    except Exception:
        _logger.error("Redis shutdown failed")
        return "error", None
    return "ok", None


def _combine_shutdown_outcomes(*outcomes: str) -> str:
    """Prefer errors, then cancellation, over clean shutdown."""
    if "error" in outcomes:
        return "error"
    if "cancelled" in outcomes:
        return "cancelled"
    return "ok"


def platform_cache_identity(
    *,
    platform_name: str,
    cluster_name: str,
    source: str,
    fingerprint: str = "",
) -> CacheIdentity:
    """Build the opaque Platform cache identity."""
    return CacheIdentity(
        subject_kind="platform",
        subject_value=platform_name,
        cluster=cluster_name,
        source=source,
        fingerprint=fingerprint.strip(),
    )


def _subject_cache_identity(
    *,
    kind: Literal["user", "community", "session"],
    subject: str,
    cluster: str,
    source: str,
    fingerprint: str,
) -> CacheIdentity:
    """Build one opaque User, Community, or Session cache identity."""
    return CacheIdentity(
        subject_kind=kind,
        subject_value=subject,
        cluster=cluster,
        source=source,
        fingerprint=fingerprint,
    )


def build_cache(
    settings: Settings,
    recorder: MetricsRecorder | None = None,
    *,
    surface: Literal["platform", "user", "community", "session"] = "platform",
    redis: Redis | None = None,
) -> tuple[CacheCoordinator[CachedSnapshot], Redis | None]:
    """Construct one surface cache, reusing the supplied Redis client."""
    policy = FRESHNESS_POLICIES[surface]
    secret_bytes = settings.cache.key_secret.get_secret_value().encode()
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
            created=lambda snapshot: snapshot.observation.observed_at,
            fill_timeout=settings.cache.fill_timeout_seconds,
            cold_timeout=settings.cache.cold_get_timeout_seconds,
            max_l1_entries=settings.cache.l1_max_entries,
            telemetry=recorder,
        ),
        None if redis is not None else redis_client,
    )


def _build_efficiency_provider(
    settings: Settings,
    recorder: MetricsRecorder,
) -> _EfficiencyProvider | None:
    """Create the optional PromQL adapter only when an endpoint is supplied."""
    if settings.providers.promql.base_url is None:
        return None
    from metrics.providers.promql import PromQLProvider

    return PromQLProvider(settings, telemetry=recorder)


def _efficiency_cache_fingerprint(settings: Settings) -> str:
    """Hash the enabled PromQL configuration into the queue cache identity."""
    config = settings.providers.promql
    if config.base_url is None:
        return "disabled"
    raw = json.dumps(
        config.model_dump(mode="json", exclude_none=True),
        separators=(",", ":"),
        sort_keys=True,
    )
    return hashlib.sha256(raw.encode()).hexdigest()[:24]


class MetricsRuntime:
    """Own Kueue, Session, optional efficiency, four surface caches, and Metrics."""

    def __init__(
        self,
        settings: Settings,
        *,
        provider: KueueProvider,
        session_provider: SessionProvider,
        usage_provider: KubeMetricsProvider,
        metrics_service: MetricsService,
        cache: CacheCoordinator[CachedSnapshot],
        user_cache: CacheCoordinator[CachedSnapshot],
        community_cache: CacheCoordinator[CachedSnapshot],
        session_cache: CacheCoordinator[CachedSnapshot],
        redis: Redis | None = None,
        telemetry: MetricsRecorder | None = None,
        efficiency_provider: _EfficiencyProvider | None = None,
    ) -> None:
        """Attach injected resources for production or focused tests."""
        self._settings = settings
        self._provider = provider
        self._session_provider = session_provider
        self._usage_provider = usage_provider
        self._metrics: MetricsService | None = metrics_service
        self._readiness = metrics_service.readiness
        self._telemetry = telemetry or NoopMetricsRecorder()
        self._efficiency_provider = efficiency_provider
        self._started = False
        self._redis = redis
        self._caches = (cache, user_cache, community_cache, session_cache)
        self._redis_coordinators: tuple[_ReadinessCoordinator, ...] = tuple(
            cast(_ReadinessCoordinator, current)
            for current in self._caches
            if callable(getattr(current, "ping", None))
        )
        self._readiness_recovery: asyncio.Task[bool] | None = None

    @classmethod
    def from_settings(cls, settings: Settings, *, recorder: MetricsRecorder) -> MetricsRuntime:
        """Wire Kueue, Session, optional PromQL efficiency, shared Redis, and service."""
        provider = KueueProvider(settings)
        session_provider = SessionProvider(settings)
        usage_provider = KubeMetricsProvider(settings)
        efficiency_provider = _build_efficiency_provider(settings, recorder)
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
        session_cache, _ = build_cache(
            settings,
            recorder,
            surface="session",
            redis=redis_client,
        )
        fingerprint = provider.cache_fingerprint()
        fingerprint = (
            f"{fingerprint}:session-{session_provider.cache_fingerprint()}"
            f":promql-{_efficiency_cache_fingerprint(settings)}"
        )

        async def session_efficiency(observation: SessionObservation) -> EfficiencyObservation:
            if efficiency_provider is None or observation.start_time is None:
                raise ProviderUnavailableError("Session efficiency is not configured")
            return await efficiency_provider.read_session(
                observation.session,
                start_time=observation.start_time,
                window_end=observation.window_end,
                observed_at=observation.observed_at,
            )

        metrics_service = MetricsService(
            platform=provider.read_platform,
            cache=cache,
            identity=lambda: platform_cache_identity(
                platform_name=settings.platform_name,
                cluster_name=settings.cluster_name,
                source=provider.name,
                fingerprint=fingerprint,
            ),
            platform_name=settings.platform_name,
            user=provider.read_user,
            user_cache=user_cache,
            user_identity=lambda username: _subject_cache_identity(
                kind="user",
                subject=username,
                cluster=settings.cluster_name,
                source=provider.name,
                fingerprint=fingerprint,
            ),
            community=provider.read_community,
            community_cache=community_cache,
            community_identity=lambda community: _subject_cache_identity(
                kind="community",
                subject=community,
                cluster=settings.cluster_name,
                source=provider.name,
                fingerprint=fingerprint,
            ),
            session=session_provider.read_session,
            session_cache=session_cache,
            session_identity=lambda session_id: _subject_cache_identity(
                kind="session",
                subject=session_id,
                cluster=settings.cluster_name,
                source=session_provider.name,
                fingerprint=fingerprint,
            ),
            session_usage=usage_provider.read_session_usage,
            session_efficiency=session_efficiency if efficiency_provider is not None else None,
            telemetry=recorder,
            provider=provider.name,
            platform_efficiency=efficiency_provider.read_platform
            if efficiency_provider is not None
            else None,
            user_efficiency=efficiency_provider.read_user
            if efficiency_provider is not None
            else None,
            community_efficiency=efficiency_provider.read_community
            if efficiency_provider is not None
            else None,
            efficiency_timeout_seconds=min(
                settings.providers.promql.request_timeout_seconds,
                settings.cache.fill_timeout_seconds * 0.5,
            ),
        )
        return cls(
            settings,
            provider=provider,
            session_provider=session_provider,
            usage_provider=usage_provider,
            metrics_service=metrics_service,
            cache=cache,
            user_cache=user_cache,
            community_cache=community_cache,
            session_cache=session_cache,
            redis=redis_client,
            telemetry=recorder,
            efficiency_provider=efficiency_provider,
        )

    @property
    def metrics_service(self) -> MetricsService:
        """Return the active Metrics service."""
        if self._metrics is None:
            raise RuntimeError("Metrics service is not initialised for this runtime")
        return self._metrics

    @property
    def settings(self) -> Settings:
        """Return the settings associated with this runtime."""
        return self._settings

    @property
    def ready(self) -> bool:
        """Return readiness without probing dependencies."""
        if self._started and self._metrics is not None:
            self._metrics.sync_cache_readiness()
        return self._started and self._readiness.ready

    async def check_readiness(self) -> bool:
        """Recover failed cache or Kueue dependencies through one task."""
        if self.ready:
            return True
        if not self._started:
            return False
        recovery = self._readiness_recovery
        if recovery is None or recovery.done():
            recovery = asyncio.create_task(self._recover_readiness())
            self._readiness_recovery = recovery
        return await asyncio.shield(recovery)

    async def _recover_readiness(self) -> bool:
        """Ping caches and rerun the provider startup validation."""
        if not self._started:
            return False
        provider = self._provider
        session_provider = self._session_provider
        if provider is None or session_provider is None:
            return False
        if not await self._recover_cache_readiness():
            return False
        try:
            async with asyncio.timeout(self._settings.startup_validation_timeout_seconds):
                await provider.startup()
        except Exception:
            for surface in _KUEUE_SURFACES:
                self._readiness.mark_source(surface, reachable=False)
        else:
            for surface in _KUEUE_SURFACES:
                self._readiness.mark_source(surface, reachable=True)
        try:
            async with asyncio.timeout(self._settings.startup_validation_timeout_seconds):
                await session_provider.startup()
        except Exception:
            self._readiness.mark_source("session", reachable=False)
        else:
            self._readiness.mark_source("session", reachable=True)
        await self._start_efficiency_provider()
        self.metrics_service.sync_cache_readiness()
        return self.ready

    async def _recover_cache_readiness(self) -> bool:
        """Ping every Redis coordinator once and update cache state."""
        if not self._redis_coordinators:
            self.metrics_service.sync_cache_readiness()
            return self._readiness.cache_available
        try:
            async with asyncio.timeout(self._settings.cache.redis_command_timeout_seconds):
                results = await asyncio.gather(
                    *(coordinator.ping() for coordinator in self._redis_coordinators),
                    return_exceptions=True,
                )
        except TimeoutError:
            return False
        if any(isinstance(result, BaseException) for result in results):
            return False
        self.metrics_service.sync_cache_readiness()
        return self._readiness.cache_available

    async def _start_efficiency_provider(self) -> None:
        """Start PromQL best-effort so failures become partial reports."""
        if self._efficiency_provider is None:
            return
        try:
            async with asyncio.timeout(self._settings.providers.promql.request_timeout_seconds):
                await self._efficiency_provider.startup()
        except Exception as exc:
            _logger.warning("Optional PromQL efficiency provider unavailable: %s", exc)

    async def start(self) -> None:
        """Validate dependencies and start the runtime exactly once."""
        if self._started:
            return
        started = perf_counter()
        outcome = "ok"
        try:
            provider = self._provider
            session_provider = self._session_provider
            if provider is None or session_provider is None:
                raise RuntimeStartupError("Metrics runtime has already been shut down")
            async with asyncio.timeout(self._settings.startup_validation_timeout_seconds):
                for coordinator in self._redis_coordinators:
                    await coordinator.ping()
            self._started = True
            self._readiness.start()
            for surface in self._readiness.surfaces:
                self._readiness.mark_source(surface, reachable=False)
                self._readiness.mark_cache(surface, available=True)
            try:
                async with asyncio.timeout(self._settings.startup_validation_timeout_seconds):
                    await provider.startup()
            except Exception as exc:
                outcome = "degraded"
                _logger.warning("Kueue provider unavailable during startup: %s", exc)
                for surface in _KUEUE_SURFACES:
                    self._readiness.mark_source(surface, reachable=False)
            else:
                for surface in _KUEUE_SURFACES:
                    self._readiness.mark_source(surface, reachable=True)
            try:
                async with asyncio.timeout(self._settings.startup_validation_timeout_seconds):
                    await session_provider.startup()
            except Exception as exc:
                outcome = "degraded"
                _logger.warning("Session provider unavailable during startup: %s", exc)
                self._readiness.mark_source("session", reachable=False)
            else:
                if "session" in self._readiness.surfaces:
                    self._readiness.mark_source("session", reachable=True)
            await self._start_efficiency_provider()
            self._telemetry.record_readiness(self.ready)
        except asyncio.CancelledError:
            outcome = "cancelled"
            await self.shutdown()
            raise
        except RedisUnavailable as exc:
            outcome = "error"
            await self.shutdown()
            raise RuntimeStartupError("Required metrics dependency is unavailable") from exc
        except TimeoutError as exc:
            outcome = "error"
            await self.shutdown()
            raise RuntimeStartupError("Metrics dependency startup validation timed out") from exc
        except RuntimeStartupError:
            outcome = "error"
            await self.shutdown()
            raise
        except Exception as exc:
            outcome = "error"
            await self.shutdown()
            raise RuntimeStartupError("Unexpected error during metrics runtime startup") from exc
        finally:
            self._telemetry.record_lifecycle(
                operation="startup",
                outcome=outcome,
                seconds=perf_counter() - started,
            )

    async def shutdown(self) -> None:
        """Close provider, caches, and shared Redis resources."""
        started = perf_counter()
        recovery, self._readiness_recovery = self._readiness_recovery, None
        if recovery is not None:
            recovery.cancel()
            await asyncio.gather(recovery, return_exceptions=True)
        self._telemetry.record_readiness(False)
        self._readiness.stop()
        self._started = False
        provider, self._provider = self._provider, None  # type: ignore[assignment]
        session_provider, self._session_provider = self._session_provider, None
        usage_provider, self._usage_provider = self._usage_provider, None
        efficiency_provider, self._efficiency_provider = self._efficiency_provider, None
        caches, self._caches = self._caches, ()  # type: ignore[assignment]
        redis, self._redis = self._redis, None
        provider_outcome = "ok"
        provider_cancel: asyncio.CancelledError | None = None
        cache_outcome, cache_cancel = await _close_resources(caches, "Metrics cache shutdown failed")
        if provider is not None:
            provider_outcome, provider_cancel = await _close_resources(
                (provider, session_provider, usage_provider),
                "Metrics provider shutdown failed",
            )
        efficiency_outcome = "ok"
        efficiency_cancel: asyncio.CancelledError | None = None
        if efficiency_provider is not None:
            efficiency_outcome, efficiency_cancel = await _close_resources(
                (efficiency_provider,),
                "PromQL efficiency provider shutdown failed",
            )
        redis_outcome = "ok"
        redis_cancel: asyncio.CancelledError | None = None
        if redis is not None:
            redis_outcome, redis_cancel = await _close_redis(redis)
        self._metrics = None
        cancellation = provider_cancel or efficiency_cancel or cache_cancel or redis_cancel
        shutdown_outcome = _combine_shutdown_outcomes(
            provider_outcome,
            efficiency_outcome,
            cache_outcome,
            redis_outcome,
        )
        self._telemetry.record_lifecycle(
            operation="shutdown",
            outcome=shutdown_outcome,
            seconds=perf_counter() - started,
        )
        if shutdown_outcome == "ok" and cancellation is None:
            _logger.info("Runtime shutdown completed")
        if cancellation is not None:
            raise cancellation
