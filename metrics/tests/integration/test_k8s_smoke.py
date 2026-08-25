from __future__ import annotations

import hashlib
import hmac
import json
import os
import subprocess
import time
from datetime import UTC, datetime, timedelta

import httpx
import pytest
from redis import Redis

from metrics.providers.kueue import parse_resource_amount


pytestmark = [
    pytest.mark.integration,
    pytest.mark.skipif(not os.getenv("METRICS_BASE_URL"), reason="METRICS_BASE_URL not configured"),
]
base_url = os.getenv("METRICS_BASE_URL", "")
routes = {
    "platform": "/apis/canfar.net/v1alpha1/metrics/platform/canfar",
    "user": "/apis/canfar.net/v1alpha1/metrics/user/bob",
    "community": "/apis/canfar.net/v1alpha1/metrics/community/astronomy",
}
accounting = pytest.mark.skipif(
    os.getenv("METRICS_TEST_PROFILE") != "accounting",
    reason="accounting profile not configured",
)


def _wait_for_accounting_series() -> None:
    forward = subprocess.Popen(
        [
            "kubectl",
            "--context",
            "kind-metrics",
            "-n",
            "metrics",
            "port-forward",
            "service/metrics-accounting-prometheus",
            "19090:9090",
        ],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    deadline = time.monotonic() + 30
    try:
        while forward.poll() is None and time.monotonic() < deadline:
            try:
                response = httpx.post(
                    "http://127.0.0.1:19090/api/v1/query",
                    data={"query": "count(canfar_active_workload_accounting_complete)"},
                    timeout=2,
                )
                result = response.json()["data"]["result"]
                if result and float(result[0]["value"][1]) > 0:
                    return
            except (httpx.HTTPError, KeyError, ValueError):
                pass
            time.sleep(0.25)
        raise AssertionError("accounting series did not become ready within 30s")
    finally:
        forward.terminate()
        forward.wait(timeout=5)


@pytest.fixture(scope="module", autouse=True)
def accounting_series_ready() -> None:
    if os.getenv("METRICS_TEST_PROFILE") == "accounting":
        _wait_for_accounting_series()


def test_health_endpoint() -> None:
    response = httpx.get(f"{base_url}/healthz", timeout=10)
    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


@pytest.mark.parametrize("route", routes.values(), ids=routes.keys())
def test_route_cold_fill_fresh_hit_and_conditional_hit(route: str) -> None:
    cold = httpx.get(f"{base_url}{route}", timeout=30)
    assert cold.status_code == 200
    assert "fwd=uri-miss" in cold.headers["cache-status"]

    fresh = httpx.get(f"{base_url}{route}", timeout=30)
    assert fresh.status_code == 200
    assert "hit" in fresh.headers["cache-status"]
    assert fresh.headers["last-modified"] == cold.headers["last-modified"]

    conditional = httpx.get(
        f"{base_url}{route}",
        headers={"If-Modified-Since": fresh.headers["last-modified"]},
        timeout=30,
    )
    assert conditional.status_code == 304
    assert not conditional.content
    assert conditional.headers["last-modified"] == fresh.headers["last-modified"]


@pytest.mark.parametrize(
    ("route", "spec"),
    [
        ("/apis/canfar.net/v1alpha1/metrics/user/nobody", {"user": "nobody"}),
        (
            "/apis/canfar.net/v1alpha1/metrics/community/nobody",
            {"community": "nobody"},
        ),
    ],
)
def test_unknown_subject_has_successful_empty_semantics(route: str, spec: dict[str, str]) -> None:
    response = httpx.get(f"{base_url}{route}", timeout=30)
    assert response.status_code == 200
    assert response.json()["spec"] == spec
    assert response.json()["status"]["runningPods"] == 0
    assert response.json()["status"]["resources"] == []


@pytest.mark.parametrize(
    ("route", "status"),
    [
        ("/apis/canfar.net/v1alpha1/metrics/user/not%2Fa%2Flabel", 400),
        ("/apis/canfar.net/v1alpha1/metrics/community/%25invalid", 400),
        ("/api/v1/metrics/platform", 404),
    ],
)
def test_stable_kubernetes_error_and_legacy_absence(route: str, status: int) -> None:
    response = httpx.get(f"{base_url}{route}", timeout=30)
    assert response.status_code == status
    assert response.headers["cache-control"] == "no-store"
    assert response.json() == {
        "apiVersion": "v1",
        "kind": "Status",
        "status": "Failure",
        "reason": "BadRequest" if status == 400 else "NotFound",
        "message": (
            "The request is malformed."
            if status == 400
            else "The requested resource was not found."
        ),
        "code": status,
    }


def test_platform_endpoint_shape() -> None:
    response = httpx.get(f"{base_url}/apis/canfar.net/v1alpha1/metrics/platform/canfar", timeout=10)
    assert response.status_code == 200
    payload = response.json()
    assert payload["apiVersion"] == "canfar.net/v1alpha1"
    assert payload["kind"] == "Metrics"
    assert payload["spec"] == {"platform": "canfar"}
    resources = {resource["name"]: resource for resource in payload["status"]["resources"]}
    assert {"cpu", "memory"} <= resources.keys()
    assert [condition["type"] for condition in payload["status"]["conditions"]] == [
        "Ready",
        "Cached",
    ]
    assert response.headers["cache-control"] == "no-store"
    assert {"last-modified", "age", "cache-status"} <= response.headers.keys()


def test_platform_endpoint_allocated_includes_kueue_smoke_workload() -> None:
    """Allocated map includes the borrowed Kueue smoke Workload total."""
    response = httpx.get(
        f"{base_url}/apis/canfar.net/v1alpha1/metrics/platform/canfar", timeout=30.0
    )
    assert response.status_code == 200
    allocated = {
        resource["name"]: resource["allocated"]
        for resource in response.json()["status"]["resources"]
    }
    cpu_cores = parse_resource_amount("cpu", allocated.get("cpu", "0"))
    mem_gib = parse_resource_amount("memory", allocated.get("memory", "0Gi"))
    # scripts/test-setup.yaml: cq-electron has 100m/100Mi nominal quota, while
    # integration-idle requests 200m/200Mi. Admission proves borrowing, and
    # ClusterQueue status.flavorsUsage total must include that borrowed usage.
    assert cpu_cores >= 0.19, f"expected >=200m CPU in allocated, got {allocated!r}"
    assert mem_gib >= 0.19, (
        f"expected >=200Mi memory from smoke workload in allocated, got {allocated!r}"
    )


def test_user_endpoint_matches_running_resource_shape_fixture() -> None:
    """Bob includes resource-shapes but excludes the independent Pending control."""
    response = httpx.get(
        f"{base_url}/apis/canfar.net/v1alpha1/metrics/user/bob",
        timeout=30.0,
    )
    assert response.status_code == 200
    payload = response.json()
    assert payload["spec"] == {"user": "bob"}
    assert payload["status"]["runningPods"] == 1
    assert [
        {"name": resource["name"], "requests": resource["requests"]}
        for resource in payload["status"]["resources"]
    ] == [{"name": "cpu", "requests": "0.21"}, {"name": "memory", "requests": "0.101562Gi"}]
    assert "pending-demand" not in response.text
    assert "resource-shapes" not in response.text


def test_community_endpoint_reconciles_mixed_users_without_member_inventory() -> None:
    """Astronomy combines Alice and Carol while exposing aggregate values only."""
    response = httpx.get(
        f"{base_url}/apis/canfar.net/v1alpha1/metrics/community/astronomy",
        timeout=30.0,
    )
    assert response.status_code == 200
    payload = response.json()
    assert payload["spec"] == {"community": "astronomy"}
    assert payload["status"]["runningPods"] == 2
    assert [
        {"name": resource["name"], "requests": resource["requests"]}
        for resource in payload["status"]["resources"]
    ] == [{"name": "cpu", "requests": "0.5"}, {"name": "memory", "requests": "0.320312Gi"}]
    assert all(member not in response.text for member in ("alice", "carol"))


@accounting
@pytest.mark.parametrize(
    ("route", "running_pods", "expected"),
    [
        (
            routes["user"],
            1,
            {
                "cpu": ("0.1", "0.2", "0.5"),
                "memory": ("0.05", "0.1", "0.5"),
            },
        ),
        (
            routes["community"],
            2,
            {
                "cpu": ("0.2", "0.4", "0.5"),
                "memory": ("0.1", "0.2", "0.5"),
            },
        ),
    ],
    ids=("user", "community"),
)
def test_accounting_profile_reconciles_complete_lifetime_values(
    route: str,
    running_pods: int,
    expected: dict[str, tuple[str, str, str]],
) -> None:
    response = httpx.get(f"{base_url}{route}", timeout=30)
    assert response.status_code == 200
    payload = response.json()
    assert payload["status"]["accountingPeriod"] == "ActiveWorkloadLifetime"
    assert payload["status"]["runningPods"] == running_pods
    resources = {resource["name"]: resource for resource in payload["status"]["resources"]}
    for name, (usage, requested, efficiency) in expected.items():
        assert resources[name]["usageHours"] == usage
        assert resources[name]["requestedHours"] == requested
        assert resources[name]["efficiency"] == efficiency
    assert payload["status"]["conditions"][0]["reason"] == "Available"
    if route == routes["community"]:
        assert all(value not in response.text for value in ("alice", "carol", "pod_uid"))


@accounting
def test_accounting_restart_recovers_and_provider_outage_returns_partial_current_data() -> None:
    redis_url = os.environ["METRICS_TEST_REDIS_URL"]
    redis = Redis.from_url(redis_url, decode_responses=True)
    try:
        for deployment in ("metrics-accounting-producer", "metrics-accounting-prometheus"):
            subprocess.run(
                [
                    "kubectl",
                    "--context",
                    "kind-metrics",
                    "-n",
                    "metrics",
                    "rollout",
                    "restart",
                    f"deployment/{deployment}",
                ],
                check=True,
            )
            subprocess.run(
                [
                    "kubectl",
                    "--context",
                    "kind-metrics",
                    "-n",
                    "metrics",
                    "rollout",
                    "status",
                    f"deployment/{deployment}",
                    "--timeout=120s",
                ],
                check=True,
            )
        _wait_for_accounting_series()
        redis.flushdb()
        recovered = httpx.get(f"{base_url}{routes['user']}", timeout=30)
        assert recovered.status_code == 200
        assert recovered.json()["status"]["accountingPeriod"] == "ActiveWorkloadLifetime"

        subprocess.run(
            [
                "kubectl",
                "--context",
                "kind-metrics",
                "-n",
                "metrics",
                "scale",
                "deployment/metrics-accounting-prometheus",
                "--replicas=0",
            ],
            check=True,
        )
        subprocess.run(
            [
                "kubectl",
                "--context",
                "kind-metrics",
                "-n",
                "metrics",
                "rollout",
                "status",
                "deployment/metrics-accounting-prometheus",
                "--timeout=60s",
            ],
            check=True,
        )
        redis.flushdb()
        partial = httpx.get(
            f"{base_url}/apis/canfar.net/v1alpha1/metrics/user/carol",
            timeout=30,
        )
        assert partial.status_code == 200
        assert partial.json()["status"]["conditions"][0]["reason"] == "PartialData"
        assert partial.json()["status"]["runningPods"] == 1
        assert "usageHours" not in partial.text
    finally:
        subprocess.run(
            [
                "kubectl",
                "--context",
                "kind-metrics",
                "-n",
                "metrics",
                "scale",
                "deployment/metrics-accounting-prometheus",
                "--replicas=1",
            ],
            check=True,
        )
        subprocess.run(
            [
                "kubectl",
                "--context",
                "kind-metrics",
                "-n",
                "metrics",
                "rollout",
                "status",
                "deployment/metrics-accounting-prometheus",
                "--timeout=120s",
            ],
            check=True,
        )
        redis.close()


def test_built_image_serves_stale_then_fails_closed_without_a_snapshot() -> None:
    redis_url = os.getenv("METRICS_TEST_REDIS_URL")
    if not redis_url:
        pytest.skip("METRICS_TEST_REDIS_URL not configured")
    redis = Redis.from_url(redis_url, decode_responses=True)
    route = routes["platform"]
    binding = "metrics-api-metrics-kueue-read"

    # Age the signed Redis snapshot without waiting five production minutes.
    assert httpx.get(f"{base_url}{route}", timeout=30).status_code == 200
    keys = list(redis.scan_iter("metrics:5:1:0:platform:*:snapshot:*"))
    assert len(keys) == 1
    envelope = json.loads(redis.get(keys[0]))
    created = (datetime.now(UTC) - timedelta(minutes=6)).isoformat().replace("+00:00", "Z")
    envelope["created"] = created
    envelope["value"]["created"] = created
    signed = json.dumps(
        {key: value for key, value in envelope.items() if key != "integrity"},
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    ).encode()
    envelope["integrity"] = hmac.new(
        b"kind-metrics-cache-key-32-bytes!!", signed, hashlib.sha256
    ).hexdigest()
    redis.set(keys[0], json.dumps(envelope, separators=(",", ":")), ex=3600)

    subprocess.run(
        [
            "kubectl",
            "--context",
            "kind-metrics",
            "-n",
            "metrics",
            "rollout",
            "restart",
            "deployment/metrics-api-metrics-api",
        ],
        check=True,
    )
    subprocess.run(
        [
            "kubectl",
            "--context",
            "kind-metrics",
            "-n",
            "metrics",
            "rollout",
            "status",
            "deployment/metrics-api-metrics-api",
            "--timeout=120s",
        ],
        check=True,
    )
    forward = subprocess.Popen(
        [
            "kubectl",
            "--context",
            "kind-metrics",
            "-n",
            "metrics",
            "port-forward",
            "service/metrics-api-metrics-api",
            "18081:8000",
        ],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    stale_base_url = "http://127.0.0.1:18081"
    deadline = time.monotonic() + 30
    while True:
        try:
            if httpx.get(f"{stale_base_url}/healthz", timeout=2).status_code == 200:
                break
        except httpx.HTTPError:
            if forward.poll() is not None or time.monotonic() >= deadline:
                raise
            time.sleep(0.25)
    subprocess.run(
        [
            "kubectl",
            "--context",
            "kind-metrics",
            "delete",
            "clusterrolebinding",
            binding,
        ],
        check=True,
    )
    try:
        stale = httpx.get(f"{stale_base_url}{route}", timeout=30)
        assert stale.status_code == 200
        assert "ttl=-" in stale.headers["cache-status"]
        assert stale.json()["status"]["conditions"][0]["reason"] == "StaleData"

        redis.flushdb()
        failed = httpx.get(f"{stale_base_url}{route}", timeout=30)
        assert failed.status_code == 503
        assert failed.json() == {
            "apiVersion": "v1",
            "kind": "Status",
            "status": "Failure",
            "reason": "ServiceUnavailable",
            "message": "The requested metrics report could not be produced.",
            "code": 503,
        }
    finally:
        subprocess.run(
            [
                "helm",
                "--kube-context",
                "kind-metrics",
                "upgrade",
                "metrics-api",
                "helm/metrics-api",
                "--namespace",
                "metrics",
                "--reuse-values",
                "--wait",
                "--timeout=120s",
            ],
            check=True,
        )
        redis.close()
        forward.terminate()
        forward.wait(timeout=5)
        time.sleep(1)
