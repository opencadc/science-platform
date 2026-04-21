from __future__ import annotations

import pytest

from metrics.config import Settings
from metrics.errors import ProviderExecutionError, ProviderUnavailableError
from metrics.providers.kueue import KueueCapacityProvider
from metrics.providers.node import NodeCapacityProvider
from metrics.providers.prometheus import PrometheusUsageProvider


@pytest.mark.anyio
async def test_kueue_provider_reads_capacity(monkeypatch: pytest.MonkeyPatch) -> None:
    doc = {
        "spec": {
            "resourceGroups": [
                {
                    "flavors": [
                        {
                            "resources": [
                                {"name": "cpu", "nominalQuota": "100"},
                                {"name": "memory", "nominalQuota": "512Gi"},
                            ]
                        }
                    ]
                }
            ]
        }
    }

    async def fake_parallel(urls: list[str], **kwargs):
        assert len(urls) == 1
        assert "/clusterqueues/cq-test" in urls[0]
        return [doc]

    monkeypatch.setattr("metrics.providers.kueue.kube_parallel_get_json", fake_parallel)

    settings = Settings(
        kube_api_url="https://kube.local",
        kueue_cluster_queues=["cq-test"],
    )
    provider = KueueCapacityProvider(settings)

    reading = await provider.get_capacity()
    assert reading.cpu_cores == 100.0
    assert reading.memory_gib == 512.0
    assert reading.source == "kueue"


@pytest.mark.anyio
async def test_kueue_provider_unavailable_without_url() -> None:
    provider = KueueCapacityProvider(Settings())
    with pytest.raises(ProviderUnavailableError):
        await provider.get_capacity()


@pytest.mark.anyio
async def test_node_provider_filters_unschedulable_nodes(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    payload = {
        "items": [
            {
                "spec": {"unschedulable": False},
                "status": {
                    "capacity": {"cpu": "8", "memory": "32Gi"},
                    "conditions": [{"type": "Ready", "status": "True"}],
                },
            },
            {
                "spec": {"unschedulable": True},
                "status": {
                    "capacity": {"cpu": "100", "memory": "100Gi"},
                    "conditions": [{"type": "Ready", "status": "True"}],
                },
            },
        ]
    }

    async def fake_request_json(*args, **kwargs):
        return payload

    monkeypatch.setattr("metrics.providers.node._request_json", fake_request_json)

    settings = Settings(kube_api_url="https://kube.local")
    provider = NodeCapacityProvider(settings)

    reading = await provider.get_capacity()
    assert reading.cpu_cores == 8.0
    assert reading.memory_gib == 32.0


@pytest.mark.anyio
async def test_prometheus_provider_reads_usage(monkeypatch: pytest.MonkeyPatch) -> None:
    responses = [
        {
            "status": "success",
            "data": {"result": [{"value": [1712450000, "11.5"]}]},
        },
        {
            "status": "success",
            "data": {"result": [{"value": [1712450000, str(2 * 1024**3)]}]},
        },
    ]

    async def fake_request_json(*args, **kwargs):
        return responses.pop(0)

    monkeypatch.setattr(
        "metrics.providers.prometheus._request_json",
        fake_request_json,
    )

    provider = PrometheusUsageProvider(Settings(prometheus_url="https://prom.local"))
    reading = await provider.get_usage()
    assert reading.requested_cpu_cores == 11.5
    assert reading.requested_memory_gib == 2.0


@pytest.mark.anyio
async def test_prometheus_provider_reads_user_scoped_usage(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    responses = [
        {
            "status": "success",
            "data": {"result": [{"value": [1712450000, "3"]}]},
        },
        {
            "status": "success",
            "data": {"result": [{"value": [1712450000, str(4 * 1024**3)]}]},
        },
    ]
    seen_queries: list[str] = []

    async def fake_request_json(*args, **kwargs):
        seen_queries.append(kwargs["params"]["query"])
        return responses.pop(0)

    monkeypatch.setattr(
        "metrics.providers.prometheus._request_json",
        fake_request_json,
    )

    provider = PrometheusUsageProvider(
        Settings(
            prometheus_url="https://prom.local",
            user_label_key="owner",
            resource_requests_metric_name="my_metric",
        )
    )
    reading = await provider.get_usage_for_user("alice")
    assert reading.requested_cpu_cores == 3.0
    assert reading.requested_memory_gib == 4.0
    assert 'owner="alice"' in seen_queries[0]
    assert seen_queries[0].startswith("sum(my_metric{")


@pytest.mark.anyio
async def test_prometheus_provider_reads_session_scoped_usage(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    responses = [
        {
            "status": "success",
            "data": {"result": [{"value": [1712450000, "2"]}]},
        },
        {
            "status": "success",
            "data": {"result": [{"value": [1712450000, str(2 * 1024**3)]}]},
        },
    ]
    seen_queries: list[str] = []

    async def fake_request_json(*args, **kwargs):
        seen_queries.append(kwargs["params"]["query"])
        return responses.pop(0)

    monkeypatch.setattr(
        "metrics.providers.prometheus._request_json",
        fake_request_json,
    )

    provider = PrometheusUsageProvider(
        Settings(
            prometheus_url="https://prom.local",
            user_label_key="owner",
            session_label_key="session",
        )
    )
    reading = await provider.get_usage_for_session("alice", 'sess"1')
    assert reading.requested_cpu_cores == 2.0
    assert reading.requested_memory_gib == 2.0
    assert 'owner="alice"' in seen_queries[0]
    assert 'session="sess\\"1"' in seen_queries[0]


@pytest.mark.anyio
async def test_prometheus_provider_non_success_status(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    async def fake_request_json(*args, **kwargs):
        return {"status": "error"}

    monkeypatch.setattr(
        "metrics.providers.prometheus._request_json",
        fake_request_json,
    )

    provider = PrometheusUsageProvider(Settings(prometheus_url="https://prom.local"))
    with pytest.raises(ProviderExecutionError):
        await provider.get_usage()
