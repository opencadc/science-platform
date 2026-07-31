from __future__ import annotations

import asyncio

import httpx
import kr8s
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
from metrics.providers.kueue import KueueProvider
from tests.fakes import FakeKueueApi


def _settings(
    queues: list[str],
    *,
    api_version: str = "kueue.x-k8s.io/v1beta2",
    timeout: float = 10.0,
    telemetry_enabled: bool = False,
) -> Settings:
    return Settings(
        cache=CacheConfig(backend="memory"),
        sources=SourceConfig(platform="kueue"),
        providers=ProviderConfigs(
            kueue=KueueProviderConfig(
                cluster_queues=queues,
                kueue_api_version=api_version,
                kube_request_timeout_seconds=timeout,
            )
        ),
        otel_metrics_enabled=telemetry_enabled,
    )


def _fingerprint(
    *,
    provider_name: str = "kueue",
    api_version: str = "kueue.x-k8s.io/v1beta2",
    queues: list[str] | None = None,
    timeout: float = 10.0,
    telemetry_enabled: bool = False,
) -> str:
    settings = _settings(
        queues or ["cq-a", "cq-b"],
        api_version=api_version,
        timeout=timeout,
        telemetry_enabled=telemetry_enabled,
    )

    class NamedKueueProvider(KueueProvider):
        @property
        def name(self) -> str:
            return provider_name

    return NamedKueueProvider(settings, api=FakeKueueApi()).cache_fingerprint()


def test_kueue_provider_fingerprint_covers_provider_api_version_and_queue_membership() -> None:
    baseline = _fingerprint()

    assert _fingerprint(provider_name="other") != baseline
    assert _fingerprint(api_version="kueue.x-k8s.io/v1beta1") != baseline
    assert _fingerprint(queues=["cq-a", "cq-c"]) != baseline
    assert _fingerprint(queues=["cq-b", "cq-a"]) == baseline


def test_kueue_provider_fingerprint_excludes_transport_and_telemetry() -> None:
    baseline = _fingerprint()

    assert _fingerprint(timeout=99.0) == baseline
    assert _fingerprint(telemetry_enabled=True) == baseline


@pytest.mark.anyio
async def test_kueue_provider_shutdown_is_idempotent_and_releases_api() -> None:
    provider = KueueProvider(_settings(["cq-a"]), api=FakeKueueApi())

    await provider.shutdown()
    await provider.shutdown()

    assert provider._api is None  # noqa: SLF001 - lifecycle contract


@pytest.mark.anyio
async def test_kueue_provider_startup_validates_clusterqueues_concurrently() -> None:
    both_started = asyncio.Event()
    started = 0

    def coordinated(name: str):
        async def load() -> dict[str, object]:
            nonlocal started
            started += 1
            if started == 2:
                both_started.set()
            await asyncio.wait_for(both_started.wait(), timeout=0.5)
            return {"metadata": {"name": name}}

        return load

    api = FakeKueueApi({"cq-a": coordinated("cq-a"), "cq-b": coordinated("cq-b")})
    provider = KueueProvider(_settings(["cq-a", "cq-b"]), api=api)
    await asyncio.wait_for(provider.startup(), timeout=1)

    assert sorted(api.requested) == ["cq-a", "cq-b"]


@pytest.mark.anyio
async def test_kueue_startup_cancels_and_awaits_siblings_before_raising() -> None:
    sibling_started = asyncio.Event()
    sibling_cancelled = asyncio.Event()
    release_sibling = asyncio.Event()
    sibling_completed = asyncio.Event()

    async def failing() -> object:
        await sibling_started.wait()
        return kr8s.NotFoundError("cq-failing")

    async def slow() -> object:
        sibling_started.set()
        try:
            await release_sibling.wait()
        except asyncio.CancelledError:
            sibling_cancelled.set()
            raise
        sibling_completed.set()
        return {"metadata": {"name": "cq-slow"}}

    api = FakeKueueApi({"cq-failing": failing, "cq-slow": slow})
    provider = KueueProvider(_settings(["cq-failing", "cq-slow"]), api=api)
    try:
        with pytest.raises(RuntimeStartupError, match="ClusterQueue 'cq-failing'.*not found"):
            await provider.startup()
        assert sibling_cancelled.is_set()
        assert not sibling_completed.is_set()
    finally:
        release_sibling.set()
        await asyncio.sleep(0)


@pytest.mark.anyio
async def test_kueue_provider_startup_fails_fast_with_missing_queue_name() -> None:
    api = FakeKueueApi({"cq-a": {"metadata": {"name": "cq-a"}}})
    provider = KueueProvider(_settings(["cq-a", "cq-missing"]), api=api)
    with pytest.raises(RuntimeStartupError, match="ClusterQueue 'cq-missing'.*not found"):
        await provider.startup()


@pytest.mark.anyio
async def test_kueue_provider_startup_fails_fast_with_forbidden_queue_name() -> None:
    forbidden = kr8s.ServerError("Forbidden", response=httpx.Response(403))
    api = FakeKueueApi({"cq-forbidden": forbidden})
    provider = KueueProvider(_settings(["cq-forbidden"]), api=api)
    with pytest.raises(
        RuntimeStartupError,
        match="ClusterQueue 'cq-forbidden'.*forbidden",
    ):
        await provider.startup()


@pytest.mark.anyio
async def test_kueue_provider_startup_requires_cluster_queues() -> None:
    provider = KueueProvider(_settings([]), api=FakeKueueApi())
    with pytest.raises(RuntimeStartupError, match="CLUSTER_QUEUES"):
        await provider.startup()


@pytest.mark.anyio
async def test_kueue_provider_startup_api_construction_failure_is_sanitized(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    async def broken_api(*_args: object, **_kwargs: object) -> object:
        raise RuntimeError("secret kubeconfig path /home/user/.kube/config")

    monkeypatch.setattr("metrics.providers.kueue.create_kube_api", broken_api)
    provider = KueueProvider(_settings(["cq-a"]))
    with pytest.raises(RuntimeStartupError) as exc_info:
        await provider.startup()

    message = str(exc_info.value)
    assert message == "Cannot configure Kubernetes API access for Kueue startup checks"
    assert "kubeconfig" not in message


@pytest.mark.anyio
async def test_kueue_provider_startup_request_error_is_sanitized() -> None:
    error = httpx.ConnectError("raw transport secret at https://kubernetes.default.svc")
    provider = KueueProvider(_settings(["cq-a"]), api=FakeKueueApi({"cq-a": error}))
    with pytest.raises(RuntimeStartupError) as exc_info:
        await provider.startup()

    message = str(exc_info.value)
    assert message == "Cannot reach Kubernetes API for Kueue startup checks"
    assert "secret" not in message
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

    settings = _settings(["cq-proton"])
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
    settings = _settings(["cq-proton"])
    with pytest.raises(RuntimeStartupError, match="misconfigured"):
        with TestClient(create_app(settings=settings)):
            pass
