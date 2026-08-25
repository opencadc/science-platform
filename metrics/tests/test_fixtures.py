"""Contracts for deterministic Kueue development fixtures."""

from __future__ import annotations

from pathlib import Path

import yaml

from metrics.dev import stack
from metrics.dev.fixtures import _millicpu

METRICS_ROOT = Path(__file__).parents[1]
TOPOLOGY = METRICS_ROOT / "scripts" / "test-setup.yaml"
WORKLOADS = METRICS_ROOT / "scripts" / "workload-fixtures.yaml"
KUEUE_CONFIG = METRICS_ROOT / "scripts" / "kueue-config.yaml"


def _documents(path: Path) -> list[dict]:
    return [document for document in yaml.safe_load_all(path.read_text()) if document]


def test_topology_uses_v1beta2_borrowing_and_equal_fair_queues() -> None:
    documents = _documents(TOPOLOGY)
    kueue = [item for item in documents if item["apiVersion"].startswith("kueue.")]
    assert all(item["apiVersion"] == "kueue.x-k8s.io/v1beta2" for item in kueue)

    by_name = {item["metadata"]["name"]: item for item in documents}
    assert by_name["cq-proton"]["spec"]["cohortName"] == "cohort-atom"
    electron = by_name["cq-electron"]["spec"]
    assert electron["cohortName"] == "cohort-atom"
    cpu = electron["resourceGroups"][0]["flavors"][0]["resources"][0]
    assert cpu == {"name": "cpu", "nominalQuota": "100m", "borrowingLimit": "1"}
    assert by_name["cq-fair"]["spec"]["admissionScope"] == {
        "admissionMode": "UsageBasedAdmissionFairSharing"
    }
    for name in ("lq-fair-high", "lq-fair-low"):
        assert by_name[name]["spec"] == {
            "clusterQueue": "cq-fair",
            "fairSharing": {"weight": "1"},
        }


def test_fair_sharing_controller_history_is_nonzero_and_fast() -> None:
    config = yaml.safe_load(KUEUE_CONFIG.read_text())
    assert config["apiVersion"] == "config.kueue.x-k8s.io/v1beta2"
    assert "metadata" not in config
    assert config["admissionFairSharing"] == {
        "usageHalfLifeTime": "1m",
        "usageSamplingInterval": "1s",
        "resourceWeights": {"cpu": 1, "memory": 1},
    }


def test_jobs_are_finite_pinned_and_cover_required_controls() -> None:
    jobs = _documents(WORKLOADS)
    by_name = {job["metadata"]["name"]: job for job in jobs}
    assert {
        "integration-idle",
        "resource-shapes",
        "pending-demand",
        "terminal-control",
        "unlabelled-control",
        "empty-subject-control",
        "fair-warm-high",
        "fair-next-high",
        "fair-next-low",
    } == set(by_name)

    images: set[str] = set()
    for job in jobs:
        assert job["kind"] == "Job"
        assert job["spec"]["backoffLimit"] == 0
        pod_spec = job["spec"]["template"]["spec"]
        for container in pod_spec.get("initContainers", []) + pod_spec["containers"]:
            images.add(container["image"])
            assert container["securityContext"]["allowPrivilegeEscalation"] is False
            assert container["securityContext"]["capabilities"]["drop"] == ["ALL"]
            assert container["resources"]["requests"]
            assert container["resources"]["limits"]
    assert len(images) == 1
    assert "@sha256:" in images.pop()

    shape = by_name["resource-shapes"]["spec"]["template"]["spec"]
    assert len(shape["containers"]) == 2
    assert shape["initContainers"]
    assert shape["runtimeClassName"] == "metrics-overhead"
    assert by_name["pending-demand"]["spec"]["suspend"] is True
    assert "app.kubernetes.io/managed-by" not in by_name["unlabelled-control"]["metadata"]["labels"]
    empty = by_name["empty-subject-control"]["metadata"]["labels"]
    assert empty["canfar.net/username"] == ""
    assert empty["canfar.net/community"] == ""


def test_skaha_jobs_carry_the_canonical_session_labels() -> None:
    jobs = _documents(WORKLOADS)
    canonical = {
        "app.kubernetes.io/managed-by",
        "app.kubernetes.io/part-of",
        "canfar.net/id",
        "canfar.net/username",
        "canfar.net/name",
        "canfar.net/kind",
        "canfar.net/job",
        "canfar.net/flavor",
        "canfar.net/accelerator",
        "canfar.net/community",
        "canfar.net/project",
    }
    included = [
        job
        for job in jobs
        if job["metadata"]["name"] not in {"unlabelled-control", "empty-subject-control"}
    ]
    for job in included:
        labels = job["metadata"]["labels"]
        assert canonical <= labels.keys()
        assert labels["app.kubernetes.io/managed-by"] == "skaha"
        assert labels["app.kubernetes.io/part-of"] == "canfar"
        assert labels["kueue.x-k8s.io/queue-name"]
        assert job["spec"]["template"]["metadata"]["labels"] == labels
    assert len({job["metadata"]["labels"]["canfar.net/username"] for job in included}) >= 2
    assert len({job["metadata"]["labels"]["canfar.net/community"] for job in included}) >= 2


def test_fixture_cpu_quantity_comparison() -> None:
    assert _millicpu("100m") == 100
    assert _millicpu("2") == 2000
    assert _millicpu("955132n") > 0
    assert stack.KUEUE_VERSION == "0.19.2"
