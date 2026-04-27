"""Stub providers and small kube helper paths for coverage."""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

import httpx
import pytest

from metrics.cache import InMemoryTTLCache
from metrics.core.settings import (
    CacheConfig,
    ProviderConfigs,
    Settings,
    SourceConfig,
)
from metrics.providers.base import MetricScope, ProviderMetrics, UnsupportedMetricScope
from metrics.schemas.metrics import PlatformMetricsData
from metrics.services.platform import CachedMetrics, PlatformMetricsService
from metrics.telemetry import NoopMetricsRecorder

from metrics.providers.kube import KubeProvider
from metrics.providers.kube import (
    resolve_kube_token,
    resolve_kube_verify,
)
from metrics.providers.prometheus import PrometheusProvider


@pytest.mark.anyio
async def test_kube_provider_lifecycle() -> None:
    p = KubeProvider(Settings())
    assert p.name == "kube"
    assert len(p.cache_fingerprint()) == 24
    await p.startup()
    await p.shutdown()


@pytest.mark.anyio
async def test_prometheus_provider_lifecycle() -> None:
    p = PrometheusProvider(Settings())
    assert p.name == "prometheus"
    await p.startup()
    await p.shutdown()


@pytest.mark.anyio
async def test_prometheus_metrics_default_platform_raises() -> None:
    m = PrometheusProvider(Settings()).metrics()
    with pytest.raises(UnsupportedMetricScope) as ei:
        await m.platform()
    assert ei.value.scope is MetricScope.PLATFORM


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
async def test_metrics_runtime_shutdown_awaits_provider_shutdown() -> None:
    """Runtime owns Adapter lifecycle: shutdown the provider Implementation before I/O close."""

    class StubMetrics(ProviderMetrics):
        supported_scopes: frozenset[MetricScope] = frozenset({MetricScope.PLATFORM})

        async def platform(self) -> PlatformMetricsData:
            return PlatformMetricsData(
                cluster="c",
                capacity={},
                allocated={},
            )

    shutdown_calls: list[str] = []

    class StubProvider:
        @property
        def name(self) -> str:
            return "stub-adapter"

        async def startup(self) -> None:
            return

        async def shutdown(self) -> None:
            shutdown_calls.append("shutdown")

        def cache_fingerprint(self) -> str:
            return "f"

        def metrics(self) -> ProviderMetrics:
            return StubMetrics()

    settings = Settings(
        cache=CacheConfig(backend="memory"),
        sources=SourceConfig(platform="kueue"),
        providers=ProviderConfigs(),
    )
    stub = StubProvider()
    client = httpx.AsyncClient()

    async def load() -> PlatformMetricsData:
        return await stub.metrics().platform()

    from metrics.core.runtime import MetricsRuntime  # local: avoid import cycle during collection

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
        platform_client=client,
        platform_provider=stub,  # type: ignore[arg-type]
        platform_service=svc,
        redis=None,
    )
    await runtime.shutdown()

    assert shutdown_calls == ["shutdown"]
    assert client.is_closed
    with pytest.raises(RuntimeError, match="not initialised"):
        _ = runtime.platform_service
    with pytest.raises(RuntimeError, match="not initialised"):
        await runtime.get_platform_metrics()


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
    assert "PrometheusProvider" in public
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

    from metrics.providers.kube import kube_parallel_get_json

    c = httpx.AsyncClient()
    try:
        assert await kube_parallel_get_json(c, [], headers={}) == []
    finally:
        await c.aclose()
