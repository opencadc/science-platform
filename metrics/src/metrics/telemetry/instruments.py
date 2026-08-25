"""Expose bounded application metrics and spans behind a no-op-safe recorder.

Callers use one interface regardless of whether OpenTelemetry is enabled.
Attribute values are deliberately chosen by the application to avoid
high-cardinality subjects or upstream details.
"""

from __future__ import annotations

from collections.abc import Iterator, Mapping
from contextlib import contextmanager
from typing import Any

from opentelemetry import trace
from opentelemetry.metrics import Meter
from opentelemetry.trace import Tracer

_SECONDS_BUCKETS = (
    0.005,
    0.01,
    0.025,
    0.05,
    0.075,
    0.1,
    0.25,
    0.5,
    0.75,
    1.0,
    2.5,
    5.0,
    7.5,
    10.0,
)


class MetricsRecorder:
    """Provide the recorder contract and no-op behavior for disabled telemetry."""

    @contextmanager
    def span(self, name: str, attributes: Mapping[str, str] | None = None) -> Iterator[Any]:
        """Enter a no-op operation span.

        Args:
            name: Bounded operation name.
            attributes: Optional bounded application-owned attributes.

        Yields:
            The OpenTelemetry invalid span.
        """
        del name, attributes
        yield trace.INVALID_SPAN

    def record_cache_lookup(
        self,
        *,
        backend: str,
        result: str = "hit",
        scope: str,
        age_seconds: float | None = None,
        hit: bool | None = None,
    ) -> None:
        """Record one bounded cache outcome.

        Args:
            backend: Cache implementation name.
            result: Bounded hit, miss, or stale result.
            scope: Metrics subject kind.
            age_seconds: Optional non-negative snapshot age.
            hit: Legacy boolean override for the result.
        """

    def record_lease(self, *, outcome: str, scope: str) -> None:
        """Record a distributed lease outcome for one subject scope.

        Args:
            outcome: Bounded acquisition result.
            scope: Metrics subject kind.
        """

    def record_fill_duration(self, *, seconds: float, outcome: str, scope: str) -> None:
        """Record cache fill duration and bounded outcome.

        Args:
            seconds: Elapsed fill time.
            outcome: Bounded fill result.
            scope: Metrics subject kind.
        """

    def record_compute_duration(self, *, seconds: float, status: str, scope: str) -> None:
        """Record end-to-end source and fill duration.

        Args:
            seconds: Elapsed computation time.
            status: Bounded completion status.
            scope: Metrics subject kind.
        """

    def record_provider_duration(
        self,
        *,
        provider: str,
        scope: str,
        status: str,
        seconds: float,
    ) -> None:
        """Record one provider operation's duration and result.

        Args:
            provider: Stable provider name.
            scope: Metrics subject kind or controlled query scope.
            status: Bounded completion status.
            seconds: Elapsed provider time.
        """

    def record_redis(
        self,
        *,
        operation: str,
        outcome: str,
        seconds: float,
    ) -> None:
        """Record one bounded Redis operation and its duration.

        Args:
            operation: Stable Redis operation name.
            outcome: Bounded operation result.
            seconds: Elapsed command time.
        """

    def record_lifecycle(self, *, operation: str, outcome: str, seconds: float) -> None:
        """Record startup or shutdown duration and outcome.

        Args:
            operation: Stable lifecycle operation name.
            outcome: Bounded completion result.
            seconds: Elapsed lifecycle time.
        """

    def record_readiness(self, ready: bool) -> None:
        """Record the latest boolean readiness observation.

        Args:
            ready: Whether the runtime can currently serve requests.
        """


NoopMetricsRecorder = MetricsRecorder


class OpenTelemetryMetricsRecorder(MetricsRecorder):
    """Implement application telemetry with bounded OpenTelemetry instruments."""

    def __init__(
        self,
        *,
        meter_name: str,
        meter_version: str,
        meter: Meter | None = None,
        tracer: Tracer | None = None,
    ) -> None:
        """Create application instruments from owned or global providers.

        Args:
            meter_name: Stable instrumentation scope name.
            meter_version: Application version for the scope.
            meter: Optional meter from an application-owned provider.
            tracer: Optional tracer from an application-owned provider.
        """
        if meter is None:
            from opentelemetry import metrics

            meter = metrics.get_meter(meter_name, meter_version)
        self._tracer = tracer or trace.get_tracer(meter_name, meter_version)
        self._cache_lookups = meter.create_counter(
            name="canfar.metrics.cache.lookups",
            unit="1",
            description="Cache lookups by bounded result.",
        )
        self._cache_age = meter.create_histogram(
            name="canfar.metrics.cache.age",
            unit="s",
            description="Age of cache snapshots returned to callers.",
            explicit_bucket_boundaries_advisory=_SECONDS_BUCKETS,
        )
        self._leases = meter.create_counter(
            name="canfar.metrics.cache.leases",
            unit="1",
            description="Distributed cache lease outcomes.",
        )
        self._fill_duration = meter.create_histogram(
            name="canfar.metrics.cache.fill.duration",
            unit="s",
            description="Cache fill duration.",
            explicit_bucket_boundaries_advisory=_SECONDS_BUCKETS,
        )
        self._compute_duration = meter.create_histogram(
            name="canfar.metrics.compute.duration",
            unit="s",
            description="End-to-end compute duration.",
            explicit_bucket_boundaries_advisory=_SECONDS_BUCKETS,
        )
        self._provider_duration = meter.create_histogram(
            name="canfar.metrics.provider.duration",
            unit="s",
            description="Source provider duration.",
            explicit_bucket_boundaries_advisory=_SECONDS_BUCKETS,
        )
        self._provider_errors = meter.create_counter(
            name="canfar.metrics.provider.errors",
            unit="1",
            description="Source provider failures.",
        )
        self._redis_duration = meter.create_histogram(
            name="canfar.metrics.redis.duration",
            unit="s",
            description="Redis operation duration.",
            explicit_bucket_boundaries_advisory=_SECONDS_BUCKETS,
        )
        self._redis_health = meter.create_up_down_counter(
            name="canfar.metrics.redis.health",
            unit="1",
            description="Redis health checks: one for success, zero for failure.",
        )
        self._lifecycle_duration = meter.create_histogram(
            name="canfar.metrics.lifecycle.duration",
            unit="s",
            description="Application lifecycle operation duration.",
            explicit_bucket_boundaries_advisory=_SECONDS_BUCKETS,
        )
        self._readiness = meter.create_up_down_counter(
            name="canfar.metrics.readiness",
            unit="1",
            description="Readiness observations: one for ready, zero otherwise.",
        )
        self._last_redis_health = 0
        self._last_readiness = 0

    @contextmanager
    def span(self, name: str, attributes: Mapping[str, str] | None = None) -> Iterator[Any]:
        """Create one internal span with bounded caller-owned attributes.

        Args:
            name: Bounded operation name.
            attributes: Optional bounded application-owned attributes.

        Yields:
            The active internal span.
        """
        with self._tracer.start_as_current_span(name, attributes=dict(attributes or {})) as span:
            yield span

    def record_cache_lookup(
        self,
        *,
        backend: str,
        result: str = "hit",
        scope: str,
        age_seconds: float | None = None,
        hit: bool | None = None,
    ) -> None:
        """Record a cache result and optional snapshot age.

        Args:
            backend: Cache implementation name.
            result: Bounded hit, miss, or stale result.
            scope: Metrics subject kind.
            age_seconds: Optional snapshot age.
            hit: Legacy boolean override for the result.
        """
        if hit is not None:
            result = "hit" if hit else "miss"
        attributes = {
            "cache.backend": backend,
            "cache.result": result,
            "metrics.scope": scope,
        }
        self._cache_lookups.add(1, attributes=attributes)
        if age_seconds is not None:
            self._cache_age.record(max(age_seconds, 0.0), attributes=attributes)

    def record_lease(self, *, outcome: str, scope: str) -> None:
        """Count lease acquisition or contention for a subject scope."""
        self._leases.add(1, attributes={"lease.outcome": outcome, "metrics.scope": scope})

    def record_fill_duration(self, *, seconds: float, outcome: str, scope: str) -> None:
        """Observe non-negative cache fill duration by outcome and scope."""
        self._fill_duration.record(
            max(seconds, 0.0),
            attributes={"result.status": outcome, "metrics.scope": scope},
        )

    def record_compute_duration(self, *, seconds: float, status: str, scope: str) -> None:
        """Observe non-negative end-to-end compute duration."""
        self._compute_duration.record(
            max(seconds, 0.0),
            attributes={"result.status": status, "metrics.scope": scope},
        )

    def record_provider_duration(
        self,
        *,
        provider: str,
        scope: str,
        status: str,
        seconds: float,
    ) -> None:
        """Observe source duration and count non-successful operations."""
        attributes = {
            "provider.name": provider,
            "metrics.scope": scope,
            "result.status": status,
        }
        self._provider_duration.record(max(seconds, 0.0), attributes=attributes)
        if status != "ok":
            self._provider_errors.add(1, attributes=attributes)

    def record_redis(self, *, operation: str, outcome: str, seconds: float) -> None:
        """Observe Redis latency and update health after ping operations."""
        attributes = {"db.operation.name": operation, "result.status": outcome}
        self._redis_duration.record(max(seconds, 0.0), attributes=attributes)
        if operation == "ping":
            value = 1 if outcome == "ok" else 0
            self._redis_health.add(value - self._last_redis_health)
            self._last_redis_health = value

    def record_lifecycle(self, *, operation: str, outcome: str, seconds: float) -> None:
        """Observe non-negative startup or shutdown duration."""
        self._lifecycle_duration.record(
            max(seconds, 0.0),
            attributes={"lifecycle.operation": operation, "result.status": outcome},
        )

    def record_readiness(self, ready: bool) -> None:
        """Update the readiness gauge to the supplied boolean state."""
        value = int(ready)
        self._readiness.add(value - self._last_readiness)
        self._last_readiness = value
