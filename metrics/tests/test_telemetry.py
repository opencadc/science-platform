from __future__ import annotations

import json
import logging

from opentelemetry.sdk.trace import TracerProvider

from metrics.core.settings import CacheConfig, Settings
from metrics.telemetry import (
    JsonFormatter,
    NoopMetricsRecorder,
    OpenTelemetryMetricsRecorder,
    setup_telemetry,
)


def test_setup_telemetry_defaults_to_noop_when_disabled() -> None:
    telemetry = setup_telemetry(Settings(cache=CacheConfig(backend="memory")))
    assert isinstance(telemetry.recorder, NoopMetricsRecorder)
    assert telemetry.meter_provider is None
    assert telemetry.tracer_provider is None
    assert telemetry.logger_provider is None

    # The base recorder is the no-op: every call records nothing and returns.
    telemetry.recorder.record_cache_lookup(backend="redis", result="hit", scope="platform")
    telemetry.recorder.record_compute_duration(seconds=1.0, status="ok", scope="platform")
    telemetry.recorder.record_provider_duration(
        provider="kueue", scope="platform", status="ok", seconds=0.01
    )


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

        def create_up_down_counter(self, name: str, **_kwargs) -> FakeInstrument:
            instruments[name] = FakeInstrument(name)
            return instruments[name]

    recorder = OpenTelemetryMetricsRecorder(
        meter_name="metrics.service",
        meter_version="v1",
        meter=FakeMeter(),
    )
    recorder.record_provider_duration(
        provider="kueue",
        scope="platform",
        status="ok",
        seconds=0.1,
    )
    recorder.record_cache_lookup(backend="redis", result="miss", scope="platform")
    recorder.record_compute_duration(seconds=0.2, status="ok", scope="platform")

    # ADR-0002 meter names; HTTP request metrics come from FastAPI
    # auto-instrumentation (http.server.*), not a custom meter.
    assert set(instruments) == {
        "canfar.metrics.provider.duration",
        "canfar.metrics.cache.lookups",
        "canfar.metrics.compute.duration",
        "canfar.metrics.cache.age",
        "canfar.metrics.cache.leases",
        "canfar.metrics.cache.fill.duration",
        "canfar.metrics.provider.errors",
        "canfar.metrics.redis.duration",
        "canfar.metrics.redis.health",
        "canfar.metrics.lifecycle.duration",
        "canfar.metrics.readiness",
    }
    assert set(instruments["canfar.metrics.provider.duration"].calls[0][1]) == {
        "provider.name",
        "metrics.scope",
        "result.status",
    }
    assert set(instruments["canfar.metrics.cache.lookups"].calls[0][1]) == {
        "cache.backend",
        "cache.result",
        "metrics.scope",
    }
    assert set(instruments["canfar.metrics.compute.duration"].calls[0][1]) == {
        "result.status",
        "metrics.scope",
    }


def test_json_logs_carry_trace_correlation_without_exception_payloads() -> None:
    provider = TracerProvider()
    tracer = provider.get_tracer("test")
    record = logging.LogRecord(
        "metrics.test",
        logging.ERROR,
        __file__,
        1,
        "bounded failure",
        (),
        RuntimeError("fixture-secret"),
    )
    with tracer.start_as_current_span("test"):
        payload = json.loads(JsonFormatter().format(record))

    assert payload["message"] == "bounded failure"
    assert len(payload["trace_id"]) == 32
    assert len(payload["span_id"]) == 16
    assert "fixture-secret" not in json.dumps(payload)
