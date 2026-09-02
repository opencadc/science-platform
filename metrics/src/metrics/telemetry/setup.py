"""Configure the optional application-level OpenTelemetry metrics exporter."""

from __future__ import annotations

import asyncio
import logging
from dataclasses import dataclass
from urllib.parse import urlsplit, urlunsplit

from opentelemetry.exporter.otlp.proto.http.metric_exporter import OTLPMetricExporter
from opentelemetry.sdk.metrics import MeterProvider
from opentelemetry.sdk.metrics.export import PeriodicExportingMetricReader
from opentelemetry.sdk.resources import Resource

from metrics.core.settings import Settings, _validate_otel_endpoint
from metrics.telemetry.instruments import (
    MetricsRecorder,
    NoopMetricsRecorder,
    OpenTelemetryMetricsRecorder,
)

_METER_NAME = "metrics.service"
_SHUTDOWN_TIMEOUT_MILLIS = 5000
_SHUTDOWN_GUARD_SECONDS = 6.0
_logger = logging.getLogger(__name__)


def _metrics_endpoint(base: str) -> str:
    """Build the OTLP/HTTP metrics URL from a configured endpoint."""
    parsed = urlsplit(_validate_otel_endpoint(base).rstrip("/"))
    suffix = "/v1/metrics"
    path = parsed.path
    if path.endswith(suffix):
        path = path[: -len(suffix)]
    return urlunsplit((parsed.scheme, parsed.netloc, f"{path}{suffix}", "", ""))


@dataclass(slots=True)
class Telemetry:
    """Own the optional meter provider and application metrics recorder."""

    recorder: MetricsRecorder
    meter_provider: MeterProvider | None = None

    @property
    def enabled(self) -> bool:
        """Return whether application metrics export is configured."""
        return self.meter_provider is not None

    async def shutdown(self) -> None:
        """Flush and close the meter provider off-thread within a hard bound."""
        provider, self.meter_provider = self.meter_provider, None
        if provider is not None:
            try:
                await asyncio.wait_for(
                    asyncio.to_thread(
                        provider.shutdown,
                        timeout_millis=_SHUTDOWN_TIMEOUT_MILLIS,
                    ),
                    timeout=_SHUTDOWN_GUARD_SECONDS,
                )
            except TimeoutError:
                _logger.warning(
                    "Telemetry shutdown exceeded %.1f seconds",
                    _SHUTDOWN_GUARD_SECONDS,
                )
            except Exception as exc:
                _logger.warning("Telemetry shutdown failed: %s", exc)


def setup_telemetry(settings: Settings) -> Telemetry:
    """Configure optional OTLP metrics without making it a readiness dependency."""
    config = settings.otel
    endpoint = config.exporter_otlp_endpoint
    if not config.metrics_enabled or not endpoint:
        return Telemetry(NoopMetricsRecorder())

    resource = Resource.create(
        {
            "service.name": config.service_name,
            "service.version": settings.app_version,
            "deployment.environment.name": config.deployment_environment,
            "canfar.cluster.name": settings.cluster_name,
            "k8s.namespace.name": config.kubernetes_namespace,
            "service.instance.id": config.pod_uid,
        }
    )
    meter_provider: MeterProvider | None = None
    try:
        meter_provider = MeterProvider(
            resource=resource,
            metric_readers=[
                PeriodicExportingMetricReader(
                    OTLPMetricExporter(endpoint=_metrics_endpoint(endpoint)),
                    export_interval_millis=config.export_interval_millis,
                )
            ],
        )
        recorder = OpenTelemetryMetricsRecorder(
            meter=meter_provider.get_meter(_METER_NAME, settings.app_version),
        )
        return Telemetry(recorder, meter_provider=meter_provider)
    except Exception:
        if meter_provider is not None:
            try:
                meter_provider.shutdown(timeout_millis=_SHUTDOWN_TIMEOUT_MILLIS)
            except Exception as cleanup_error:
                _logger.warning("Telemetry cleanup failed: %s", cleanup_error)
        raise
