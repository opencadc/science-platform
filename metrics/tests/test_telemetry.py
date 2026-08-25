from __future__ import annotations

from metrics.core.settings import CacheConfig, Settings
from metrics.telemetry import (
    NoopMetricsRecorder,
    OpenTelemetryMetricsRecorder,
    setup_telemetry,
)


def test_setup_telemetry_defaults_to_noop_when_disabled() -> None:
    recorder, meter_provider = setup_telemetry(
        Settings(otel_metrics_enabled=False, cache=CacheConfig(backend="memory"))
    )
    assert isinstance(recorder, NoopMetricsRecorder)
    assert meter_provider is None

    # The base recorder is the no-op: every call records nothing and returns.
    recorder.record_cache_lookup(backend="redis", hit=True, scope="platform")
    recorder.record_compute_duration(seconds=1.0, status="ok", scope="platform")
    recorder.record_provider_duration(provider="kueue", scope="platform", status="ok", seconds=0.01)


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

    monkeypatch.setattr("metrics.telemetry.metrics.get_meter", lambda *_args: FakeMeter())

    recorder = OpenTelemetryMetricsRecorder(meter_name="metrics.service", meter_version="v1")
    recorder.record_provider_duration(
        provider="kueue",
        scope="platform",
        status="ok",
        seconds=0.1,
    )
    recorder.record_cache_lookup(backend="redis", hit=False, scope="platform")
    recorder.record_compute_duration(seconds=0.2, status="ok", scope="platform")

    # ADR-0002 meter names; HTTP request metrics come from FastAPI
    # auto-instrumentation (http.server.*), not a custom meter.
    assert set(instruments) == {
        "canfar.metrics.provider.duration",
        "canfar.metrics.cache.lookups",
        "canfar.metrics.compute.duration",
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
