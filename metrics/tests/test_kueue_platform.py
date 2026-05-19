from __future__ import annotations

import httpx
import pytest

from metrics.core.settings import (
    KueueProviderConfig,
    ProviderConfigs,
    Settings,
    SourceConfig,
)
from metrics.providers.kueue import KueueMetrics


@pytest.mark.anyio
async def test_kueue_platform_aggregates_configured_queues_only(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    settings = Settings(
        cluster_name="c",
        sources=SourceConfig(platform="kueue"),
        providers=ProviderConfigs(
            kueue=KueueProviderConfig(
                kube_api_url="https://kube.test",
                cluster_queues=["cq-a", "cq-b"],
            )
        ),
    )
    monkeypatch.setattr("metrics.providers.kueue.resolve_kube_token", lambda *a, **k: "t")

    cq_a = {
        "spec": {
            "resourceGroups": [
                {
                    "flavors": [
                        {
                            "resources": [
                                {"name": "cpu", "nominalQuota": "10"},
                                {"name": "memory", "nominalQuota": "20Gi"},
                            ]
                        }
                    ]
                }
            ]
        },
        "status": {
            "flavorsUsage": [
                {
                    "resources": [
                        {"name": "cpu", "total": "1", "borrowed": "500m"},
                        {
                            "name": "memory",
                            "total": "2Gi",
                            "borrowed": "1Gi",
                        },
                    ]
                }
            ]
        },
    }
    cq_b = {
        "spec": {
            "resourceGroups": [
                {
                    "flavors": [
                        {
                            "resources": [
                                {"name": "cpu", "nominalQuota": "6"},
                            ]
                        }
                    ]
                }
            ]
        },
        "status": {
            "flavorsUsage": [
                {
                    "resources": [
                        {"name": "cpu", "total": "0", "borrowed": "0"},
                    ]
                }
            ]
        },
    }

    async def fake_parallel(_c, urls: list[str], *, headers, **kwargs):
        out: list[dict] = []
        for url in urls:
            if url.endswith("/clusterqueues/cq-a"):
                out.append(cq_a)
            elif url.endswith("/clusterqueues/cq-b"):
                out.append(cq_b)
            else:
                raise AssertionError(f"unexpected url {url!r}")
        return out

    monkeypatch.setattr(
        "metrics.providers.kueue.kube_parallel_get_json",
        fake_parallel,
    )
    client = httpx.AsyncClient()
    try:
        km = KueueMetrics(settings=settings, client=client, kueue_config=settings.providers.kueue)
        data = await km.platform()
    finally:
        await client.aclose()
    payload = data.model_dump()
    assert set(payload.keys()) == {"scope", "cluster", "capacity", "allocated"}
    assert "borrowed" not in payload
    assert "lending" not in payload
    assert data.capacity["cpu"] == "16"
    assert data.allocated["cpu"] == "1"
    assert data.allocated["memory"] == "2Gi"
    assert data.capacity["memory"] == "20Gi"
    assert "memory" in data.capacity
    assert "memory" in data.allocated


@pytest.mark.anyio
async def test_kueue_platform_subcore_cpu_uses_cores_in_capacity_and_allocated(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """100m in usage must not print as 100m while capacity prints whole cores."""
    settings = Settings(
        cluster_name="c",
        sources=SourceConfig(platform="kueue"),
        providers=ProviderConfigs(
            kueue=KueueProviderConfig(
                kube_api_url="https://kube.test",
                cluster_queues=["cq-a"],
            )
        ),
    )
    monkeypatch.setattr("metrics.providers.kueue.resolve_kube_token", lambda *a, **k: "t")
    cq = {
        "spec": {
            "resourceGroups": [
                {
                    "flavors": [
                        {
                            "resources": [
                                {"name": "cpu", "nominalQuota": "10"},
                                {"name": "memory", "nominalQuota": "20Gi"},
                            ]
                        }
                    ]
                }
            ]
        },
        "status": {
            "flavorsUsage": [
                {
                    "resources": [
                        {"name": "cpu", "total": "100m", "borrowed": "0"},
                    ]
                }
            ]
        },
    }

    async def fake_parallel(_c, urls: list[str], **_kwargs):
        out: list[dict] = []
        for url in urls:
            if url.endswith("/clusterqueues/cq-a"):
                out.append(cq)
            else:
                raise AssertionError(url)
        return out

    monkeypatch.setattr(
        "metrics.providers.kueue.kube_parallel_get_json",
        fake_parallel,
    )
    client = httpx.AsyncClient()
    try:
        km = KueueMetrics(settings=settings, client=client, kueue_config=settings.providers.kueue)
        data = await km.platform()
    finally:
        await client.aclose()
    assert data.capacity["cpu"] == "10"
    assert data.allocated["cpu"] == "0.1"


@pytest.mark.anyio
async def test_kueue_platform_zero_allocated_when_no_flavors_usage(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """No admitted workloads: allocated keys align with capacity, zeros explicit."""
    settings = Settings(
        cluster_name="c",
        sources=SourceConfig(platform="kueue"),
        providers=ProviderConfigs(
            kueue=KueueProviderConfig(
                kube_api_url="https://kube.test",
                cluster_queues=["cq-a"],
            )
        ),
    )
    monkeypatch.setattr("metrics.providers.kueue.resolve_kube_token", lambda *a, **k: "t")
    cq = {
        "spec": {
            "resourceGroups": [
                {
                    "flavors": [
                        {
                            "resources": [
                                {"name": "cpu", "nominalQuota": "10"},
                                {"name": "memory", "nominalQuota": "20Gi"},
                            ]
                        }
                    ]
                }
            ]
        },
        "status": {},
    }

    async def fake_parallel(_c, urls: list[str], **_kwargs):
        out: list[dict] = []
        for url in urls:
            if url.endswith("/clusterqueues/cq-a"):
                out.append(cq)
            else:
                raise AssertionError(url)
        return out

    monkeypatch.setattr(
        "metrics.providers.kueue.kube_parallel_get_json",
        fake_parallel,
    )
    client = httpx.AsyncClient()
    try:
        km = KueueMetrics(settings=settings, client=client, kueue_config=settings.providers.kueue)
        data = await km.platform()
    finally:
        await client.aclose()
    assert data.allocated["cpu"] == "0"
    assert data.allocated["memory"] == "0Gi"
    assert set(data.allocated.keys()) == set(data.capacity.keys())
