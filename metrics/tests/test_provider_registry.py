"""Registry and platform source validation."""

from __future__ import annotations

import subprocess
import sys

import pytest

from metrics.core.provider_registry import (
    assert_platform_metrics_scope_capability,
    assert_supported_platform_source,
    build_platform_provider_bundle,
    supported_platform_sources,
)
from metrics.providers.base import ProviderMetrics
from metrics.core.settings import CacheConfig, ProviderConfigs, Settings, SourceConfig
from metrics.errors import RuntimeStartupError


def test_supported_platform_sources_contains_kueue() -> None:
    assert "kueue" in supported_platform_sources()


def test_name_only_registry_surface_does_not_import_kueue() -> None:
    """List/validate call sites should not pay the Kueue Adapter import graph."""
    code = r"""
import sys
import metrics.core.provider_registry as pr
_ = pr.supported_platform_sources()
if "metrics.providers.kueue" in sys.modules:
    raise SystemExit(2)
from metrics.core.settings import CacheConfig, ProviderConfigs, Settings, SourceConfig

s = Settings(
    cache=CacheConfig(backend="memory"),
    sources=SourceConfig(platform="kueue"),
    providers=ProviderConfigs(),
)
pr.assert_supported_platform_source(s)
if "metrics.providers.kueue" in sys.modules:
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


def test_assert_platform_metrics_scope_capability_rejects_empty_scopes() -> None:
    """A platform Adapter's Implementation must list MetricScope.PLATFORM before serving."""

    class BadProvider:
        @property
        def name(self) -> str:
            return "bad-mock"

        def metrics(self) -> ProviderMetrics:
            return ProviderMetrics()

    with pytest.raises(RuntimeStartupError, match="MetricScope|PLATFORM|supported_scopes"):
        assert_platform_metrics_scope_capability(BadProvider())  # type: ignore[arg-type]


def test_assert_supported_platform_source_rejects_unknown() -> None:
    settings = Settings(
        cache=CacheConfig(backend="memory"),
        sources=SourceConfig(platform="unknown-source"),
    )
    with pytest.raises(RuntimeStartupError, match="Unsupported platform source"):
        assert_supported_platform_source(settings)


@pytest.mark.anyio
async def test_build_platform_provider_bundle_returns_kueue_client_and_provider() -> None:
    settings = Settings(
        cache=CacheConfig(backend="memory"),
        sources=SourceConfig(platform="kueue"),
        providers=ProviderConfigs(),
    )
    bundle = build_platform_provider_bundle(settings)
    assert bundle.provider.name == "kueue"
    assert not bundle.http_client.is_closed
    await bundle.http_client.aclose()
