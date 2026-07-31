from __future__ import annotations

import metrics.telemetry as telemetry_module
from metrics.core.settings import Settings
from metrics.telemetry import (
    NoopMetricsRecorder,
    OpenTelemetryMetricsRecorder,
    setup_telemetry,
)


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


def test_otel_instrument_names_and_attribute_keys_remain_stable(monkeypatch) -> None:
    class FakeInstrument:
        def __init__(self, name: str) -> None:
            self.name = name
            self.calls: list[tuple[int | float, dict[str, object]]] = []

        def add(self, value: int, *, attributes: dict[str, object]) -> None:
            self.calls.append((value, attributes))

        def record(self, value: float, *, attributes: dict[str, object]) -> None:
            self.calls.append((value, attributes))

    instruments: dict[str, FakeInstrument] = {}

    class FakeMeter:
        def create_counter(self, *, name: str, **_kwargs) -> FakeInstrument:
            instruments[name] = FakeInstrument(name)
            return instruments[name]

        def create_histogram(self, *, name: str, **_kwargs) -> FakeInstrument:
            instruments[name] = FakeInstrument(name)
            return instruments[name]

    monkeypatch.setattr(
        telemetry_module.metrics,
        "get_meter",
        lambda *_args: FakeMeter(),
    )

    recorder = OpenTelemetryMetricsRecorder(meter_name="metrics.service", meter_version="v1")
    recorder.record_http_request(scope="platform", status_code=200, cached=True)
    recorder.record_provider_duration(
        provider="kueue",
        scope="platform",
        status="ok",
        seconds=0.1,
    )
    recorder.record_cache_lookup(backend="redis", hit=False, scope="platform")
    recorder.record_compute_duration(seconds=0.2, status="ok", scope="platform")

    assert set(instruments) == {
        "canfar.metrics.http.requests",
        "canfar.metrics.provider.duration",
        "canfar.metrics.cache.lookups",
        "canfar.metrics.compute.duration",
    }
    assert set(instruments["canfar.metrics.http.requests"].calls[0][1]) == {
        "metrics.scope",
        "http.status_code",
        "cache.hit",
    }
    assert set(instruments["canfar.metrics.provider.duration"].calls[0][1]) == {
        "provider.name",
        "metrics.scope",
        "result.status",
    }
    assert set(instruments["canfar.metrics.cache.lookups"].calls[0][1]) == {
        "cache.backend",
        "cache.hit",
        "metrics.scope",
    }
    assert set(instruments["canfar.metrics.compute.duration"].calls[0][1]) == {
        "result.status",
        "metrics.scope",
    }
