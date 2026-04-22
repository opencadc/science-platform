"""OpenTelemetry metrics integration for service-level observations."""

from __future__ import annotations

from dataclasses import dataclass

from opentelemetry import metrics
from opentelemetry.exporter.otlp.proto.http.metric_exporter import OTLPMetricExporter
from opentelemetry.sdk.metrics import MeterProvider
from opentelemetry.sdk.metrics.export import PeriodicExportingMetricReader
from opentelemetry.sdk.resources import Resource

from metrics.core.settings import Settings


class MetricsRecorder:
    """Service-level metrics recorder contract."""

    def record_cache_lookup(self, *, backend: str, hit: bool, scope: str) -> None:
        raise NotImplementedError

    def record_compute_duration(
        self, *, seconds: float, status: str, scope: str
    ) -> None:
        raise NotImplementedError

    def record_provider_duration(
        self,
        *,
        provider: str,
        scope: str,
        status: str,
        seconds: float,
    ) -> None:
        raise NotImplementedError

    def record_http_request(
        self, *, scope: str, status_code: int, cached: bool
    ) -> None:
        raise NotImplementedError


class NoopMetricsRecorder(MetricsRecorder):
    """No-op metrics recorder used when OTel metrics are disabled."""

    def record_cache_lookup(self, *, backend: str, hit: bool, scope: str) -> None:
        return

    def record_compute_duration(
        self, *, seconds: float, status: str, scope: str
    ) -> None:
        return

    def record_provider_duration(
        self,
        *,
        provider: str,
        scope: str,
        status: str,
        seconds: float,
    ) -> None:
        return

    def record_http_request(
        self, *, scope: str, status_code: int, cached: bool
    ) -> None:
        return


class OpenTelemetryMetricsRecorder(MetricsRecorder):
    """OTel-backed service recorder for cache and compute metrics."""

    def __init__(self, *, meter_name: str, meter_version: str) -> None:
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
        )
        self._provider_duration = meter.create_histogram(
            name="canfar.metrics.provider.duration",
            unit="s",
            description="Provider call duration by scope and status.",
        )
        self._http_requests = meter.create_counter(
            name="canfar.metrics.http.requests",
            unit="1",
            description="HTTP request count by scope, status, and cache state.",
        )

    def record_cache_lookup(self, *, backend: str, hit: bool, scope: str) -> None:
        self._cache_lookups.add(
            1,
            attributes={
                "cache.backend": backend,
                "cache.hit": hit,
                "metrics.scope": scope,
            },
        )

    def record_compute_duration(
        self, *, seconds: float, status: str, scope: str
    ) -> None:
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
        self._provider_duration.record(
            max(seconds, 0.0),
            attributes={
                "provider.name": provider,
                "metrics.scope": scope,
                "result.status": status,
            },
        )

    def record_http_request(
        self, *, scope: str, status_code: int, cached: bool
    ) -> None:
        self._http_requests.add(
            1,
            attributes={
                "metrics.scope": scope,
                "http.status_code": status_code,
                "cache.hit": cached,
            },
        )


@dataclass(slots=True)
class TelemetrySetup:
    """Telemetry bootstrap outputs used by app startup/shutdown logic."""

    recorder: MetricsRecorder
    meter_provider: MeterProvider | None


def setup_telemetry(settings: Settings) -> TelemetrySetup:
    """Build telemetry recorder and optional meter provider."""
    if not settings.otel_metrics_enabled:
        return TelemetrySetup(recorder=NoopMetricsRecorder(), meter_provider=None)

    exporter = _build_otlp_exporter(settings)
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
    return TelemetrySetup(recorder=recorder, meter_provider=meter_provider)


def _build_otlp_exporter(settings: Settings) -> OTLPMetricExporter:
    if settings.otel_exporter_otlp_endpoint:
        return OTLPMetricExporter(endpoint=settings.otel_exporter_otlp_endpoint)
    return OTLPMetricExporter()
