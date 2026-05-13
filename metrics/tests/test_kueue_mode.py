from __future__ import annotations

import hashlib

import httpx
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
async def test_kueue_provider_fingerprint_uses_queue_set_only() -> None:
    settings = Settings(
        cache=CacheConfig(backend="memory"),
        sources=SourceConfig(platform="kueue"),
        providers=ProviderConfigs(
            kueue=KueueProviderConfig(
                kube_api_url="https://kubernetes.default.svc",
                cluster_queues=["cq-b", "cq-a"],
            )
        ),
    )
    client = httpx.AsyncClient()
    try:
        provider = KueueProvider(settings, client)
        expected_raw = "|".join(sorted(["cq-b", "cq-a"]))
        expected = hashlib.sha256(expected_raw.encode("utf-8")).hexdigest()[:24]
        assert provider.cache_fingerprint() == expected
    finally:
        await client.aclose()


@pytest.mark.anyio
async def test_kueue_provider_startup_validates_list_then_each_clusterqueue(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    settings = Settings(
        cache=CacheConfig(backend="memory"),
        sources=SourceConfig(platform="kueue"),
        providers=ProviderConfigs(
            kueue=KueueProviderConfig(
                kube_api_url="https://kubernetes.default.svc",
                cluster_queues=["cq-a", "cq-b"],
            )
        ),
    )
    calls: list[str] = []
    list_path = settings.providers.kueue.kube_clusterqueue_path
    monkeypatch.setattr("metrics.providers.kueue.resolve_kube_token", lambda *a, **k: "t")

    async def fake_get_json(_client, url: str, *, headers: dict[str, str]):
        assert headers == {"Authorization": "Bearer t"}
        calls.append(url)
        if url.endswith(list_path):
            return {"items": []}
        if url.endswith(f"{list_path}/cq-a"):
            return {"metadata": {"name": "cq-a"}}
        if url.endswith(f"{list_path}/cq-b"):
            return {"metadata": {"name": "cq-b"}}
        raise AssertionError(f"unexpected url {url}")

    monkeypatch.setattr("metrics.providers.kueue.kube_get_json", fake_get_json)
    client = kueue_http_client(settings.providers.kueue)
    try:
        provider = KueueProvider(settings, client)
        await provider.startup()
    finally:
        await client.aclose()

    assert calls == [
        f"https://kubernetes.default.svc{list_path}",
        f"https://kubernetes.default.svc{list_path}/cq-a",
        f"https://kubernetes.default.svc{list_path}/cq-b",
    ]


@pytest.mark.anyio
async def test_kueue_provider_startup_fails_fast_with_missing_queue_name(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    settings = Settings(
        cache=CacheConfig(backend="memory"),
        sources=SourceConfig(platform="kueue"),
        providers=ProviderConfigs(
            kueue=KueueProviderConfig(
                kube_api_url="https://kubernetes.default.svc",
                cluster_queues=["cq-a", "cq-missing"],
            )
        ),
    )
    list_path = settings.providers.kueue.kube_clusterqueue_path
    monkeypatch.setattr("metrics.providers.kueue.resolve_kube_token", lambda *a, **k: "t")
    request = httpx.Request(
        "GET",
        f"https://kubernetes.default.svc{list_path}/cq-missing",
    )
    response = httpx.Response(404, request=request)

    async def fake_get_json(_client, url: str, *, headers: dict[str, str]):
        assert headers == {"Authorization": "Bearer t"}
        if url.endswith(list_path):
            return {"items": []}
        if url.endswith(f"{list_path}/cq-a"):
            return {"metadata": {"name": "cq-a"}}
        if url.endswith(f"{list_path}/cq-missing"):
            raise httpx.HTTPStatusError("Not Found", request=request, response=response)
        raise AssertionError(f"unexpected url {url}")

    monkeypatch.setattr("metrics.providers.kueue.kube_get_json", fake_get_json)
    client = kueue_http_client(settings.providers.kueue)
    try:
        provider = KueueProvider(settings, client)
        with pytest.raises(RuntimeStartupError, match="ClusterQueue 'cq-missing'.*not found"):
            await provider.startup()
    finally:
        await client.aclose()


@pytest.mark.anyio
async def test_kueue_provider_startup_fails_fast_with_forbidden_queue_name(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    settings = Settings(
        cache=CacheConfig(backend="memory"),
        sources=SourceConfig(platform="kueue"),
        providers=ProviderConfigs(
            kueue=KueueProviderConfig(
                kube_api_url="https://kubernetes.default.svc",
                cluster_queues=["cq-forbidden"],
            )
        ),
    )
    list_path = settings.providers.kueue.kube_clusterqueue_path
    monkeypatch.setattr("metrics.providers.kueue.resolve_kube_token", lambda *a, **k: "t")
    request = httpx.Request(
        "GET",
        f"https://kubernetes.default.svc{list_path}/cq-forbidden",
    )
    response = httpx.Response(403, request=request)

    async def fake_get_json(_client, url: str, *, headers: dict[str, str]):
        assert headers == {"Authorization": "Bearer t"}
        if url.endswith(list_path):
            return {"items": []}
        if url.endswith(f"{list_path}/cq-forbidden"):
            raise httpx.HTTPStatusError("Forbidden", request=request, response=response)
        raise AssertionError(f"unexpected url {url}")

    monkeypatch.setattr("metrics.providers.kueue.kube_get_json", fake_get_json)
    client = kueue_http_client(settings.providers.kueue)
    try:
        provider = KueueProvider(settings, client)
        with pytest.raises(
            RuntimeStartupError,
            match="ClusterQueue 'cq-forbidden'.*forbidden",
        ):
            await provider.startup()
    finally:
        await client.aclose()


@pytest.mark.anyio
async def test_kueue_provider_startup_requires_kube_url() -> None:
    s = Settings(
        cache=CacheConfig(backend="memory"),
        sources=SourceConfig(platform="kueue"),
        providers=ProviderConfigs(
            kueue=KueueProviderConfig(
                kube_api_url=None,
                cluster_queues=["a"],
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
            )
        ),
    )
    with pytest.raises(RuntimeStartupError, match="misconfigured"):
        with TestClient(create_app(settings=settings)):
            pass
