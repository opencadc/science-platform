from __future__ import annotations

import asyncio
from concurrent.futures import ThreadPoolExecutor
from threading import Barrier, Event, Lock

import pytest

import metrics.telemetry.setup as telemetry_setup
from metrics.core.settings import (
    CacheConfig,
    KueueProviderConfig,
    OTelConfig,
    ProviderConfigs,
    Settings,
)
from metrics.telemetry import (
    MetricsRecorder,
    NoopMetricsRecorder,
    OpenTelemetryMetricsRecorder,
    Telemetry,
    setup_telemetry,
)


class _PassiveInstrument:
    def add(self, _value: int, **_kwargs: object) -> None:
        pass

    def record(self, _value: float, **_kwargs: object) -> None:
        pass


class _RecordingInstrument(_PassiveInstrument):
    def __init__(self) -> None:
        self.calls: list[tuple[int | float, dict[str, object]]] = []

    def add(self, value: int, *, attributes: dict[str, object] | None = None) -> None:
        self.calls.append((value, attributes or {}))

    def record(self, value: float, *, attributes: dict[str, object] | None = None) -> None:
        self.calls.append((value, attributes or {}))


class _FakeMeter:
    def __init__(self) -> None:
        self.instruments: dict[str, _RecordingInstrument] = {}

    def _create(self, name: str) -> _RecordingInstrument:
        instrument = _RecordingInstrument()
        self.instruments[name] = instrument
        return instrument

    def create_counter(self, *, name: str, **_kwargs: object) -> _RecordingInstrument:
        return self._create(name)

    def create_histogram(self, *, name: str, **_kwargs: object) -> _RecordingInstrument:
        return self._create(name)

    def create_up_down_counter(self, *, name: str, **_kwargs: object) -> _RecordingInstrument:
        return self._create(name)


class _BlockingInstrument(_PassiveInstrument):
    def __init__(self) -> None:
        self._active_lock = Lock()
        self._active = 0
        self.first_started = Event()
        self.overlap = Event()
        self.release = Event()
        self.values: list[int] = []

    def add(self, value: int, **_kwargs: object) -> None:
        with self._active_lock:
            self._active += 1
            if self._active == 1:
                self.first_started.set()
            else:
                self.overlap.set()
        if not self.release.wait(timeout=5):
            raise AssertionError("test did not release the transition")
        with self._active_lock:
            self.values.append(value)
            self._active -= 1


class _TransitionMeter:
    def __init__(self) -> None:
        self.up_down: dict[str, _BlockingInstrument] = {}

    def create_counter(self, **_kwargs: object) -> _PassiveInstrument:
        return _PassiveInstrument()

    def create_histogram(self, **_kwargs: object) -> _PassiveInstrument:
        return _PassiveInstrument()

    def create_up_down_counter(self, *, name: str, **_kwargs: object) -> _BlockingInstrument:
        instrument = _BlockingInstrument()
        self.up_down[name] = instrument
        return instrument


def _run_serialized(callback, instrument: _BlockingInstrument) -> None:
    start = Barrier(2)

    def invoke() -> None:
        start.wait()
        callback()

    with ThreadPoolExecutor(max_workers=2) as executor:
        futures = [executor.submit(invoke) for _ in range(2)]
        assert instrument.first_started.wait(timeout=1)
        assert not instrument.overlap.wait(timeout=0.1)
        instrument.release.set()
        for future in futures:
            future.result()


def _settings_with_otel(otel: OTelConfig) -> Settings:
    return Settings.model_construct(
        app_version="v1alpha1",
        cluster_name="cluster-a",
        redis_url="redis://localhost:6379/0",
        cache=CacheConfig(key_secret="x" * 32),
        providers=ProviderConfigs(
            kueue=KueueProviderConfig(cluster_queues=["cq-a"], namespaces=["work-a"])
        ),
        otel=otel,
    )


@pytest.mark.anyio
async def test_setup_telemetry_is_noop_when_disabled_or_endpoint_is_absent() -> None:
    disabled = setup_telemetry(_settings_with_otel(OTelConfig()))
    assert isinstance(disabled.recorder, NoopMetricsRecorder)
    assert disabled.meter_provider is None
    assert disabled.enabled is False

    absent_endpoint = _settings_with_otel(
        OTelConfig.model_construct(
            metrics_enabled=True,
            exporter_otlp_endpoint=None,
            service_name="canfar-metrics",
            export_interval_millis=60_000,
            deployment_environment="unknown",
            kubernetes_namespace="unknown",
            pod_uid="unknown",
        )
    )
    absent = setup_telemetry(absent_endpoint)
    assert isinstance(absent.recorder, NoopMetricsRecorder)
    assert absent.meter_provider is None
    assert absent.enabled is False

    await disabled.shutdown()
    await disabled.shutdown()
    await absent.shutdown()
    await absent.shutdown()


def test_noop_recorder_keeps_the_application_interface() -> None:
    recorder: MetricsRecorder = NoopMetricsRecorder()
    recorder.record_cache_lookup(backend="redis", result="hit", scope="platform")
    recorder.record_fill_duration(seconds=0.1, outcome="ok", scope="platform")
    recorder.record_compute_duration(seconds=1.0, status="ok", scope="platform")
    recorder.record_provider_duration(
        provider="kueue",
        scope="platform",
        status="ok",
        seconds=0.01,
    )
    recorder.record_redis(operation="ping", outcome="ok", seconds=0.01)
    recorder.record_lifecycle(operation="startup", outcome="ok", seconds=0.01)
    recorder.record_readiness(True)


def test_otel_instrument_names_and_attribute_keys_remain_stable() -> None:
    meter = _FakeMeter()
    recorder = OpenTelemetryMetricsRecorder(meter=meter)
    recorder.record_provider_duration(
        provider="kueue",
        scope="platform",
        status="ok",
        seconds=0.1,
    )
    recorder.record_cache_lookup(
        backend="redis",
        result="miss",
        scope="platform",
        age_seconds=-1,
    )
    recorder.record_compute_duration(seconds=0.2, status="ok", scope="platform")

    assert set(meter.instruments) == {
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
    assert set(meter.instruments["canfar.metrics.provider.duration"].calls[0][1]) == {
        "provider.name",
        "metrics.scope",
        "result.status",
    }
    assert set(meter.instruments["canfar.metrics.cache.lookups"].calls[0][1]) == {
        "cache.backend",
        "cache.result",
        "metrics.scope",
    }
    assert meter.instruments["canfar.metrics.cache.age"].calls[0][0] == 0
    assert not hasattr(recorder, "span")


def test_redis_operation_vocabulary_preserves_cache_coordination_names() -> None:
    """Cache Redis operations remain visible instead of being relabeled as other."""
    meter = _FakeMeter()
    recorder = OpenTelemetryMetricsRecorder(meter=meter)

    for operation in ("lease_acquire", "commit", "lease_release"):
        recorder.record_redis(operation=operation, outcome="ok", seconds=0)

    operations = [
        attributes["db.operation.name"]
        for _value, attributes in meter.instruments["canfar.metrics.redis.duration"].calls
    ]
    assert operations == ["lease_acquire", "commit", "lease_release"]


def test_lease_outcome_vocabulary_preserves_errors_and_bounds_unknown_values() -> None:
    """Redis lease errors remain distinguishable from unrecognized values."""
    meter = _FakeMeter()
    recorder = OpenTelemetryMetricsRecorder(meter=meter)

    recorder.record_lease(outcome="error", scope="platform")
    recorder.record_lease(outcome="unexpected", scope="platform")

    outcomes = [
        attributes["lease.outcome"]
        for _value, attributes in meter.instruments["canfar.metrics.cache.leases"].calls
    ]
    assert outcomes == ["error", "other"]


def test_state_transitions_are_serialized_and_repeated_calls_are_deltas() -> None:
    meter = _TransitionMeter()
    recorder = OpenTelemetryMetricsRecorder(meter=meter)
    readiness = meter.up_down["canfar.metrics.readiness"]
    redis_health = meter.up_down["canfar.metrics.redis.health"]

    _run_serialized(lambda: recorder.record_readiness(True), readiness)
    assert readiness.values == [1, 0]
    assert sum(readiness.values) == 1
    recorder.record_readiness(True)
    recorder.record_readiness(False)
    recorder.record_readiness(False)
    assert readiness.values == [1, 0, 0, -1, 0]

    _run_serialized(
        lambda: recorder.record_redis(operation="ping", outcome="ok", seconds=0),
        redis_health,
    )
    assert redis_health.values == [1, 0]
    recorder.record_redis(operation="ping", outcome="ok", seconds=0)
    recorder.record_redis(operation="ping", outcome="error", seconds=0)
    recorder.record_redis(operation="ping", outcome="error", seconds=0)
    assert redis_health.values == [1, 0, 0, -1, 0]


@pytest.mark.anyio
async def test_telemetry_shutdown_is_idempotent_after_provider_failure() -> None:
    class Provider:
        def __init__(self) -> None:
            self.calls = 0
            self.timeouts: list[int] = []

        def shutdown(self, *, timeout_millis: int) -> None:
            self.timeouts.append(timeout_millis)
            self.calls += 1
            raise RuntimeError("meter shutdown failed")

    provider = Provider()
    telemetry = Telemetry(NoopMetricsRecorder(), meter_provider=provider)  # type: ignore[arg-type]

    await telemetry.shutdown()
    assert provider.calls == 1
    assert provider.timeouts == [5000]
    assert telemetry.enabled is False
    await telemetry.shutdown()
    assert provider.calls == 1


@pytest.mark.anyio
async def test_telemetry_shutdown_runs_synchronous_provider_off_event_loop() -> None:
    class Provider:
        def shutdown(self, *, timeout_millis: int) -> None:
            assert timeout_millis == 5000
            Event().wait(timeout=0.1)

    telemetry = Telemetry(NoopMetricsRecorder(), meter_provider=Provider())  # type: ignore[arg-type]
    shutdown = asyncio.create_task(telemetry.shutdown())
    await asyncio.wait_for(asyncio.sleep(0.01), timeout=0.05)
    await shutdown


@pytest.mark.anyio
async def test_telemetry_shutdown_suppresses_outer_guard_timeout(
    monkeypatch,
    caplog: pytest.LogCaptureFixture,
) -> None:
    """An exporter that outlives the outer guard cannot block app shutdown."""

    async def blocked_to_thread(*_args: object, **_kwargs: object) -> None:
        await asyncio.sleep(1)

    monkeypatch.setattr(telemetry_setup, "_SHUTDOWN_GUARD_SECONDS", 0.01)
    monkeypatch.setattr(telemetry_setup.asyncio, "to_thread", blocked_to_thread)

    class Provider:
        """Provide the SDK shutdown attribute used by the async path."""

        def shutdown(self, *, timeout_millis: int) -> None:
            del timeout_millis

    telemetry = Telemetry(NoopMetricsRecorder(), meter_provider=Provider())  # type: ignore[arg-type]

    await telemetry.shutdown()

    assert any("Telemetry shutdown exceeded" in record.getMessage() for record in caplog.records)


def test_setup_cleans_up_a_partially_constructed_meter(monkeypatch) -> None:
    events: list[str] = []

    class Provider:
        def get_meter(self, *_args: object) -> object:
            return object()

        def shutdown(self, *, timeout_millis: int) -> None:
            events.append(str(timeout_millis))

    provider = Provider()
    monkeypatch.setattr(telemetry_setup, "MeterProvider", lambda **_kwargs: provider)
    monkeypatch.setattr(
        telemetry_setup,
        "PeriodicExportingMetricReader",
        lambda *_args, **_kwargs: object(),
    )
    monkeypatch.setattr(telemetry_setup, "OTLPMetricExporter", lambda **_kwargs: object())

    def fail_recorder(**_kwargs: object) -> None:
        raise RuntimeError("instrument construction failed")

    monkeypatch.setattr(telemetry_setup, "OpenTelemetryMetricsRecorder", fail_recorder)
    settings = _settings_with_otel(
        OTelConfig(
            metrics_enabled=True,
            exporter_otlp_endpoint="http://collector:4318",
        )
    )

    with pytest.raises(RuntimeError, match="instrument construction failed"):
        setup_telemetry(settings)
    assert events == ["5000"]


@pytest.mark.parametrize(
    ("base", "expected"),
    [
        ("http://collector:4318", "http://collector:4318/v1/metrics"),
        ("http://collector:4318/", "http://collector:4318/v1/metrics"),
        ("http://collector:4318/v1/metrics", "http://collector:4318/v1/metrics"),
    ],
)
def test_metrics_endpoint_preserves_supported_otlp_forms(base: str, expected: str) -> None:
    assert telemetry_setup._metrics_endpoint(base) == expected


def test_metrics_endpoint_rejects_unsafe_url() -> None:
    with pytest.raises(ValueError, match="credentials, query, or fragment|query or fragment"):
        telemetry_setup._metrics_endpoint("http://collector:4318?token=secret")
