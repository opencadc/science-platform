"""Registry and platform source validation."""

from __future__ import annotations

import pytest

from metrics.core.registry import (
    bind_platform_metrics,
    build_platform_provider,
)
from metrics.core.settings import CacheConfig, ProviderConfigs, Settings, SourceConfig
from metrics.errors import RuntimeStartupError
from metrics.schemas.metrics import PlatformMetricsData


def test_bind_platform_metrics_rejects_provider_without_platform_read() -> None:
    class BadProvider:
        @property
        def name(self) -> str:
            return "bad-mock"

        async def startup(self) -> None:
            return

        async def shutdown(self) -> None:
            return

        def cache_fingerprint(self) -> str:
            return "bad"

    with pytest.raises(RuntimeStartupError, match="does not provide platform metrics"):
        bind_platform_metrics(BadProvider())


@pytest.mark.anyio
async def test_bind_platform_metrics_accepts_structural_capability() -> None:
    class PlatformProvider:
        @property
        def name(self) -> str:
            return "platform-mock"

        async def startup(self) -> None:
            return

        async def shutdown(self) -> None:
            return

        def cache_fingerprint(self) -> str:
            return "platform"

        async def platform(self) -> PlatformMetricsData:
            return PlatformMetricsData(cluster="c", capacity={}, allocated={})

    provider = PlatformProvider()
    platform = bind_platform_metrics(provider)
    assert await platform.platform() == await provider.platform()


@pytest.mark.anyio
async def test_build_platform_provider_returns_owned_kueue_provider() -> None:
    settings = Settings(
        cache=CacheConfig(backend="memory"),
        sources=SourceConfig(platform="kueue"),
        providers=ProviderConfigs(),
    )
    provider = build_platform_provider(settings)
    assert provider.name == "kueue"
    assert bind_platform_metrics(provider) is provider
    await provider.shutdown()
