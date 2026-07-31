"""Provider test doubles and small Kubernetes HTTP helper paths."""

from __future__ import annotations

import asyncio
from pathlib import Path

import pytest

from metrics.cache import InMemoryTTLCache
from metrics.core.runtime import MetricsRuntime
from metrics.core.settings import CacheConfig, Settings
from metrics.errors import RuntimeStartupError
from metrics.services.platform import CachedMetrics, PlatformMetricsService
from metrics.telemetry import NoopMetricsRecorder
from tests.fakes import LifecycleProvider

from metrics.providers.kueue import (
    resolve_kube_token,
    resolve_kube_verify,
)

_unpatched_runtime_start = MetricsRuntime.start


class _RecordingRedis:
    def __init__(self, events: list[str]) -> None:
        self._events = events

    async def aclose(self) -> None:
        self._events.append("redis shutdown")


def _runtime_with(
    provider: LifecycleProvider,
    *,
    redis: _RecordingRedis | None = None,
) -> MetricsRuntime:
    service = PlatformMetricsService(
        platform=provider.platform,
        cache=InMemoryTTLCache[CachedMetrics](ttl_seconds=60),
        key=lambda: "platform:4:c:stub",
        telemetry=NoopMetricsRecorder(),
        provider=provider.name,
    )
    runtime = MetricsRuntime(Settings(cache=CacheConfig(backend="memory")))
    runtime.wire(
        provider=provider,
        platform_service=service,
        redis=redis,
    )
    return runtime


@pytest.mark.anyio
async def test_metrics_runtime_starts_and_stops_owned_provider_once() -> None:
    """Repeated lifecycle calls still operate on the owned provider exactly once."""

    events: list[str] = []
    runtime = _runtime_with(LifecycleProvider(events))
    await _unpatched_runtime_start(runtime)
    await _unpatched_runtime_start(runtime)
    await runtime.shutdown()
    await runtime.shutdown()

    assert events == ["startup", "provider shutdown"]
    with pytest.raises(RuntimeError, match="not initialised"):
        _ = runtime.platform_service
    with pytest.raises(RuntimeError, match="not initialised"):
        await runtime.get_platform_metrics()


@pytest.mark.anyio
@pytest.mark.parametrize(
    ("startup_error", "expected_message"),
    [
        (RuntimeStartupError("invalid provider configuration"), "invalid provider configuration"),
        (ValueError("sensitive implementation detail"), "Unexpected error"),
    ],
)
async def test_metrics_runtime_startup_failure_cleans_up_and_sanitizes(
    startup_error: Exception,
    expected_message: str,
) -> None:
    """Provider startup failures close all owned resources and hide unexpected details."""

    events: list[str] = []
    runtime = _runtime_with(
        LifecycleProvider(events, startup_error=startup_error),
        redis=_RecordingRedis(events),
    )

    with pytest.raises(RuntimeStartupError) as error:
        await _unpatched_runtime_start(runtime)

    assert expected_message in str(error.value)
    assert "sensitive implementation detail" not in str(error.value)
    assert events == ["startup", "provider shutdown", "redis shutdown"]
    await runtime.shutdown()
    assert events == ["startup", "provider shutdown", "redis shutdown"]


@pytest.mark.anyio
async def test_metrics_runtime_shutdown_failure_does_not_skip_remaining_cleanup() -> None:
    """One resource failure cannot prevent cleanup of later resources."""

    events: list[str] = []
    runtime = _runtime_with(
        LifecycleProvider(events, shutdown_error=RuntimeError("boom")),
        redis=_RecordingRedis(events),
    )

    await runtime.shutdown()

    assert events == ["provider shutdown", "redis shutdown"]


@pytest.mark.anyio
async def test_metrics_runtime_cancellation_cleans_up_remaining_resources() -> None:
    """Cancellation is re-raised only after all owned resources are cleaned up."""

    events: list[str] = []
    runtime = _runtime_with(
        LifecycleProvider(events, shutdown_error=asyncio.CancelledError()),
        redis=_RecordingRedis(events),
    )

    with pytest.raises(asyncio.CancelledError):
        await runtime.shutdown()

    assert events == ["provider shutdown", "redis shutdown"]


@pytest.mark.anyio
async def test_metrics_runtime_startup_cancellation_cleans_up_resources() -> None:
    """Cancellation during startup still closes the provider and cache client."""

    events: list[str] = []
    runtime = _runtime_with(
        LifecycleProvider(events, startup_error=asyncio.CancelledError()),
        redis=_RecordingRedis(events),
    )

    with pytest.raises(asyncio.CancelledError):
        await _unpatched_runtime_start(runtime)

    assert events == ["startup", "provider shutdown", "redis shutdown"]


def test_token_file_reads(tmp_path: Path) -> None:
    t = tmp_path / "t.tok"
    t.write_text("  secret  ", encoding="utf-8")
    assert resolve_kube_token(None, str(t)) == "secret"


def test_ca_file_in_verify_uses_in_cluster_or_system() -> None:
    p = Path("/nope/no-ca-here-123")
    v = resolve_kube_verify(True, ca_file=str(p))
    assert v is True or isinstance(v, str)


@pytest.mark.anyio
async def test_kube_parallel_empty() -> None:
    import httpx

    from metrics.providers.kueue import kube_parallel_get_json

    c = httpx.AsyncClient()
    try:
        assert await kube_parallel_get_json(c, [], headers={}) == []
    finally:
        await c.aclose()
