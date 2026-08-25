"""User metrics provider and FastAPI contract tests."""

from __future__ import annotations

import contextlib
from collections.abc import Awaitable, Callable
from datetime import datetime, timedelta
from decimal import Decimal

import httpx
import pytest
from fastapi.testclient import TestClient

from metrics.cache import FRESHNESS_POLICIES, CacheIdentity, CacheResult, InMemoryCoordinator
from metrics.core.factory import create_app
from metrics.core.runtime import MetricsRuntime
from metrics.core.settings import (
    CacheConfig,
    KubernetesProviderConfig,
    KueueProviderConfig,
    ProviderConfigs,
    Settings,
)
from metrics.errors import ProviderExecutionError, ProviderUnavailableError
from metrics.providers.kubernetes import KubernetesProvider, scheduler_requests
from metrics.providers.kueue import KueueProvider
from metrics.services.metrics import MetricsService
from metrics.services.models import (
    AccountingSnapshot,
    ActiveWorkloadLifetime,
    CachedSnapshot,
    LifetimeIssue,
    ResourceHours,
)
from tests.fakes import FakeKueueApi

LABELS = {
    "app.kubernetes.io/managed-by": "skaha",
    "app.kubernetes.io/part-of": "canfar",
    "canfar.net/username": "alice",
    "canfar.net/community": "astronomy",
}


def _pod(
    name: str,
    *,
    username: str = "alice",
    community: str = "astronomy",
    phase: str = "Running",
    labels: dict[str, str] | None = None,
    spec: dict | None = None,
) -> dict:
    return {
        "metadata": {
            "name": name,
            "uid": f"uid-{name}",
            "labels": labels
            or LABELS
            | {
                "canfar.net/username": username,
                "canfar.net/community": community,
            },
        },
        "status": {"phase": phase},
        "spec": spec
        or {
            "containers": [
                {
                    "resources": {
                        "requests": {
                            "cpu": "200m",
                            "memory": "200Mi",
                            "example.com/fpga": "1",
                        }
                    }
                }
            ]
        },
    }


class FakePodApi:
    """Filter namespaced Pod fixtures like Kubernetes LIST selectors."""

    def __init__(self, pods: dict[str, list[dict] | BaseException]) -> None:
        self.pods = pods
        self.requests: list[tuple[str, dict[str, str]]] = []

    @contextlib.asynccontextmanager
    async def call_api(
        self,
        *,
        method: str,
        version: str,
        namespace: str,
        url: str,
        params: dict[str, str],
    ):
        assert (method, version, url) == ("GET", "v1", "pods")
        self.requests.append((namespace, params))
        value = self.pods[namespace]
        if isinstance(value, BaseException):
            raise value
        labels = dict(part.split("=", 1) for part in params["labelSelector"].split(","))
        phase = params["fieldSelector"].split("=", 1)[1]
        selected = [
            pod
            for pod in value
            if pod["status"]["phase"] == phase
            and all(
                pod["metadata"]["labels"].get(key) == expected for key, expected in labels.items()
            )
        ]
        yield httpx.Response(200, json={"items": selected})


def _settings() -> Settings:
    return Settings(
        cluster_name="kind-metrics",
        cache=CacheConfig(backend="memory"),
        providers=ProviderConfigs(
            kueue=KueueProviderConfig(cluster_queues=["cq"]),
            kubernetes=KubernetesProviderConfig(workload_namespaces=["work-a", "work-b"]),
        ),
    )


def _runtime(
    api: FakePodApi,
    *,
    user_accounting: Callable[[str, datetime], Awaitable[CacheResult]] | None = None,
    community_accounting: Callable[[str, datetime], Awaitable[CacheResult]] | None = None,
) -> MetricsRuntime:
    settings = _settings()
    platform = KueueProvider(
        settings,
        api=FakeKueueApi(
            {
                "cq": {
                    "spec": {
                        "resourceGroups": [
                            {"flavors": [{"resources": [{"name": "cpu", "nominalQuota": "1"}]}]}
                        ]
                    }
                }
            }
        ),
    )
    users = KubernetesProvider(settings, api=api)
    platform_cache = InMemoryCoordinator[CachedSnapshot](
        policy=FRESHNESS_POLICIES["platform"], created=lambda value: value.created
    )
    user_cache = InMemoryCoordinator[CachedSnapshot](
        policy=FRESHNESS_POLICIES["user"], created=lambda value: value.created
    )
    community_cache = InMemoryCoordinator[CachedSnapshot](
        policy=FRESHNESS_POLICIES["community"], created=lambda value: value.created
    )
    service = MetricsService(
        platform=platform.read_platform,
        cache=platform_cache,
        identity=lambda: CacheIdentity("platform", "canfar", "kind-metrics", "kueue"),
        user=users.read_user,
        user_cache=user_cache,
        user_identity=lambda username: CacheIdentity(
            "user", username, "kind-metrics", "kubernetes", "work-a-work-b"
        ),
        user_accounting=user_accounting,
        community=users.read_community,
        community_cache=community_cache,
        community_identity=lambda community: CacheIdentity(
            "community", community, "kind-metrics", "kubernetes", "work-a-work-b"
        ),
        community_accounting=community_accounting,
    )
    return MetricsRuntime(
        settings,
        provider=platform,
        user_provider=users,
        metrics_service=service,
        cache=platform_cache,
        user_cache=user_cache,
        community_cache=community_cache,
    )


def test_scheduler_effective_requests_include_sidecars_init_and_overhead() -> None:
    requests = scheduler_requests(
        _pod(
            "shapes",
            spec={
                "containers": [
                    {"resources": {"requests": {"cpu": "100m", "memory": "64Mi"}}},
                    {"resources": {"requests": {"cpu": "50m", "memory": "32Mi"}}},
                ],
                "initContainers": [
                    {
                        "restartPolicy": "Always",
                        "resources": {"requests": {"cpu": "25m", "memory": "16Mi"}},
                    },
                    {"resources": {"requests": {"cpu": "300m", "memory": "128Mi"}}},
                ],
                "overhead": {"cpu": "10m", "memory": "8Mi"},
            },
        )
    )
    assert requests["cpu"] == pytest.approx(0.335)
    assert requests["memory"] == pytest.approx(152 / 1024)


@pytest.mark.anyio
async def test_workload_observation_records_read_cutoff() -> None:
    provider = KubernetesProvider(
        _settings(),
        api=FakePodApi({"work-a": [_pod("selected")], "work-b": []}),
    )
    before = datetime.now().astimezone()
    observation = await provider.read_user("alice")
    after = datetime.now().astimezone()

    assert before - timedelta(milliseconds=1) <= observation.observed_at <= after
    assert observation.observed_at.microsecond % 1000 == 0


def test_user_route_selects_exact_running_skaha_pods_and_returns_no_inventory() -> None:
    api = FakePodApi(
        {
            "work-a": [
                _pod("selected"),
                _pod("pending", phase="Pending"),
                _pod("other-user", username="alice2"),
                _pod("other-app", labels=LABELS | {"app.kubernetes.io/managed-by": "other"}),
            ],
            "work-b": [],
        }
    )
    with TestClient(create_app(settings=_settings(), runtime=_runtime(api))) as client:
        api.requests.clear()
        response = client.get("/apis/canfar.net/v1alpha1/metrics/user/alice")
        second = client.get("/apis/canfar.net/v1alpha1/metrics/user/alice")
        empty = client.get("/apis/canfar.net/v1alpha1/metrics/user/nobody")

    assert response.status_code == 200
    payload = response.json()
    assert payload["metadata"] == {"name": "user-alice"}
    assert payload["spec"] == {"user": "alice"}
    assert payload["status"]["runningPods"] == 1
    assert payload["status"]["resources"] == [
        {"name": "cpu", "requests": "0.2"},
        {"name": "example.com/fpga", "requests": "1"},
        {"name": "memory", "requests": "0.195312Gi"},
    ]
    assert "selected" not in response.text
    assert "uid-selected" not in response.text
    assert second.json()["status"]["conditions"][1]["reason"] == "FreshHit"
    assert empty.json()["status"]["runningPods"] == 0
    assert empty.json()["status"]["resources"] == []
    assert all(
        params
        == {
            "labelSelector": (
                "app.kubernetes.io/managed-by=skaha,"
                "app.kubernetes.io/part-of=canfar,"
                "canfar.net/username=alice"
            ),
            "fieldSelector": "status.phase=Running",
        }
        for _, params in api.requests[:2]
    )


def test_user_route_adds_aggregate_lifetime_hours_and_efficiency() -> None:
    async def accounting(_username: str, observed_at: datetime) -> CacheResult:
        return CacheResult(
            AccountingSnapshot(
                lifetime=ActiveWorkloadLifetime(
                    resources={
                        "cpu": ResourceHours("core-hours", Decimal("10.9"), Decimal("35")),
                        "memory": ResourceHours("GiB-hours", Decimal("118.4"), Decimal("470")),
                        "nvidia.com/gpu": ResourceHours("GPU-hours", Decimal("1"), Decimal("0")),
                    },
                    incomplete={},
                    pod_uids=frozenset({"uid-selected"}),
                    coverage={
                        resource: frozenset({"uid-selected"})
                        for resource in ("cpu", "memory", "nvidia.com/gpu")
                    },
                ),
                created=observed_at,
            ),
            cached=False,
            stale=False,
        )

    api = FakePodApi({"work-a": [_pod("selected")], "work-b": []})
    with TestClient(
        create_app(
            settings=_settings(),
            runtime=_runtime(api, user_accounting=accounting),
        )
    ) as client:
        response = client.get("/apis/canfar.net/v1alpha1/metrics/user/alice")

    assert response.status_code == 200
    status = response.json()["status"]
    assert status["accountingPeriod"] == "ActiveWorkloadLifetime"
    assert status["resources"] == [
        {
            "name": "cpu",
            "requests": "0.2",
            "usageHours": "10.9",
            "requestedHours": "35",
            "efficiency": "0.311429",
        },
        {"name": "example.com/fpga", "requests": "1"},
        {
            "name": "memory",
            "requests": "0.195312Gi",
            "usageHours": "118.4",
            "requestedHours": "470",
            "efficiency": "0.251915",
        },
        {
            "name": "nvidia.com/gpu",
            "usageHours": "1",
            "requestedHours": "0",
        },
    ]
    assert status["conditions"][0]["reason"] == "Available"


def test_incomplete_accounting_omits_only_affected_resource_fields() -> None:
    async def accounting(_username: str, observed_at: datetime) -> CacheResult:
        return CacheResult(
            AccountingSnapshot(
                lifetime=ActiveWorkloadLifetime(
                    resources={"cpu": ResourceHours("core-hours", Decimal("2"), Decimal("1"))},
                    incomplete={
                        "memory": frozenset({LifetimeIssue.COUNTER_RESET}),
                    },
                    pod_uids=frozenset({"uid-selected"}),
                    coverage={"cpu": frozenset({"uid-selected"})},
                ),
                created=observed_at,
            ),
            cached=False,
            stale=False,
        )

    api = FakePodApi({"work-a": [_pod("selected")], "work-b": []})
    with TestClient(
        create_app(
            settings=_settings(),
            runtime=_runtime(api, user_accounting=accounting),
        )
    ) as client:
        response = client.get("/apis/canfar.net/v1alpha1/metrics/user/alice")

    resources = {item["name"]: item for item in response.json()["status"]["resources"]}
    assert resources["cpu"]["efficiency"] == "2"
    assert resources["memory"] == {"name": "memory", "requests": "0.195312Gi"}
    assert response.json()["status"]["conditions"][0]["reason"] == "AccountingIncomplete"


def test_accounting_for_a_different_running_pod_set_is_omitted() -> None:
    async def misaligned(_username: str, observed_at: datetime) -> CacheResult:
        return CacheResult(
            AccountingSnapshot(
                lifetime=ActiveWorkloadLifetime(
                    resources={"cpu": ResourceHours("core-hours", Decimal("2"), Decimal("1"))},
                    incomplete={},
                    pod_uids=frozenset({"uid-replaced"}),
                    coverage={"cpu": frozenset({"uid-replaced"})},
                ),
                created=observed_at,
            ),
            cached=False,
            stale=False,
        )

    api = FakePodApi({"work-a": [_pod("selected")], "work-b": []})
    with TestClient(
        create_app(
            settings=_settings(),
            runtime=_runtime(api, user_accounting=misaligned),
        )
    ) as client:
        response = client.get("/apis/canfar.net/v1alpha1/metrics/user/alice")

    status = response.json()["status"]
    assert all("usageHours" not in resource for resource in status["resources"])
    assert status["conditions"][0]["reason"] == "AccountingIncomplete"


def test_accounting_for_a_different_observation_cutoff_is_omitted() -> None:
    async def misaligned(_username: str, observed_at: datetime) -> CacheResult:
        return CacheResult(
            AccountingSnapshot(
                lifetime=ActiveWorkloadLifetime(
                    resources={"cpu": ResourceHours("core-hours", Decimal("2"), Decimal("1"))},
                    incomplete={},
                    pod_uids=frozenset({"uid-selected"}),
                    coverage={"cpu": frozenset({"uid-selected"})},
                ),
                created=observed_at - timedelta(seconds=1),
            ),
            cached=False,
            stale=False,
        )

    api = FakePodApi({"work-a": [_pod("selected")], "work-b": []})
    with TestClient(
        create_app(
            settings=_settings(),
            runtime=_runtime(api, user_accounting=misaligned),
        )
    ) as client:
        response = client.get("/apis/canfar.net/v1alpha1/metrics/user/alice")

    status = response.json()["status"]
    assert all("usageHours" not in resource for resource in status["resources"])
    assert status["conditions"][0]["reason"] == "AccountingIncomplete"


def test_unavailable_accounting_preserves_current_requests_as_partial_data() -> None:
    async def unavailable(_username: str, _observed_at: datetime) -> CacheResult:
        raise ProviderUnavailableError("prometheus unavailable")

    api = FakePodApi({"work-a": [_pod("selected")], "work-b": []})
    with TestClient(
        create_app(
            settings=_settings(),
            runtime=_runtime(api, user_accounting=unavailable),
        )
    ) as client:
        response = client.get("/apis/canfar.net/v1alpha1/metrics/user/alice")

    status = response.json()["status"]
    assert response.status_code == 200
    assert status["accountingPeriod"] == "ActiveWorkloadLifetime"
    assert all("usageHours" not in resource for resource in status["resources"])
    assert status["conditions"][0]["type"] == "Ready"
    assert status["conditions"][0]["status"] == "False"
    assert status["conditions"][0]["reason"] == "PartialData"


def test_stale_accounting_makes_the_combined_user_report_stale() -> None:
    async def accounting(_username: str, observed_at: datetime) -> CacheResult:
        return CacheResult(
            AccountingSnapshot(
                lifetime=ActiveWorkloadLifetime(
                    resources={},
                    incomplete={},
                    pod_uids=frozenset({"uid-selected"}),
                ),
                created=observed_at,
            ),
            cached=True,
            stale=True,
        )

    api = FakePodApi({"work-a": [_pod("selected")], "work-b": []})
    with TestClient(
        create_app(
            settings=_settings(),
            runtime=_runtime(api, user_accounting=accounting),
        )
    ) as client:
        response = client.get("/apis/canfar.net/v1alpha1/metrics/user/alice")

    conditions = response.json()["status"]["conditions"]
    assert conditions[0]["reason"] == "StaleData"
    assert conditions[1]["reason"] == "StaleHit"


def test_community_route_aggregates_mixed_users_in_one_direct_scan() -> None:
    api = FakePodApi(
        {
            "work-a": [
                _pod("alice-astronomy", username="alice"),
                _pod("carol-astronomy", username="carol"),
                _pod("bob-physics", username="bob", community="physics"),
                _pod("pending", username="dana", phase="Pending"),
                _pod(
                    "unlabelled",
                    labels={
                        "app.kubernetes.io/managed-by": "skaha",
                        "app.kubernetes.io/part-of": "canfar",
                        "canfar.net/username": "erin",
                    },
                ),
            ],
            "work-b": [],
        }
    )
    with TestClient(create_app(settings=_settings(), runtime=_runtime(api))) as client:
        api.requests.clear()
        response = client.get("/apis/canfar.net/v1alpha1/metrics/community/astronomy")
        cached = client.get("/apis/canfar.net/v1alpha1/metrics/community/astronomy")
        isolated = client.get("/apis/canfar.net/v1alpha1/metrics/community/physics")
        empty = client.get("/apis/canfar.net/v1alpha1/metrics/community/nobody")

    assert response.status_code == 200
    assert response.json()["metadata"] == {"name": "community-astronomy"}
    assert response.json()["spec"] == {"community": "astronomy"}
    assert response.json()["status"]["runningPods"] == 2
    assert response.json()["status"]["resources"] == [
        {"name": "cpu", "requests": "0.4"},
        {"name": "example.com/fpga", "requests": "2"},
        {"name": "memory", "requests": "0.390625Gi"},
    ]
    assert all(name not in response.text for name in ("alice", "carol", "uid-"))
    assert cached.json()["status"]["conditions"][1]["reason"] == "FreshHit"
    assert isolated.json()["status"]["runningPods"] == 1
    assert empty.json()["status"]["runningPods"] == 0
    assert len(api.requests) == 6
    assert all("canfar.net/username" not in params["labelSelector"] for _, params in api.requests)
    assert all(params["fieldSelector"] == "status.phase=Running" for _, params in api.requests)


def test_community_lifetime_sums_direct_inputs_before_efficiency_without_members() -> None:
    calls: list[str] = []

    async def accounting(community: str, observed_at: datetime) -> CacheResult:
        calls.append(community)
        return CacheResult(
            AccountingSnapshot(
                lifetime=ActiveWorkloadLifetime(
                    resources={
                        # Controlled inputs: (1 + 9) / (2 + 10), not mean(0.5, 0.9).
                        "cpu": ResourceHours("core-hours", Decimal("10"), Decimal("12")),
                        "memory": ResourceHours("GiB-hours", Decimal("30"), Decimal("60")),
                    },
                    incomplete={},
                    pod_uids=frozenset({"uid-alice", "uid-carol"}),
                    coverage={
                        "cpu": frozenset({"uid-alice", "uid-carol"}),
                        "memory": frozenset({"uid-alice", "uid-carol"}),
                    },
                ),
                created=observed_at,
            ),
            cached=False,
            stale=False,
        )

    api = FakePodApi(
        {
            "work-a": [
                _pod("alice", username="alice"),
                _pod("carol", username="carol"),
                _pod("other", username="bob", community="physics"),
            ],
            "work-b": [],
        }
    )
    with TestClient(
        create_app(
            settings=_settings(),
            runtime=_runtime(api, community_accounting=accounting),
        )
    ) as client:
        api.requests.clear()
        response = client.get("/apis/canfar.net/v1alpha1/metrics/community/astronomy")

    assert response.status_code == 200
    assert calls == ["astronomy"]
    assert len(api.requests) == 2
    status = response.json()["status"]
    assert status["accountingPeriod"] == "ActiveWorkloadLifetime"
    resources = {item["name"]: item for item in status["resources"]}
    assert resources["cpu"] == {
        "name": "cpu",
        "requests": "0.4",
        "usageHours": "10",
        "requestedHours": "12",
        "efficiency": "0.833333",
    }
    assert resources["memory"]["efficiency"] == "0.5"
    assert all(value not in response.text for value in ("alice", "carol", "uid-"))


def test_incomplete_community_accounting_preserves_requests_and_readiness() -> None:
    async def accounting(_community: str, observed_at: datetime) -> CacheResult:
        return CacheResult(
            AccountingSnapshot(
                lifetime=ActiveWorkloadLifetime(
                    resources={"cpu": ResourceHours("core-hours", Decimal("2"), Decimal("4"))},
                    incomplete={"memory": frozenset({LifetimeIssue.SAMPLING_GAP})},
                    pod_uids=frozenset({"uid-selected"}),
                    coverage={"cpu": frozenset({"uid-selected"})},
                ),
                created=observed_at,
            ),
            cached=False,
            stale=False,
        )

    api = FakePodApi({"work-a": [_pod("selected")], "work-b": []})
    with TestClient(
        create_app(
            settings=_settings(),
            runtime=_runtime(api, community_accounting=accounting),
        )
    ) as client:
        response = client.get("/apis/canfar.net/v1alpha1/metrics/community/astronomy")

    status = response.json()["status"]
    resources = {item["name"]: item for item in status["resources"]}
    assert resources["cpu"]["efficiency"] == "0.5"
    assert resources["memory"] == {"name": "memory", "requests": "0.195312Gi"}
    assert status["conditions"][0]["reason"] == "AccountingIncomplete"


@pytest.mark.parametrize("value", ["%252F", "%2F", "%252E%252E", "bad%252Cselector"])
def test_user_route_rejects_encoded_or_selector_like_values(value: str) -> None:
    api = FakePodApi({"work-a": [], "work-b": []})
    with TestClient(create_app(settings=_settings(), runtime=_runtime(api))) as client:
        api.requests.clear()
        response = client.get(f"/apis/canfar.net/v1alpha1/metrics/user/{value}")
    assert response.status_code == 400
    assert not api.requests


@pytest.mark.parametrize("value", ["%252F", "%2F", "%252E%252E", "bad%252Cselector"])
def test_community_route_rejects_encoded_or_selector_like_values(value: str) -> None:
    api = FakePodApi({"work-a": [], "work-b": []})
    with TestClient(create_app(settings=_settings(), runtime=_runtime(api))) as client:
        api.requests.clear()
        response = client.get(f"/apis/canfar.net/v1alpha1/metrics/community/{value}")
    assert response.status_code == 400
    assert not api.requests


@pytest.mark.anyio
async def test_namespace_failure_never_returns_partial_totals() -> None:
    api = FakePodApi(
        {
            "work-a": [_pod("selected")],
            "work-b": httpx.ConnectError("unavailable"),
        }
    )
    provider = KubernetesProvider(_settings(), api=api)
    with pytest.raises(ProviderExecutionError, match="Failed querying Running Pods"):
        await provider.read_user("alice")
