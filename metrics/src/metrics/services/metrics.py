"""Framework-neutral Metrics reads behind the HTTP adapters."""

from __future__ import annotations

import asyncio
from collections.abc import Awaitable, Callable, Mapping
from time import perf_counter

from metrics.cache import (
    CacheCoordinator,
    CacheIdentity,
    CacheNotFound,
    CacheUnavailable,
)
from metrics.errors import AppError
from metrics.services.models import CachedSnapshot, MetricsSubject, MetricsSurface, Report
from metrics.services.snapshots import SnapshotLoader
from metrics.telemetry import MetricsRecorder, NoopMetricsRecorder

IdentityFactory = Callable[[MetricsSurface, str], CacheIdentity]


class MetricsService:
    """Serve each surface's report from its cache, filling through one snapshot loader."""

    def __init__(
        self,
        *,
        platform_name: str,
        caches: Mapping[MetricsSurface, CacheCoordinator[CachedSnapshot]],
        identity: IdentityFactory,
        loader: SnapshotLoader,
        telemetry: MetricsRecorder | None = None,
    ) -> None:
        """Bind one cache and one snapshot fill per surface.

        Args:
            platform_name: The only Platform subject this deployment serves.
            caches: One coordinator per surface; each carries its own windows.
            identity: Builds the opaque cache identity for a surface subject.
            loader: Builds each surface's snapshot inside a cache fill.
            telemetry: Optional bounded metrics recorder.
        """
        self._platform_name = platform_name
        self._caches = dict(caches)
        self._identity = identity
        self._fills: dict[MetricsSurface, Callable[[str], Awaitable[CachedSnapshot]]] = {
            "platform": lambda _subject: loader.platform(),
            "user": loader.user,
            "community": loader.community,
            "session": loader.session,
        }
        self._telemetry = telemetry or NoopMetricsRecorder()

    @property
    def caches(self) -> Mapping[MetricsSurface, CacheCoordinator[CachedSnapshot]]:
        """Return the per-surface cache coordinators."""
        return self._caches

    async def get(self, subject: MetricsSubject) -> Report:
        """Return one cached or freshly filled report for ``subject``."""
        kind, value = subject.kind, subject.value
        started = perf_counter()
        status = "ok"
        try:
            if kind == "platform" and value != self._platform_name:
                raise AppError(code="platform_not_found", status_code=404)
            cache = self._caches[kind]
            fill = self._fills[kind]
            try:
                result = await cache.get_or_fill(self._identity(kind, value), lambda: fill(value))
            except CacheNotFound as exc:
                raise AppError(code=f"{kind}_not_found", status_code=404) from exc
            except CacheUnavailable as exc:
                if not exc.cache_available:
                    raise AppError(
                        code="metrics_cache_unavailable", status_code=503, retry_after=1
                    ) from exc
                raise AppError(code=f"{kind}_metrics_unavailable", status_code=503) from exc
            return Report(
                snapshot=result.value,
                cached=result.cached,
                stale=result.stale,
                cache_available=result.cache_available,
                age_seconds=result.age_seconds,
                fresh_seconds=cache.policy.fresh_seconds,
            )
        except asyncio.CancelledError:
            status = "cancelled"
            raise
        except AppError as exc:
            status = "not_found" if exc.status_code == 404 else "error"
            raise
        except Exception:
            status = "error"
            raise
        finally:
            self._telemetry.record_compute_duration(
                seconds=perf_counter() - started, status=status, scope=kind
            )
