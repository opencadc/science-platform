from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from metrics.core.factory import create_app
from metrics.core.runtime import MetricsRuntime
from metrics.core.settings import (
    CacheConfig,
    KueueProviderConfig,
    ProviderConfigs,
    Settings,
    SourceConfig,
)
from metrics.errors import RuntimeStartupError
from metrics.providers.kueue import KueueProvider, kueue_http_client


@pytest.mark.anyio
async def test_kueue_provider_startup_requires_kube_url() -> None:
    s = Settings(
        cache=CacheConfig(backend="memory"),
        sources=SourceConfig(platform="kueue"),
        providers=ProviderConfigs(
            kueue=KueueProviderConfig(
                kube_api_url=None,
                cluster_queues=["a"],
                cohort="c",
            )
        ),
    )
    c = kueue_http_client(s.providers.kueue)
    p = KueueProvider(s, c)
    with pytest.raises(RuntimeStartupError, match="KUBE_API_URL"):
        await p.startup()
    await c.aclose()


def test_kueue_app_lifespan_invokes_runtime_start(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    called = 0

    async def fake_start(self: MetricsRuntime) -> None:
        nonlocal called
        called += 1
        assert self.settings.providers.kueue.cluster_queues == ["cq-proton"]

    monkeypatch.setattr(MetricsRuntime, "start", fake_start)

    settings = Settings(
        cache=CacheConfig(backend="memory"),
        sources=SourceConfig(platform="kueue"),
        providers=ProviderConfigs(
            kueue=KueueProviderConfig(
                kube_api_url="https://kubernetes.default.svc",
                cluster_queues=["cq-proton"],
                cohort="cohort-atom",
            )
        ),
    )
    with TestClient(create_app(settings=settings)) as client:
        assert client.get("/healthz").status_code == 200
    assert called == 1


def test_kueue_app_fails_when_runtime_start_raises(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    async def boom(self: MetricsRuntime) -> None:  # noqa: ARG001
        raise RuntimeStartupError("misconfigured")

    monkeypatch.setattr(MetricsRuntime, "start", boom)
    # Factory already imports create_app; patch on module used in lifespan
    monkeypatch.setattr("metrics.core.runtime.MetricsRuntime", MetricsRuntime)
    settings = Settings(
        cache=CacheConfig(backend="memory"),
        sources=SourceConfig(platform="kueue"),
        providers=ProviderConfigs(
            kueue=KueueProviderConfig(
                kube_api_url="https://kubernetes.default.svc",
                cluster_queues=["cq-proton"],
                cohort="cohort-atom",
            )
        ),
    )
    with pytest.raises(RuntimeStartupError, match="misconfigured"):
        with TestClient(create_app(settings=settings)):
            pass
