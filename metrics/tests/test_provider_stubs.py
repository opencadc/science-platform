"""Provider test doubles and small Kubernetes HTTP helper paths."""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

import pytest

from metrics.cache import InMemoryTTLCache
from metrics.core.runtime import MetricsRuntime
from metrics.core.settings import (
    CacheConfig,
    ProviderConfigs,
    Settings,
    SourceConfig,
)
from metrics.errors import RuntimeStartupError
from metrics.schemas.metrics import PlatformMetricsData
from metrics.services.platform import CachedMetrics, PlatformMetricsService
from metrics.telemetry import NoopMetricsRecorder

from metrics.providers.kueue import (
    resolve_kube_token,
    resolve_kube_verify,
)

_runtime_start = MetricsRuntime.start


def test_import_metrics_providers_base_avoids_circular_import() -> None:
    """Package ``__init__`` must not eagerly load factory/runtime while importing base."""
    result = subprocess.run(
        [sys.executable, "-c", "import metrics.providers.base"],
        check=False,
        capture_output=True,
        text=True,
    )
    assert result.returncode == 0, result.stdout + result.stderr


@pytest.mark.anyio
async def test_metrics_runtime_starts_and_stops_owned_provider_once() -> None:
    """Repeated lifecycle calls still operate on the owned provider exactly once."""

    startup_calls: list[str] = []
    shutdown_calls: list[str] = []

    class StubProvider:
        @property
        def name(self) -> str:
            return "stub-adapter"

        async def startup(self) -> None:
            startup_calls.append("startup")

        async def shutdown(self) -> None:
            shutdown_calls.append("shutdown")

        def cache_fingerprint(self) -> str:
            return "f"

        async def platform(self) -> PlatformMetricsData:
            return PlatformMetricsData(
                cluster="c",
                capacity={},
                allocated={},
            )

    settings = Settings(
        cache=CacheConfig(backend="memory"),
        sources=SourceConfig(platform="kueue"),
        providers=ProviderConfigs(),
    )
    stub = StubProvider()

    async def load() -> PlatformMetricsData:
        return await stub.platform()

    svc = PlatformMetricsService(
        platform=load,
        cache=InMemoryTTLCache[CachedMetrics](ttl_seconds=60),
        key=lambda: "platform:4:stub:fp",
        telemetry=NoopMetricsRecorder(),
        provider=stub.name,
    )
    runtime = MetricsRuntime(settings)
    runtime.set_recorder(NoopMetricsRecorder())
    runtime.wire(
        provider=stub,  # type: ignore[arg-type]
        platform_service=svc,
        redis=None,
    )
    await _runtime_start(runtime)
    await _runtime_start(runtime)
    await runtime.shutdown()
    await runtime.shutdown()

    assert startup_calls == ["startup"]
    assert shutdown_calls == ["shutdown"]
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

    class FailingProvider:
        @property
        def name(self) -> str:
            return "stub"

        async def startup(self) -> None:
            events.append("startup")
            raise startup_error

        async def shutdown(self) -> None:
            events.append("provider shutdown")

        def cache_fingerprint(self) -> str:
            return "stub"

        async def platform(self) -> PlatformMetricsData:
            return PlatformMetricsData(cluster="c", capacity={}, allocated={})

    class StubRedis:
        async def aclose(self) -> None:
            events.append("redis shutdown")

    settings = Settings(cache=CacheConfig(backend="memory"))
    service = PlatformMetricsService(
        platform=FailingProvider().platform,
        cache=InMemoryTTLCache[CachedMetrics](ttl_seconds=60),
        key=lambda: "platform:4:c:stub",
    )
    runtime = MetricsRuntime(settings)
    provider = FailingProvider()
    runtime.wire(
        provider=provider,  # type: ignore[arg-type]
        platform_service=service,
        redis=StubRedis(),  # type: ignore[arg-type]
    )

    with pytest.raises(RuntimeStartupError) as error:
        await _runtime_start(runtime)

    assert expected_message in str(error.value)
    assert "sensitive implementation detail" not in str(error.value)
    assert events == ["startup", "provider shutdown", "redis shutdown"]
    await runtime.shutdown()
    assert events == ["startup", "provider shutdown", "redis shutdown"]


@pytest.mark.anyio
async def test_metrics_runtime_shutdown_failure_does_not_skip_remaining_cleanup() -> None:
    """One resource failure cannot prevent cleanup of later resources."""

    events: list[str] = []

    class FailingShutdownProvider:
        @property
        def name(self) -> str:
            return "stub"

        async def startup(self) -> None:
            return

        async def shutdown(self) -> None:
            events.append("provider shutdown")
            raise RuntimeError("boom")

        def cache_fingerprint(self) -> str:
            return "stub"

        async def platform(self) -> PlatformMetricsData:
            return PlatformMetricsData(cluster="c", capacity={}, allocated={})

    class StubRedis:
        async def aclose(self) -> None:
            events.append("redis shutdown")

    provider = FailingShutdownProvider()
    service = PlatformMetricsService(
        platform=provider.platform,
        cache=InMemoryTTLCache[CachedMetrics](ttl_seconds=60),
        key=lambda: "platform:4:c:stub",
    )
    runtime = MetricsRuntime(Settings(cache=CacheConfig(backend="memory")))
    runtime.wire(
        provider=provider,  # type: ignore[arg-type]
        platform_service=service,
        redis=StubRedis(),  # type: ignore[arg-type]
    )

    await runtime.shutdown()

    assert events == ["provider shutdown", "redis shutdown"]


def test_metrics_core_dir_lists_lazy_exports() -> None:
    """``dir(metrics.core)`` is only the lazy :data:`__all__` API, not module imports."""
    import metrics.core

    public = dir(metrics.core)
    assert public == sorted(metrics.core.__all__)
    assert "Settings" in public
    assert "create_app" in public
    assert "importlib" not in public
    assert "Any" not in public


def test_metrics_providers_dir_lists_lazy_exports() -> None:
    """``dir(metrics.providers)`` is only the lazy :data:`__all__` API, not module imports."""
    import metrics.providers

    public = dir(metrics.providers)
    assert public == sorted(metrics.providers.__all__)
    assert "KueueProvider" in public
    assert "importlib" not in public
    assert "Any" not in public


def test_lazy_core_settings_does_not_import_factory() -> None:
    """``from metrics.core import Settings`` must not load the FastAPI factory (no app)."""
    code = r"""
import sys
from metrics.core import Settings
_ = Settings
if "metrics.core.factory" in sys.modules:
    raise SystemExit(2)
print("ok")
"""
    result = subprocess.run(
        [sys.executable, "-c", code],
        check=False,
        capture_output=True,
        text=True,
    )
    assert result.returncode == 0, result.stdout + result.stderr


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
