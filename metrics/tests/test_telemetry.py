from __future__ import annotations

from metrics.core.settings import Settings
from metrics.telemetry import NoopMetricsRecorder, setup_telemetry


def test_setup_telemetry_defaults_to_noop_when_disabled() -> None:
    setup = setup_telemetry(Settings(otel_metrics_enabled=False))
    assert isinstance(setup.recorder, NoopMetricsRecorder)
    assert setup.meter_provider is None


def test_noop_recorder_accepts_all_metric_calls() -> None:
    recorder = NoopMetricsRecorder()
    recorder.record_cache_lookup(backend="redis", hit=True, scope="platform")
    recorder.record_compute_duration(seconds=1.0, status="ok", scope="platform")
    recorder.record_provider_duration(
        provider="kueue",
        scope="platform",
        status="ok",
        seconds=0.01,
    )
    recorder.record_http_request(scope="platform", status_code=200, cached=True)
