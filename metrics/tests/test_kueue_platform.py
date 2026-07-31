from __future__ import annotations

import asyncio
from collections.abc import Mapping

import httpx
import kr8s
import pytest

from metrics.core.settings import (
    KueueProviderConfig,
    ProviderConfigs,
    Settings,
    SourceConfig,
)
from metrics.errors import ProviderExecutionError
from metrics.providers.kueue import (
    KueueProvider,
    fetch_cluster_queue_docs,
    format_resource_amount,
    merge_resource_totals,
    parse_resource_amount,
)
from metrics.schemas.metrics import PlatformMetricsData
from tests.fakes import FakeKueueApi

# --- Quantity parsing and formatting (quantiphy-backed provider helpers) ---


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        ("250m", 0.25),
        ("0", 0.0),
        ("1.5", 1.5),
        ("1k", 1000.0),
        ("1M", 1000000.0),
        ("1E", 1e18),
        ("1e3", 1000.0),
        ("1E-3", 0.001),
    ],
)
def test_parse_cpu_quantities_in_cores(raw: str, expected: float) -> None:
    assert parse_resource_amount("cpu", raw) == pytest.approx(expected)


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        ("512Mi", 0.5),
        ("1.5Gi", 1.5),
        ("1Ti", 1024.0),
        ("1Ei", 1073741824.0),
        ("1G", 0.9313225746154785),
    ],
)
def test_parse_memory_quantities_in_gib(raw: str, expected: float) -> None:
    assert parse_resource_amount("memory", raw) == pytest.approx(expected)


def test_format_cpu_always_uses_cores_not_millicores() -> None:
    """Capacity and allocated both use the same CPU unit (see docs/specs.md)."""
    assert format_resource_amount("cpu", 38.0) == "38"
    assert format_resource_amount("cpu", 0.1) == "0.1"
    assert format_resource_amount("cpu", 0.0) == "0"
    assert format_resource_amount("cpu", 0.0005) == "0.0005"
    assert format_resource_amount("cpu", 1.2300) == "1.23"
    assert format_resource_amount("cpu", 1e3) == "1000"


def test_format_memory_uses_gi() -> None:
    assert format_resource_amount("memory", 88.0) == "88Gi"
    assert format_resource_amount("memory", 0.097656) == "0.097656Gi"


def test_extended_resources_use_same_quantity_parser() -> None:
    assert parse_resource_amount("nvidia.com/gpu", "1.5") == 1.5
    assert parse_resource_amount("example.com/bandwidth", "1Mi") == 1048576.0


def test_float_accumulation_formats_cleanly() -> None:
    """0.1 + 0.1 + 0.1 must print as 0.3 despite float representation noise."""
    totals: dict[str, float] = {}
    for _ in range(3):
        merge_resource_totals(totals, "cpu", parse_resource_amount("cpu", "0.1"))
    assert format_resource_amount("cpu", totals["cpu"]) == "0.3"


def test_aggregate_and_format_overflow_are_rejected() -> None:
    maximum = float(2**63)
    with pytest.raises(ProviderExecutionError):
        merge_resource_totals({"cpu": maximum}, "cpu", 1.0)
    with pytest.raises(ProviderExecutionError):
        format_resource_amount("cpu", maximum)


@pytest.mark.parametrize("resource_name", ["memory", "ephemeral-storage"])
def test_storage_aggregate_overflow_is_checked_in_base_units(
    resource_name: str,
) -> None:
    totals: dict[str, float] = {}
    five_exbi = parse_resource_amount(resource_name, "5Ei")
    merge_resource_totals(totals, resource_name, five_exbi)
    with pytest.raises(ProviderExecutionError):
        merge_resource_totals(totals, resource_name, five_exbi)


def test_empty_resource_name_is_not_aggregated() -> None:
    totals: dict[str, float] = {}
    merge_resource_totals(totals, "", 1.0)
    assert totals == {}


@pytest.mark.parametrize(
    "raw",
    [
        None,
        "",
        "-1",
        "NaN",
        "Infinity",
        "9223372036854775808",
        " 1Gi ",
        1,
        0.1,
    ],
)
def test_invalid_quantities_are_rejected(raw: object) -> None:
    with pytest.raises(ProviderExecutionError):
        parse_resource_amount("nvidia.com/gpu", raw)


# --- fetch_cluster_queue_docs (kr8s access layer) ---


@pytest.mark.anyio
async def test_fetch_empty_names_makes_no_requests() -> None:
    api = FakeKueueApi()
    assert await fetch_cluster_queue_docs(api, "kueue.x-k8s.io/v1beta2", []) == []
    assert api.requested == []


@pytest.mark.anyio
async def test_fetch_returns_docs_in_request_order() -> None:
    api = FakeKueueApi({"cq-a": {"metadata": {"name": "cq-a"}}, "cq-b": {"metadata": {"name": "cq-b"}}})
    docs = await fetch_cluster_queue_docs(api, "kueue.x-k8s.io/v1beta2", ["cq-b", "cq-a"])
    assert [d["metadata"]["name"] for d in docs] == ["cq-b", "cq-a"]


@pytest.mark.anyio
async def test_fetch_missing_queue_raises_not_found() -> None:
    api = FakeKueueApi({"cq-a": {"metadata": {"name": "cq-a"}}})
    with pytest.raises(kr8s.NotFoundError):
        await fetch_cluster_queue_docs(api, "kueue.x-k8s.io/v1beta2", ["cq-a", "cq-gone"])


@pytest.mark.anyio
async def test_fetch_cancels_and_awaits_siblings_before_raising() -> None:
    sibling_started = asyncio.Event()
    sibling_cancelled = asyncio.Event()
    release_sibling = asyncio.Event()
    sibling_completed = asyncio.Event()

    async def failing() -> object:
        await sibling_started.wait()
        return kr8s.ServerError("boom", response=httpx.Response(500))

    async def slow() -> object:
        sibling_started.set()
        try:
            await release_sibling.wait()
        except asyncio.CancelledError:
            sibling_cancelled.set()
            raise
        sibling_completed.set()
        return {"metadata": {"name": "slow"}}

    api = FakeKueueApi({"cq-failing": failing, "cq-slow": slow})
    try:
        with pytest.raises(kr8s.ServerError):
            await fetch_cluster_queue_docs(
                api,
                "kueue.x-k8s.io/v1beta2",
                ["cq-failing", "cq-slow"],
            )
        assert sibling_cancelled.is_set()
        assert not sibling_completed.is_set()
    finally:
        release_sibling.set()
        await asyncio.sleep(0)


# --- KueueProvider.platform() aggregation ---


def _settings(queues: list[str]) -> Settings:
    return Settings(
        cluster_name="c",
        sources=SourceConfig(platform="kueue"),
        providers=ProviderConfigs(kueue=KueueProviderConfig(cluster_queues=queues)),
    )


async def _read_platform_doc(doc: Mapping[str, object]) -> PlatformMetricsData:
    provider = KueueProvider(_settings(["cq-a"]), api=FakeKueueApi({"cq-a": dict(doc)}))
    return await provider.platform()


@pytest.mark.anyio
async def test_kueue_platform_aggregates_configured_queues_only() -> None:
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

    api = FakeKueueApi({"cq-a": cq_a, "cq-b": cq_b})
    data = await KueueProvider(_settings(["cq-a", "cq-b"]), api=api).platform()
    assert sorted(api.requested) == ["cq-a", "cq-b"]
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
async def test_kueue_platform_subcore_cpu_uses_cores_in_capacity_and_allocated() -> None:
    """100m in usage must not print as 100m while capacity prints whole cores."""
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

    data = await _read_platform_doc(cq)
    assert data.capacity["cpu"] == "10"
    assert data.allocated["cpu"] == "0.1"


@pytest.mark.anyio
async def test_kueue_platform_zero_allocated_when_no_flavors_usage() -> None:
    """No admitted workloads: allocated keys align with capacity, zeros explicit."""
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

    data = await _read_platform_doc(cq)
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
        await _read_platform_doc(doc)
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
        await _read_platform_doc(doc)


@pytest.mark.anyio
async def test_kueue_platform_rejects_invalid_nested_shape_without_payload_leaks() -> None:
    doc = {"spec": {"resourceGroups": "secret-shape"}}

    with pytest.raises(
        ProviderExecutionError,
        match="Kueue platform data contained an invalid object shape",
    ) as exc_info:
        await _read_platform_doc(doc)

    assert "secret-shape" not in str(exc_info.value)


@pytest.mark.anyio
@pytest.mark.parametrize(
    ("error", "expected_message"),
    [
        (
            httpx.ConnectError("raw transport secret at https://kubernetes.default.svc"),
            "Failed querying Kueue objects (upstream request error)",
        ),
        (
            kr8s.ServerError("secret detail", response=httpx.Response(500)),
            "Kubernetes returned HTTP 500 querying Kueue objects",
        ),
        (
            kr8s.NotFoundError("cq-a"),
            "Kubernetes returned HTTP 404 querying Kueue objects",
        ),
    ],
)
async def test_kueue_platform_sanitizes_upstream_errors(
    error: Exception,
    expected_message: str,
) -> None:
    provider = KueueProvider(_settings(["cq-a"]), api=FakeKueueApi({"cq-a": error}))
    with pytest.raises(ProviderExecutionError) as exc_info:
        await provider.platform()

    message = str(exc_info.value)
    assert message == expected_message
    assert "secret" not in message
    assert "kubernetes.default.svc" not in message
