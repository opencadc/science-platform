"""Kueue provider: quantities, kr8s fetch semantics, startup checks, fingerprint."""

from __future__ import annotations


import httpx
import kr8s
import pytest

from metrics.core.settings import KueueProviderConfig, ProviderConfigs, Settings
from metrics.errors import ProviderExecutionError, RuntimeStartupError
from metrics.providers.kueue import (
    KueueProvider,
    fetch_cluster_queue_docs,
    format_resource_amount,
    merge_resource_totals,
    parse_resource_amount,
)
from tests.fakes import FakeKueueApi

pytestmark = pytest.mark.anyio


def _settings(queues: list[str]) -> Settings:
    return Settings(
        cluster_name="c",
        providers=ProviderConfigs(kueue=KueueProviderConfig(cluster_queues=queues)),
    )


# --- Quantities (quantiphy-backed helpers; units per ADR-0002/0024) ---


@pytest.mark.parametrize(
    ("resource", "raw", "expected"),
    [
        ("cpu", "250m", 0.25),
        ("cpu", "0", 0.0),
        ("cpu", "1.5", 1.5),
        ("memory", "512Mi", 0.5),
        ("memory", "1.5Gi", 1.5),
        ("memory", "1Ti", 1024.0),
        ("memory", "1G", 0.9313225746154785),
        ("ephemeral-storage", "5Ei", 5 * 2**30),
        ("nvidia.com/gpu", "1.5", 1.5),
        ("example.com/bandwidth", "1Mi", 1048576.0),
    ],
)
def test_parse_resource_amounts_in_public_units(resource: str, raw: str, expected: float) -> None:
    assert parse_resource_amount(resource, raw) == pytest.approx(expected)


@pytest.mark.parametrize(
    "raw",
    [None, "", "-1", "NaN", "9223372036854775808", " 1Gi "],
)
def test_invalid_quantities_are_rejected(raw: object) -> None:
    with pytest.raises(ProviderExecutionError):
        parse_resource_amount("nvidia.com/gpu", raw)


def test_formatting_units_precision_and_overflow() -> None:
    assert format_resource_amount("cpu", 38.0) == "38"
    assert format_resource_amount("cpu", 0.0005) == "0.0005"
    assert format_resource_amount("cpu", 1e3) == "1000"
    assert format_resource_amount("memory", 88.0) == "88Gi"

    # Float noise must not leak: 0.1 * 3 prints as 0.3.
    totals: dict[str, float] = {}
    for _ in range(3):
        merge_resource_totals(totals, "cpu", parse_resource_amount("cpu", "0.1"))
    assert format_resource_amount("cpu", totals["cpu"]) == "0.3"

    # Overflow is checked in base units (storage) and at 2**63 (everything).
    with pytest.raises(ProviderExecutionError):
        format_resource_amount("cpu", float(2**63))
    with pytest.raises(ProviderExecutionError):
        merge_resource_totals({"memory": 5 * 2**33}, "memory", 5 * 2**33)


# --- fetch_cluster_queue_docs (kr8s access) ---


async def test_fetch_orders_results_and_maps_missing_to_http_404() -> None:
    api = FakeKueueApi(
        {"cq-a": {"metadata": {"name": "cq-a"}}, "cq-b": {"metadata": {"name": "cq-b"}}}
    )
    assert await fetch_cluster_queue_docs(api, "kueue.x-k8s.io/v1beta2", []) == []
    docs = await fetch_cluster_queue_docs(api, "kueue.x-k8s.io/v1beta2", ["cq-b", "cq-a"])
    assert [d["metadata"]["name"] for d in docs] == ["cq-b", "cq-a"]
    with pytest.raises(kr8s.ServerError) as exc_info:
        await fetch_cluster_queue_docs(api, "kueue.x-k8s.io/v1beta2", ["cq-a", "cq-gone"])
    assert exc_info.value.response is not None
    assert exc_info.value.response.status_code == 404


# --- platform() rejects corrupt upstream data without leaking payloads ---


@pytest.mark.parametrize(
    ("doc", "match"),
    [
        (
            {
                "spec": {
                    "resourceGroups": [
                        {
                            "flavors": [
                                {"resources": [{"name": "cpu", "nominalQuota": "bad-secret-value"}]}
                            ]
                        }
                    ]
                }
            },
            "invalid resource quantity",
        ),
        (
            {
                "spec": {
                    "resourceGroups": [
                        {"flavors": [{"resources": [{"name": "cpu", "nominalQuota": "1"}]}]}
                    ]
                },
                "status": {
                    "flavorsUsage": [{"resources": [{"name": "cpu", "total": "bad-secret-value"}]}]
                },
            },
            "invalid resource quantity",
        ),
        ({"spec": {"resourceGroups": "secret-shape"}}, "invalid object shape"),
    ],
)
async def test_platform_rejects_corrupt_documents(doc: dict, match: str) -> None:
    provider = KueueProvider(_settings(["cq-a"]), api=FakeKueueApi({"cq-a": doc}))
    with pytest.raises(ProviderExecutionError, match=match) as exc_info:
        await provider.read_platform()

    assert "secret" not in str(exc_info.value)


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
            kr8s.ServerError("cq-a not found", response=httpx.Response(404)),
            "Kubernetes returned HTTP 404 querying Kueue objects",
        ),
    ],
)
async def test_platform_sanitizes_upstream_errors(error: Exception, expected_message: str) -> None:
    provider = KueueProvider(_settings(["cq-a"]), api=FakeKueueApi({"cq-a": error}))
    with pytest.raises(ProviderExecutionError) as exc_info:
        await provider.read_platform()

    assert str(exc_info.value) == expected_message


# --- startup validation (fail-fast, sanitized) ---


@pytest.mark.parametrize(
    ("queues", "docs", "match"),
    [
        ([], {}, "CLUSTER_QUEUES"),
        (["cq-a", "cq-missing"], {"cq-a": {"metadata": {}}}, "'cq-missing'.*not found"),
        (
            ["cq-forbidden"],
            {"cq-forbidden": kr8s.ServerError("Forbidden", response=httpx.Response(403))},
            "'cq-forbidden'.*forbidden",
        ),
        (
            ["cq-a"],
            {"cq-a": httpx.ConnectError("secret at https://kubernetes.default.svc")},
            "^Cannot reach Kubernetes API for Kueue startup checks$",
        ),
    ],
)
async def test_startup_fails_fast_with_sanitized_messages(
    queues: list[str],
    docs: dict[str, object],
    match: str,
) -> None:
    provider = KueueProvider(_settings(queues), api=FakeKueueApi(docs))
    with pytest.raises(RuntimeStartupError, match=match) as exc_info:
        await provider.startup()
    assert "kubernetes.default.svc" not in str(exc_info.value)


async def test_startup_api_construction_failure_is_sanitized(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    async def broken_api(*_args: object, **_kwargs: object) -> object:
        raise RuntimeError("secret kubeconfig path /home/user/.kube/config")

    monkeypatch.setattr("metrics.providers.kueue.create_kube_api", broken_api)
    provider = KueueProvider(_settings(["cq-a"]))
    with pytest.raises(RuntimeStartupError) as exc_info:
        await provider.startup()
    assert str(exc_info.value) == "Cannot configure Kubernetes API access for Kueue startup checks"


# --- identity ---


def test_fingerprint_covers_identity_and_excludes_transport() -> None:
    def fingerprint(
        *, api_version: str = "kueue.x-k8s.io/v1beta2", queues=("cq-a", "cq-b"), timeout=10.0
    ):
        settings = Settings(
            providers=ProviderConfigs(
                kueue=KueueProviderConfig(
                    cluster_queues=list(queues),
                    kueue_api_version=api_version,
                    kube_request_timeout_seconds=timeout,
                )
            )
        )
        return KueueProvider(settings, api=FakeKueueApi()).cache_fingerprint()

    baseline = fingerprint()
    assert fingerprint(api_version="kueue.x-k8s.io/v1beta1") != baseline
    assert fingerprint(queues=("cq-a", "cq-c")) != baseline
    assert fingerprint(queues=("cq-b", "cq-a")) == baseline
    assert fingerprint(timeout=99.0) == baseline


async def test_shutdown_is_idempotent_and_releases_api() -> None:
    provider = KueueProvider(_settings(["cq-a"]), api=FakeKueueApi())
    await provider.shutdown()
    await provider.shutdown()
    assert provider._api is None  # noqa: SLF001 - lifecycle contract
