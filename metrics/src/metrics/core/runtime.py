"""Own the Metrics providers, caches, service, readiness, and lifecycle."""

from __future__ import annotations

import asyncio
import hashlib
import json
import logging
from collections.abc import Awaitable, Callable, Iterable, Mapping
from time import perf_counter
from typing import Protocol

from redis.asyncio import Redis
from redis.asyncio.retry import Retry
from redis.backoff import NoBackoff

from metrics.cache import (
    FRESHNESS_POLICIES,
    CacheCoordinator,
    CacheIdentity,
    RedisCoordinator,
    RedisSnapshots,
    RedisUnavailable,
    describe_failure,
)
from metrics.core.settings import LEASE_MARGIN_SECONDS, Settings
from metrics.errors import RuntimeStartupError
from metrics.providers.kubemetrics import KubeMetricsProvider
from metrics.providers.kueue import KueueProvider
from metrics.providers.promql import PromQLProvider
from metrics.providers.session import SessionProvider
from metrics.services.metrics import MetricsService
from metrics.services.models import CachedSnapshot, MetricsSurface
from metrics.services.snapshots import EfficiencySource, SnapshotLoader
from metrics.telemetry import MetricsRecorder, NoopMetricsRecorder

_logger = logging.getLogger(__name__)
_SURFACES: tuple[MetricsSurface, ...] = ("platform", "user", "community", "session")
_SCHEMA_REVISION = "9"
_SOURCE_REVISION = "kueue-v2"
_QUERY_REVISION = "0"


class _Kueue(Protocol):
    """Kueue provider operations the runtime drives."""

    async def validate_platform(self) -> None:
        """Prove the configured ClusterQueues are readable."""

    async def probe_local_queues(self) -> None:
        """Prove LocalQueue list access."""

    async def shutdown(self) -> None:
        """Release the provider."""


class _Probed(Protocol):
    """A provider with an access probe and a shutdown."""

    async def startup(self) -> None:
        """Prove access."""

    async def shutdown(self) -> None:
        """Release the provider."""


class _Closable(Protocol):
    """A resource with an asynchronous shutdown."""

    async def shutdown(self) -> None:
        """Release the resource."""


class _Efficiency(EfficiencySource, _Probed, Protocol):
    """The optional efficiency provider's reads and lifecycle."""


def cache_identity(
    kind: MetricsSurface,
    subject: str,
    *,
    cluster: str,
    source: str,
    fingerprint: str,
) -> CacheIdentity:
    """Build the opaque cache identity of one surface subject."""
    return CacheIdentity(
        subject_kind=kind,
        subject_value=subject,
        cluster=cluster,
        source=source,
        fingerprint=fingerprint,
    )


def build_redis(settings: Settings) -> Redis:
    """Create the one Redis client every surface cache shares; no I/O happens here."""
    return Redis.from_url(
        settings.redis_url.get_secret_value(),
        socket_connect_timeout=settings.cache.redis_command_timeout_seconds,
        socket_timeout=settings.cache.redis_command_timeout_seconds,
        # One immediate retry absorbs a dropped pooled connection. Every
        # script is safe to resend: OBSERVE recognises its own token and
        # SETTLE is token-fenced.
        retry=Retry(NoBackoff(), 1),
        client_name="canfar-metrics",
    )


def build_cache(
    settings: Settings,
    surface: MetricsSurface,
    redis: Redis,
    recorder: MetricsRecorder | None = None,
) -> RedisCoordinator[CachedSnapshot]:
    """Construct one surface cache over the shared Redis client."""
    secret = settings.cache.key_secret.get_secret_value().encode()
    store = RedisSnapshots[CachedSnapshot](
        redis=redis,
        value_type=CachedSnapshot,
        secret=secret,
        command_timeout=settings.cache.redis_command_timeout_seconds,
        schema_revision=_SCHEMA_REVISION,
        source_revision=_SOURCE_REVISION,
        query_revision=_QUERY_REVISION,
        telemetry=recorder,
    )
    return RedisCoordinator[CachedSnapshot](
        store=store,
        key_prefix=settings.redis_key_prefix,
        key_secret=secret,
        policy=FRESHNESS_POLICIES[surface],
        fill_timeout=settings.cache.fill_timeout_seconds,
        cold_timeout=settings.cache.cold_get_timeout_seconds,
        lease_margin=LEASE_MARGIN_SECONDS,
        failure_cooldown=settings.cache.failure_cooldown_seconds,
        max_l1_entries=settings.cache.l1_max_entries,
        telemetry=recorder,
    )


def _fingerprint(*parts: str) -> str:
    """Hash the source configuration that changes what a cached report means."""
    raw = json.dumps(parts, separators=(",", ":"))
    return hashlib.sha256(raw.encode()).hexdigest()[:24]


async def _close_all(
    closers: Iterable[Callable[[], Awaitable[object]]],
) -> tuple[str, BaseException | None]:
    """Run every closer even when one fails; report the outcome and any cancellation."""
    outcome = "ok"
    cancellation: BaseException | None = None
    for close in closers:
        try:
            await close()
        except asyncio.CancelledError as exc:
            outcome = "cancelled" if outcome == "ok" else outcome
            cancellation = cancellation or exc
        except Exception as exc:
            outcome = "error"
            _logger.error("shutdown step failed error=%s", describe_failure(exc))
    return outcome, cancellation


class MetricsRuntime:
    """Own the Metrics providers, caches, service, readiness, and lifecycle.

    Readiness latches: the runtime becomes ready once Redis and the Platform
    source (the configured ClusterQueues) have both been proven reachable,
    and stays ready afterwards. A later shared-dependency outage therefore
    degrades responses (stale data, then sanitized 503s) instead of removing
    every replica from the Service at once, while a new pod that cannot reach
    its dependencies never becomes ready and cannot replace a working one.
    """

    def __init__(
        self,
        settings: Settings,
        *,
        kueue: _Kueue,
        session: _Probed,
        usage: _Closable,
        service: MetricsService,
        efficiency: _Efficiency | None = None,
        redis: Redis | None = None,
        telemetry: MetricsRecorder | None = None,
    ) -> None:
        """Attach injected resources for production or focused tests."""
        self._settings = settings
        self._kueue = kueue
        self._session = session
        self._usage = usage
        self._efficiency = efficiency
        self._service = service
        self._redis = redis
        self._telemetry = telemetry or NoopMetricsRecorder()
        self._started = False
        self._closed = False
        self._validated = False
        self._validation: asyncio.Task[bool] | None = None

    @classmethod
    def from_settings(cls, settings: Settings, *, recorder: MetricsRecorder) -> MetricsRuntime:
        """Wire providers, one shared Redis client, four surface caches, and the service."""
        kueue = KueueProvider(settings)
        session = SessionProvider(settings)
        usage = KubeMetricsProvider(settings)
        efficiency = (
            PromQLProvider(settings, telemetry=recorder)
            if settings.providers.promql.base_url is not None
            else None
        )
        redis = build_redis(settings)
        caches = {surface: build_cache(settings, surface, redis, recorder) for surface in _SURFACES}
        fingerprint = _fingerprint(
            kueue.cache_fingerprint(),
            session.cache_fingerprint(),
            efficiency.cache_fingerprint() if efficiency is not None else "disabled",
        )

        def identity(kind: MetricsSurface, subject: str) -> CacheIdentity:
            return cache_identity(
                kind,
                subject,
                cluster=settings.cluster_name,
                source=session.name if kind == "session" else kueue.name,
                fingerprint=fingerprint,
            )

        loader = SnapshotLoader(
            kueue=kueue,
            session=session,
            usage=usage,
            efficiency=efficiency,
            usage_timeout_seconds=settings.providers.kueue.kube_request_timeout_seconds,
            efficiency_timeout_seconds=settings.providers.promql.request_timeout_seconds,
            telemetry=recorder,
        )
        service = MetricsService(
            platform_name=settings.platform_name,
            caches=caches,
            identity=identity,
            loader=loader,
            telemetry=recorder,
        )
        return cls(
            settings,
            kueue=kueue,
            session=session,
            usage=usage,
            service=service,
            efficiency=efficiency,
            redis=redis,
            telemetry=recorder,
        )

    @property
    def metrics_service(self) -> MetricsService:
        """Return the Metrics service."""
        return self._service

    @property
    def settings(self) -> Settings:
        """Return the settings associated with this runtime."""
        return self._settings

    @property
    def ready(self) -> bool:
        """Return whether this process has validated its dependencies and still serves."""
        return self._started and self._validated

    def _caches(self) -> Mapping[MetricsSurface, CacheCoordinator[CachedSnapshot]]:
        """Return the service's per-surface caches."""
        return self._service.caches

    async def _validate(self) -> bool:
        """Prove Redis and the Platform source reachable together, then latch readiness."""
        timeout = self._settings.startup_validation_timeout_seconds
        try:
            async with asyncio.timeout(timeout):
                await asyncio.gather(*(cache.ping() for cache in self._caches().values()))
                await self._kueue.validate_platform()
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            _logger.warning(
                "metrics runtime not ready: dependency validation failed error=%s",
                describe_failure(exc),
            )
            return False
        if self._started and not self._validated:
            self._validated = True
            self._telemetry.record_readiness(True)
            _logger.info("metrics runtime ready")
        return self.ready

    async def check_readiness(self) -> bool:
        """Return readiness, running one shared validation while not yet ready."""
        if self.ready:
            return True
        if not self._started:
            return False
        validation = self._validation
        if validation is None or validation.done():
            validation = asyncio.create_task(self._validate())
            self._validation = validation
        return await asyncio.shield(validation)

    async def _probe(self, name: str, probe: Callable[[], Awaitable[None]]) -> None:
        """Warn when a non-Platform surface's source access cannot be proven."""
        try:
            async with asyncio.timeout(self._settings.startup_validation_timeout_seconds):
                await probe()
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            _logger.warning(
                "%s access could not be verified at startup error=%s", name, describe_failure(exc)
            )

    async def start(self) -> None:
        """Require Redis, then validate sources; a Platform failure leaves the runtime unready."""
        if self._started:
            return
        if self._closed:
            raise RuntimeStartupError("Metrics runtime has already been shut down")
        started = perf_counter()
        outcome = "ok"
        try:
            async with asyncio.timeout(self._settings.startup_validation_timeout_seconds):
                await asyncio.gather(*(cache.ping() for cache in self._caches().values()))
            self._started = True
            self._telemetry.record_readiness(False)
            if self._efficiency is not None:
                await self._probe("PromQL efficiency", self._efficiency.startup)
            await asyncio.gather(
                self._probe("User LocalQueue", self._kueue.probe_local_queues),
                self._probe("Session Job", self._session.startup),
            )
            if not await self._validate():
                outcome = "degraded"
        except asyncio.CancelledError:
            outcome = "cancelled"
            await self.shutdown()
            raise
        except BaseException as exc:
            outcome = "error"
            await self.shutdown()
            if isinstance(exc, RedisUnavailable | TimeoutError):
                _logger.error("Redis is unavailable at startup error=%s", describe_failure(exc))
                raise RuntimeStartupError("Required Redis cache is unavailable") from exc
            raise
        finally:
            self._telemetry.record_lifecycle(
                operation="startup", outcome=outcome, seconds=perf_counter() - started
            )

    async def shutdown(self) -> None:
        """Close caches first, then providers and the shared Redis client."""
        if self._closed:
            return
        started = perf_counter()
        self._closed = True
        self._started = False
        validation, self._validation = self._validation, None
        if validation is not None:
            validation.cancel()
            await asyncio.gather(validation, return_exceptions=True)
        self._telemetry.record_readiness(False)
        closers: list[Callable[[], Awaitable[object]]] = [
            cache.shutdown for cache in self._caches().values()
        ]
        closers += [self._kueue.shutdown, self._session.shutdown, self._usage.shutdown]
        if self._efficiency is not None:
            closers.append(self._efficiency.shutdown)
        if self._redis is not None:
            closers.append(self._redis.aclose)
        outcome, cancellation = await _close_all(closers)
        self._telemetry.record_lifecycle(
            operation="shutdown", outcome=outcome, seconds=perf_counter() - started
        )
        if outcome == "ok":
            _logger.info("Runtime shutdown completed")
        if cancellation is not None:
            raise cancellation
