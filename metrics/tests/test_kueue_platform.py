from __future__ import annotations

import asyncio
from collections.abc import Mapping

import httpx
import pytest

from metrics.core.settings import (
    KueueProviderConfig,
    ProviderConfigs,
    Settings,
    SourceConfig,
)
from metrics.errors import ProviderExecutionError
from metrics.providers.kueue import KueueProvider, kube_parallel_get_json
from metrics.schemas.metrics import PlatformMetricsData


@pytest.mark.anyio
async def test_parallel_get_cancels_and_awaits_siblings_before_raising() -> None:
    sibling_started = asyncio.Event()
    sibling_cancelled = asyncio.Event()
    release_sibling = asyncio.Event()
    sibling_completed = asyncio.Event()

    async def respond(request: httpx.Request) -> httpx.Response:
        if request.url.path.endswith("/failing"):
            await sibling_started.wait()
            return httpx.Response(500, request=request)
        sibling_started.set()
        try:
            await release_sibling.wait()
        except asyncio.CancelledError:
            sibling_cancelled.set()
            raise
        sibling_completed.set()
        return httpx.Response(200, json={"metadata": {"name": "slow"}}, request=request)

    client = httpx.AsyncClient(transport=httpx.MockTransport(respond))
    try:
        with pytest.raises(httpx.HTTPStatusError):
            await kube_parallel_get_json(
                client,
                [
                    "https://kubernetes.default.svc/clusterqueues/failing",
                    "https://kubernetes.default.svc/clusterqueues/slow",
                ],
                headers={},
            )
        assert sibling_cancelled.is_set()
        assert not sibling_completed.is_set()
    finally:
        release_sibling.set()
        await asyncio.sleep(0)
        await client.aclose()


async def _read_platform_doc(
    monkeypatch: pytest.MonkeyPatch,
    doc: Mapping[str, object],
) -> PlatformMetricsData:
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

    async def fake_parallel(*_args, **_kwargs):
        return [doc]

    monkeypatch.setattr("metrics.providers.kueue.kube_parallel_get_json", fake_parallel)
    client = httpx.AsyncClient()
    try:
        return await KueueProvider(settings, client).platform()
    finally:
        await client.aclose()


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
                                {"name": "cpu", "nominalQuota": "10.1"},
                                {"name": "memory", "nominalQuota": "20Gi"},
                                {
                                    "name": "ephemeral-storage",
                                    "nominalQuota": "512Mi",
                                },
                                {"name": "nvidia.com/gpu", "nominalQuota": "0.1"},
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
                        {"name": "nvidia.com/gpu", "total": "0.1"},
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
                                {"name": "cpu", "nominalQuota": "5.2"},
                                {
                                    "name": "ephemeral-storage",
                                    "nominalQuota": "1.5Gi",
                                },
                                {"name": "nvidia.com/gpu", "nominalQuota": "0.2"},
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
                        {"name": "nvidia.com/gpu", "total": "0.2"},
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
        data = await KueueProvider(settings, client).platform()
    finally:
        await client.aclose()
    payload = data.model_dump()
    assert set(payload.keys()) == {"scope", "cluster", "capacity", "allocated"}
    assert "borrowed" not in payload
    assert "lending" not in payload
    assert data.capacity["cpu"] == "15.3"
    assert data.allocated["cpu"] == "1"
    assert data.allocated["memory"] == "2Gi"
    assert data.capacity["memory"] == "20Gi"
    assert data.capacity["ephemeral-storage"] == "2Gi"
    assert data.allocated["ephemeral-storage"] == "0Gi"
    assert data.capacity["nvidia.com/gpu"] == "0.3"
    assert data.allocated["nvidia.com/gpu"] == "0.3"
    assert list(data.capacity) == sorted(data.capacity)
    assert list(data.allocated) == sorted(data.allocated)


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
        data = await KueueProvider(settings, client).platform()
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
        data = await KueueProvider(settings, client).platform()
    finally:
        await client.aclose()
    assert data.allocated["cpu"] == "0"
    assert data.allocated["memory"] == "0Gi"
    assert set(data.allocated.keys()) == set(data.capacity.keys())


@pytest.mark.anyio
@pytest.mark.parametrize(
    "resource",
    [
        {"name": "cpu"},
        {"name": "cpu", "nominalQuota": "bad-secret-value"},
        {"name": "cpu", "nominalQuota": "-1"},
        {"name": "cpu", "nominalQuota": " 1 "},
        {"name": "cpu", "nominalQuota": 1},
    ],
)
async def test_kueue_platform_rejects_corrupt_capacity_quantities(
    monkeypatch: pytest.MonkeyPatch,
    resource: dict[str, object],
) -> None:
    doc = {
        "spec": {
            "resourceGroups": [{"flavors": [{"resources": [resource]}]}],
        },
    }

    with pytest.raises(
        ProviderExecutionError,
        match="Kueue platform data contained an invalid resource quantity",
    ) as exc_info:
        await _read_platform_doc(monkeypatch, doc)
    assert "bad-secret-value" not in str(exc_info.value)


@pytest.mark.anyio
@pytest.mark.parametrize(
    "resource",
    [
        {"name": "cpu"},
        {"name": "cpu", "total": "bad-secret-value"},
    ],
)
async def test_kueue_platform_rejects_missing_allocation_quantity(
    monkeypatch: pytest.MonkeyPatch,
    resource: dict[str, str],
) -> None:
    doc = {
        "spec": {
            "resourceGroups": [
                {
                    "flavors": [
                        {
                            "resources": [
                                {"name": "cpu", "nominalQuota": "1"},
                            ]
                        }
                    ]
                }
            ],
        },
        "status": {
            "flavorsUsage": [{"resources": [resource]}],
        },
    }

    with pytest.raises(ProviderExecutionError):
        await _read_platform_doc(monkeypatch, doc)


@pytest.mark.anyio
@pytest.mark.parametrize(
    ("body", "content_type"),
    [
        ("secret raw payload", "application/json"),
        ('["not", "an", "object"]', "application/json"),
    ],
)
async def test_kueue_http_rejects_invalid_documents_without_payload_leaks(
    body: str,
    content_type: str,
) -> None:
    async def respond(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            text=body,
            headers={"content-type": content_type},
        )

    async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
        with pytest.raises(ProviderExecutionError) as exc_info:
            await kube_parallel_get_json(
                client,
                ["https://kubernetes.default.svc/clusterqueues/cq-a"],
                headers={"Authorization": "Bearer secret-token"},
            )

    message = str(exc_info.value)
    assert "secret" not in message
    assert "kubernetes.default.svc" not in message
    assert "list" not in message


@pytest.mark.anyio
async def test_kueue_platform_rejects_invalid_nested_shape_without_payload_leaks(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    doc = {"spec": {"resourceGroups": "secret-shape"}}

    with pytest.raises(
        ProviderExecutionError,
        match="Kueue platform data contained an invalid object shape",
    ) as exc_info:
        await _read_platform_doc(monkeypatch, doc)

    assert "secret-shape" not in str(exc_info.value)
