"""Create application-owned OpenTelemetry providers and OTLP exporters.

Providers remain local to the application instead of replacing global SDK
providers, which keeps lifecycle, tests, and shutdown behavior explicit.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from urllib.parse import urlsplit, urlunsplit

from opentelemetry.exporter.otlp.proto.http._log_exporter import OTLPLogExporter
from opentelemetry.exporter.otlp.proto.http.metric_exporter import OTLPMetricExporter
from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
from opentelemetry.sdk._logs import LoggerProvider, LoggingHandler
from opentelemetry.sdk._logs.export import BatchLogRecordProcessor
from opentelemetry.sdk.metrics import MeterProvider
from opentelemetry.sdk.metrics.export import PeriodicExportingMetricReader
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor

from metrics.core.settings import Settings
from metrics.telemetry.instruments import (
    MetricsRecorder,
    NoopMetricsRecorder,
    OpenTelemetryMetricsRecorder,
)
from metrics.telemetry.logging import configure_logging

_METER_NAME = "metrics.service"


def _signal_endpoint(base: str, signal: str) -> str:
    """Build an OTLP/HTTP signal URL from one configured base endpoint.

    Existing standard signal suffixes are replaced so operators may configure
    either the common OTLP base or one signal-specific URL.

    Args:
        base: Configured OTLP HTTP endpoint.
        signal: Signal path component such as ``metrics`` or ``traces``.

    Returns:
        Endpoint ending in ``/v1/{signal}`` with query and fragment removed.
    """
    parsed = urlsplit(base.rstrip("/"))
    path = parsed.path
    for suffix in ("/v1/metrics", "/v1/traces", "/v1/logs"):
        if path.endswith(suffix):
            path = path[: -len(suffix)]
            break
    return urlunsplit((parsed.scheme, parsed.netloc, f"{path}/v1/{signal}", "", ""))


@dataclass(slots=True)
class Telemetry:
    """Own optional signal providers, handlers, and the application recorder."""

    recorder: MetricsRecorder
    meter_provider: MeterProvider | None = None
    tracer_provider: TracerProvider | None = None
    logger_provider: LoggerProvider | None = None
    log_handler: LoggingHandler | None = None

    @property
    def enabled(self) -> bool:
        """Whether at least one OTel signal is active."""
        return any((self.meter_provider, self.tracer_provider, self.logger_provider))

    def shutdown(self) -> None:
        """Detach log export and flush each configured signal provider."""
        logger = logging.getLogger("metrics")
        if self.log_handler is not None:
            logger.removeHandler(self.log_handler)
        if self.logger_provider is not None:
            self.logger_provider.shutdown()
        if self.tracer_provider is not None:
            self.tracer_provider.shutdown()
        if self.meter_provider is not None:
            self.meter_provider.shutdown()


def setup_telemetry(settings: Settings) -> Telemetry:
    """Configure JSON logging and optional OTLP signals.

    Args:
        settings: Validated process and OpenTelemetry settings.

    Returns:
        Owned telemetry resources; when all signals are disabled, only a no-op
        recorder is returned.
    """
    configure_logging(settings.log_level)
    config = settings.otel
    if not (config.metrics_enabled or config.traces_enabled or config.logs_enabled):
        return Telemetry(NoopMetricsRecorder())

    endpoint = config.exporter_otlp_endpoint
    assert endpoint is not None  # validated by OTelConfig
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

    tracer_provider = None
    if config.traces_enabled:
        tracer_provider = TracerProvider(resource=resource)
        tracer_provider.add_span_processor(
            BatchSpanProcessor(OTLPSpanExporter(endpoint=_signal_endpoint(endpoint, "traces")))
        )

    meter_provider = None
    if config.metrics_enabled:
        meter_provider = MeterProvider(
            resource=resource,
            metric_readers=[
                PeriodicExportingMetricReader(
                    OTLPMetricExporter(endpoint=_signal_endpoint(endpoint, "metrics")),
                    export_interval_millis=config.export_interval_millis,
                )
            ],
        )

    logger_provider = None
    log_handler = None
    if config.logs_enabled:
        logger_provider = LoggerProvider(resource=resource)
        logger_provider.add_log_record_processor(
            BatchLogRecordProcessor(OTLPLogExporter(endpoint=_signal_endpoint(endpoint, "logs")))
        )
        log_handler = LoggingHandler(logger_provider=logger_provider)
        logging.getLogger("metrics").addHandler(log_handler)

    meter = meter_provider.get_meter(_METER_NAME, settings.app_version) if meter_provider else None
    tracer = (
        tracer_provider.get_tracer(_METER_NAME, settings.app_version) if tracer_provider else None
    )
    recorder: MetricsRecorder
    if meter is not None or tracer is not None:
        recorder = OpenTelemetryMetricsRecorder(
            meter_name=_METER_NAME,
            meter_version=settings.app_version,
            meter=meter,
            tracer=tracer,
        )
    else:
        recorder = NoopMetricsRecorder()
    return Telemetry(
        recorder,
        meter_provider=meter_provider,
        tracer_provider=tracer_provider,
        logger_provider=logger_provider,
        log_handler=log_handler,
    )
