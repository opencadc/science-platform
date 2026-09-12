"""Optional OpenTelemetry metrics setup and bounded application instruments."""

from metrics.telemetry.instruments import (
    MetricsRecorder,
    NoopMetricsRecorder,
    OpenTelemetryMetricsRecorder,
)
from metrics.telemetry.setup import Telemetry, setup_telemetry

__all__ = [
    "MetricsRecorder",
    "NoopMetricsRecorder",
    "OpenTelemetryMetricsRecorder",
    "Telemetry",
    "setup_telemetry",
]
