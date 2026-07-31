from __future__ import annotations

import asyncio
from unittest.mock import AsyncMock

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


def _fingerprint(
    *,
    provider_name: str = "kueue",
    kube_api_url: str = "https://kubernetes.default.svc",
    resource_path: str = "/apis/kueue.x-k8s.io/v1beta2/clusterqueues",
    queues: list[str] | None = None,
    token: str | None = None,
    ca_file: str | None = None,
    timeout: float = 10.0,
    telemetry_enabled: bool = False,
) -> str:
    settings = Settings(
        cache=CacheConfig(backend="memory"),
        sources=SourceConfig(platform="kueue"),
        providers=ProviderConfigs(
            kueue=KueueProviderConfig(
                kube_api_url=kube_api_url,
                kube_clusterqueue_path=resource_path,
                cluster_queues=queues or ["cq-a", "cq-b"],
                kube_api_token=token,
                ca_file=ca_file,
                kube_request_timeout_seconds=timeout,
            )
        ),
        otel_metrics_enabled=telemetry_enabled,
    )

    class NamedKueueProvider(KueueProvider):
        @property
        def name(self) -> str:
            return provider_name

    provider = NamedKueueProvider(settings, AsyncMock(spec=httpx.AsyncClient))
    return provider.cache_fingerprint()


def test_kueue_provider_fingerprint_covers_provider_endpoint_path_and_queue_membership() -> None:
    baseline = _fingerprint()

    assert _fingerprint(provider_name="other") != baseline
    assert _fingerprint(kube_api_url="https://other.example") != baseline
    assert _fingerprint(resource_path="/apis/example/v1/clusterqueues") != baseline
    assert _fingerprint(queues=["cq-a", "cq-c"]) != baseline
    assert _fingerprint(queues=["cq-b", "cq-a"]) == baseline


def test_kueue_provider_fingerprint_excludes_secrets_transport_and_telemetry() -> None:
    baseline = _fingerprint()

    assert _fingerprint(token="secret") == baseline
    assert _fingerprint(ca_file="/secret/ca.crt") == baseline
    assert _fingerprint(timeout=99.0) == baseline
    assert _fingerprint(telemetry_enabled=True) == baseline


@pytest.mark.anyio
async def test_kueue_provider_closes_owned_client_once() -> None:
    settings = Settings(cache=CacheConfig(backend="memory"))
    client = AsyncMock(spec=httpx.AsyncClient)
    provider = KueueProvider(settings, client)

    await provider.shutdown()
    await provider.shutdown()

    client.aclose.assert_awaited_once_with()


@pytest.mark.anyio
@pytest.mark.parametrize("close_error", [RuntimeError("boom"), asyncio.CancelledError()])
async def test_kueue_provider_shutdown_can_retry_after_close_failure(
    close_error: BaseException,
) -> None:
    settings = Settings(cache=CacheConfig(backend="memory"))
    client = AsyncMock(spec=httpx.AsyncClient)
    client.aclose.side_effect = [close_error, None]
    provider = KueueProvider(settings, client)

    with pytest.raises(type(close_error)):
        await provider.shutdown()
    await provider.shutdown()

    assert client.aclose.await_count == 2


@pytest.mark.anyio
async def test_kueue_provider_startup_validates_clusterqueues_concurrently(
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
    both_started = asyncio.Event()
    list_path = settings.providers.kueue.kube_clusterqueue_path
    monkeypatch.setattr("metrics.providers.kueue.resolve_kube_token", lambda *a, **k: "t")

    async def fake_get_json(_client, url: str, *, headers: dict[str, str]):
        assert headers == {"Authorization": "Bearer t"}
        calls.append(url)
        if len(calls) == 2:
            both_started.set()
        await asyncio.wait_for(both_started.wait(), timeout=0.5)
        if url.endswith(f"{list_path}/cq-a"):
            return {"metadata": {"name": "cq-a"}}
        if url.endswith(f"{list_path}/cq-b"):
            return {"metadata": {"name": "cq-b"}}
        raise AssertionError(f"unexpected url {url}")

    monkeypatch.setattr("metrics.providers.kueue.kube_get_json", fake_get_json)
    client = kueue_http_client(settings.providers.kueue)
    try:
        provider = KueueProvider(settings, client)
        await asyncio.wait_for(provider.startup(), timeout=1)
    finally:
        await client.aclose()

    assert calls == [
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


@pytest.mark.anyio
async def test_kueue_provider_startup_request_error_is_sanitized(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    settings = Settings(
        cache=CacheConfig(backend="memory"),
        providers=ProviderConfigs(
            kueue=KueueProviderConfig(
                kube_api_url="https://kubernetes.default.svc",
                kube_api_token="secret-token",
                cluster_queues=["cq-a"],
            )
        ),
    )
    request = httpx.Request(
        "GET",
        "https://kubernetes.default.svc/apis/kueue/clusterqueues?token=secret-token",
    )

    async def fail(*_args, **_kwargs):
        raise httpx.ConnectError("raw transport secret", request=request)

    monkeypatch.setattr("metrics.providers.kueue.kube_get_json", fail)
    client = kueue_http_client(settings.providers.kueue)
    try:
        with pytest.raises(RuntimeStartupError) as exc_info:
            await KueueProvider(settings, client).startup()
    finally:
        await client.aclose()

    message = str(exc_info.value)
    assert message == "Cannot reach Kubernetes API for Kueue startup checks"
    assert "secret-token" not in message
    assert "kubernetes.default.svc" not in message
    assert "ConnectError" not in message


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
