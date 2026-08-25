"""User metrics provider and FastAPI contract tests."""

from __future__ import annotations

import contextlib

import httpx
import pytest
from fastapi.testclient import TestClient

from metrics.cache import FRESHNESS_POLICIES, CacheIdentity, InMemoryCoordinator
from metrics.core.factory import create_app
from metrics.core.runtime import MetricsRuntime
from metrics.core.settings import (
    CacheConfig,
    KubernetesProviderConfig,
    KueueProviderConfig,
    ProviderConfigs,
    Settings,
)
from metrics.errors import ProviderExecutionError
from metrics.providers.kubernetes import KubernetesProvider, scheduler_requests
from metrics.providers.kueue import KueueProvider
from metrics.services.metrics import MetricsService
from metrics.services.models import CachedSnapshot
from tests.fakes import FakeKueueApi

LABELS = {
    "app.kubernetes.io/managed-by": "skaha",
    "app.kubernetes.io/part-of": "canfar",
    "canfar.net/username": "alice",
}


def _pod(
    name: str,
    *,
    username: str = "alice",
    phase: str = "Running",
    labels: dict[str, str] | None = None,
    spec: dict | None = None,
) -> dict:
    return {
        "metadata": {"name": name, "uid": f"uid-{name}", "labels": labels or LABELS | {
            "canfar.net/username": username
        }},
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
            and all(pod["metadata"]["labels"].get(key) == expected for key, expected in labels.items())
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


def _runtime(api: FakePodApi) -> MetricsRuntime:
    settings = _settings()
    platform = KueueProvider(
        settings,
        api=FakeKueueApi(
            {
                "cq": {
                    "spec": {
                        "resourceGroups": [
                            {
                                "flavors": [
                                    {"resources": [{"name": "cpu", "nominalQuota": "1"}]}
                                ]
                            }
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
    service = MetricsService(
        platform=platform.read_platform,
        cache=platform_cache,
        identity=lambda: CacheIdentity("platform", "canfar", "kind-metrics", "kueue"),
        user=users.read_user,
        user_cache=user_cache,
        user_identity=lambda username: CacheIdentity(
            "user", username, "kind-metrics", "kubernetes", "work-a-work-b"
        ),
    )
    return MetricsRuntime(
        settings,
        provider=platform,
        user_provider=users,
        metrics_service=service,
        cache=platform_cache,
        user_cache=user_cache,
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


@pytest.mark.parametrize("value", ["%252F", "%2F", "%252E%252E", "bad%252Cselector"])
def test_user_route_rejects_encoded_or_selector_like_values(value: str) -> None:
    api = FakePodApi({"work-a": [], "work-b": []})
    with TestClient(create_app(settings=_settings(), runtime=_runtime(api))) as client:
        api.requests.clear()
        response = client.get(f"/apis/canfar.net/v1alpha1/metrics/user/{value}")
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
