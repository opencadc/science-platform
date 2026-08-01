"""OpenTelemetry metrics integration for service-level observations."""

from __future__ import annotations

from opentelemetry import metrics
from opentelemetry.exporter.otlp.proto.http.metric_exporter import OTLPMetricExporter
from opentelemetry.sdk.metrics import MeterProvider
from opentelemetry.sdk.metrics.export import PeriodicExportingMetricReader
from opentelemetry.sdk.resources import Resource

from metrics.core.settings import Settings

# These histograms record seconds. The SDK's default boundaries run 0, 5, 10 … 10000,
# which are sized for milliseconds, so every observation we make lands in the first
# real bucket and any quantile over them is bucket interpolation rather than a
# measurement: an observed mean of 44 ms reported a p95 of 4.75 s. These bounds match
# what the OpenTelemetry HTTP semantic conventions use for second-valued durations.
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
    """Service-level metrics recorder; the base implementation records nothing."""

    def record_cache_lookup(self, *, backend: str, hit: bool, scope: str) -> None:
        """Record one cache lookup for the given backend and scope."""
        return

    def record_compute_duration(self, *, seconds: float, status: str, scope: str) -> None:
        """Record end-to-end compute time for a metrics read."""
        return

    def record_provider_duration(
        self,
        *,
        provider: str,
        scope: str,
        status: str,
        seconds: float,
    ) -> None:
        """Record time spent inside a named provider for a scope."""
        return


# The ADR-0002 name for the disabled-mode recorder.
NoopMetricsRecorder = MetricsRecorder


class OpenTelemetryMetricsRecorder(MetricsRecorder):
    """OTel-backed service recorder for cache, compute, and provider metrics.

    HTTP request metrics come from FastAPI auto-instrumentation
    (``http.server.*``), not a custom meter.
    """

    def __init__(self, *, meter_name: str, meter_version: str) -> None:
        """Create counters and histograms on a named OpenTelemetry :class:`Meter`."""
        meter = metrics.get_meter(meter_name, meter_version)
        self._cache_lookups = meter.create_counter(
            name="canfar.metrics.cache.lookups",
            unit="1",
            description="Total cache lookups by backend and hit status.",
        )
        self._compute_duration = meter.create_histogram(
            name="canfar.metrics.compute.duration",
            unit="s",
            description="End-to-end compute duration for platform metrics reads.",
            explicit_bucket_boundaries_advisory=_SECONDS_BUCKETS,
        )
        self._provider_duration = meter.create_histogram(
            name="canfar.metrics.provider.duration",
            unit="s",
            description="Provider call duration by scope and status.",
            explicit_bucket_boundaries_advisory=_SECONDS_BUCKETS,
        )

    def record_cache_lookup(self, *, backend: str, hit: bool, scope: str) -> None:
        """See :meth:`MetricsRecorder.record_cache_lookup`."""
        self._cache_lookups.add(
            1,
            attributes={
                "cache.backend": backend,
                "cache.hit": hit,
                "metrics.scope": scope,
            },
        )

    def record_compute_duration(self, *, seconds: float, status: str, scope: str) -> None:
        """See :meth:`MetricsRecorder.record_compute_duration`."""
        self._compute_duration.record(
            max(seconds, 0.0),
            attributes={
                "result.status": status,
                "metrics.scope": scope,
            },
        )

    def record_provider_duration(
        self,
        *,
        provider: str,
        scope: str,
        status: str,
        seconds: float,
    ) -> None:
        """See :meth:`MetricsRecorder.record_provider_duration`."""
        self._provider_duration.record(
            max(seconds, 0.0),
            attributes={
                "provider.name": provider,
                "metrics.scope": scope,
                "result.status": status,
            },
        )


def setup_telemetry(settings: Settings) -> tuple[MetricsRecorder, MeterProvider | None]:
    """Build the telemetry recorder and the optional meter provider."""
    if not settings.otel_metrics_enabled:
        return NoopMetricsRecorder(), None

    exporter = (
        OTLPMetricExporter(endpoint=settings.otel_exporter_otlp_endpoint)
        if settings.otel_exporter_otlp_endpoint
        else OTLPMetricExporter()
    )
    reader = PeriodicExportingMetricReader(
        exporter=exporter,
        export_interval_millis=settings.otel_export_interval_millis,
    )
    meter_provider = MeterProvider(
        resource=Resource.create({"service.name": settings.otel_service_name}),
        metric_readers=[reader],
    )
    metrics.set_meter_provider(meter_provider)
    recorder = OpenTelemetryMetricsRecorder(
        meter_name="metrics.service",
        meter_version=settings.app_version,
    )
    return recorder, meter_provider
