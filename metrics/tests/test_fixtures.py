"""Contracts for the labelled, deterministic Kueue development fixtures."""

from __future__ import annotations

import copy
from pathlib import Path

import pytest
import yaml

from metrics.dev import fixtures, stack

METRICS_ROOT = Path(__file__).parents[1]
TOPOLOGY = METRICS_ROOT / "scripts" / "test-setup.yaml"
WORKLOADS = METRICS_ROOT / "scripts" / "workload-fixtures.yaml"
KUEUE_CONFIG = METRICS_ROOT / "scripts" / "kueue-config.yaml"


def _documents(path: Path) -> list[dict[str, object]]:
    """Load non-empty YAML documents from one fixture manifest."""
    return [document for document in yaml.safe_load_all(path.read_text()) if document]


def _write_documents(path: Path, documents: list[dict[str, object]]) -> None:
    """Write temporary fixture documents for negative validation tests."""
    path.write_text(
        "---\n".join(yaml.safe_dump(document, sort_keys=False) for document in documents),
        encoding="utf-8",
    )


def test_topology_uses_v1beta2_clusterqueues_and_labelled_localqueues() -> None:
    documents = _documents(TOPOLOGY)
    kueue = [item for item in documents if str(item["apiVersion"]).startswith("kueue.")]
    assert all(item["apiVersion"] == "kueue.x-k8s.io/v1beta2" for item in kueue)
    assert not any(item["kind"] == "Cohort" for item in documents)

    cluster_queues = {
        item["metadata"]["name"]: item for item in documents if item["kind"] == "ClusterQueue"
    }
    assert set(cluster_queues) == set(stack.CLUSTER_QUEUES)
    communities = {
        name: queue["metadata"]["labels"]["canfar.net/community"]
        for name, queue in cluster_queues.items()
    }
    assert all(communities.values())
    assert all("cohortName" not in queue["spec"] for queue in cluster_queues.values())

    local_queues = [item for item in documents if item["kind"] == "LocalQueue"]
    identities: set[tuple[str, str, str, str]] = set()
    for queue in local_queues:
        metadata = queue["metadata"]
        namespace = metadata["namespace"]
        name = metadata["name"]
        labels = metadata["labels"]
        username = labels["canfar.net/username"]
        community = labels["canfar.net/community"]
        cluster_queue = queue["spec"]["clusterQueue"]
        assert username and community
        assert cluster_queue in communities
        assert communities[cluster_queue] == community
        identities.add((namespace, name, username, community))
    assert len(identities) == len(local_queues)
    assert {namespace for namespace, _, _, _ in identities} == set(stack.WORKLOAD_NAMESPACES)
    assert {"bob", "alice", "carol"} <= {username for _, _, username, _ in identities}


def test_topology_marks_only_reset_owned_objects() -> None:
    documents = _documents(TOPOLOGY)
    owned_kinds = {"ResourceFlavor", "RuntimeClass", "ClusterQueue", "LocalQueue"}
    for document in documents:
        labels = document["metadata"].get("labels", {})
        if document["kind"] in owned_kinds:
            assert labels[fixtures.FIXTURE_OWNER_LABEL] == "metrics-dev"
        else:
            assert fixtures.FIXTURE_OWNER_LABEL not in labels


def test_kueue_config_does_not_enable_cohort_fixture_behaviour() -> None:
    config = yaml.safe_load(KUEUE_CONFIG.read_text())
    assert config["apiVersion"] == "config.kueue.x-k8s.io/v1beta2"
    assert "metadata" not in config
    assert "Cohort.kueue.x-k8s.io" not in config["controller"]["groupKindConcurrency"]


def test_jobs_are_finite_pinned_and_carry_matching_queue_identity() -> None:
    jobs = _documents(WORKLOADS)
    assert jobs
    topology = _documents(TOPOLOGY)
    local_queues = {
        (item["metadata"]["namespace"], item["metadata"]["name"]): item["metadata"]["labels"]
        for item in topology
        if item["kind"] == "LocalQueue"
    }

    images: set[str] = set()
    for job in jobs:
        assert job["kind"] == "Job"
        assert job["spec"]["backoffLimit"] == 0
        metadata = job["metadata"]
        labels = metadata["labels"]
        assert labels["kueue.x-k8s.io/queue-name"]
        identity = (
            metadata["namespace"],
            labels["kueue.x-k8s.io/queue-name"],
        )
        assert identity in local_queues
        assert labels["canfar.net/username"]
        assert labels["canfar.net/community"]
        queue_labels = local_queues[identity]
        assert (labels["canfar.net/username"], labels["canfar.net/community"]) == (
            queue_labels["canfar.net/username"],
            queue_labels["canfar.net/community"],
        )

        pod_metadata = job["spec"]["template"]["metadata"]
        pod_labels = pod_metadata["labels"]
        assert "kueue.x-k8s.io/queue-name" not in pod_labels
        assert pod_labels["canfar.net/username"] == labels["canfar.net/username"]
        assert pod_labels["canfar.net/community"] == labels["canfar.net/community"]
        pod_spec = job["spec"]["template"]["spec"]
        for container in pod_spec.get("initContainers", []) + pod_spec["containers"]:
            images.add(container["image"])
            assert container["securityContext"]["allowPrivilegeEscalation"] is False
            assert container["securityContext"]["capabilities"]["drop"] == ["ALL"]
            assert container["resources"]["requests"]
            assert container["resources"]["limits"]
    assert len(images) == 1
    assert "@sha256:" in images.pop()


def test_prepare_prunes_only_owned_fixture_objects_before_reapply(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    calls: list[tuple[str, ...]] = []

    class FakeStack:
        WORKLOAD_NAMESPACES = ("canfar-workloads", "canfar-workloads-secondary")
        WORKLOAD_NAMESPACE = "canfar-workloads"
        CLUSTER_QUEUES = ("cq-proton", "cq-electron", "cq-fair")

        @staticmethod
        def _kubectl(*args: str) -> None:
            calls.append(args)

    monkeypatch.setattr(fixtures, "_stack", lambda: FakeStack)
    topology = tmp_path / "topology.yaml"
    topology.write_text("{}\n", encoding="utf-8")

    fixtures._prepare(topology)

    selector = "metrics.canfar.net/fixture-owner=metrics-dev"
    expected_deletes = [
        *(
            delete
            for namespace in FakeStack.WORKLOAD_NAMESPACES
            for delete in (
                (
                    "--namespace",
                    namespace,
                    "delete",
                    "job",
                    "-l",
                    "metrics.canfar.net/fixture-phase",
                    "--ignore-not-found",
                    "--wait",
                    f"--timeout={fixtures.TIMEOUT}",
                ),
                (
                    "--namespace",
                    namespace,
                    "delete",
                    "localqueue",
                    "-l",
                    selector,
                    "--ignore-not-found",
                    "--wait",
                    f"--timeout={fixtures.TIMEOUT}",
                ),
            )
        ),
        (
            "delete",
            "resourceflavor,runtimeclass,clusterqueue",
            "-l",
            selector,
            "--ignore-not-found",
            "--wait",
            f"--timeout={fixtures.TIMEOUT}",
        ),
    ]
    actual_deletes = [call for call in calls if "delete" in call]
    assert actual_deletes == expected_deletes
    apply_index = calls.index(("apply", "-f", str(topology)))
    assert all("delete" not in call for call in calls[apply_index:])


def test_fixture_validator_allows_distinct_local_queues_with_same_labels(tmp_path: Path) -> None:
    topology = _documents(TOPOLOGY)
    duplicate = next(
        copy.deepcopy(item)
        for item in topology
        if item["kind"] == "LocalQueue" and item["metadata"]["name"] == "lq-bob-physics"
    )
    duplicate["metadata"]["name"] = "lq-bob-physics-duplicate"
    topology.append(duplicate)
    topology_path = tmp_path / "topology.yaml"
    _write_documents(topology_path, topology)

    fixtures.validate_fixture_metadata(topology_path, WORKLOADS)


def test_fixture_validator_rejects_duplicate_local_queue_name(tmp_path: Path) -> None:
    topology = _documents(TOPOLOGY)
    duplicate = next(
        copy.deepcopy(item)
        for item in topology
        if item["kind"] == "LocalQueue" and item["metadata"]["name"] == "lq-bob-physics"
    )
    topology.append(duplicate)
    topology_path = tmp_path / "topology.yaml"
    _write_documents(topology_path, topology)

    with pytest.raises(stack.DevStackError, match="duplicate LocalQueue Kubernetes identity"):
        fixtures.validate_fixture_metadata(topology_path, WORKLOADS)


def test_fixture_validator_rejects_duplicate_local_queue_uid(tmp_path: Path) -> None:
    topology = _documents(TOPOLOGY)
    duplicate = next(
        copy.deepcopy(item)
        for item in topology
        if item["kind"] == "LocalQueue" and item["metadata"]["name"] == "lq-bob-physics"
    )
    uid = "550e8400-e29b-41d4-a716-446655440000"
    topology[-1]["metadata"]["uid"] = uid
    duplicate["metadata"]["name"] = "lq-bob-physics-duplicate"
    duplicate["metadata"]["uid"] = uid
    topology.append(duplicate)
    topology_path = tmp_path / "topology.yaml"
    _write_documents(topology_path, topology)

    with pytest.raises(stack.DevStackError, match="duplicate LocalQueue .* metadata.uid"):
        fixtures.validate_fixture_metadata(topology_path, WORKLOADS)


def test_fixture_validator_rejects_malformed_local_queue_uid(tmp_path: Path) -> None:
    topology = _documents(TOPOLOGY)
    queue = next(item for item in topology if item["kind"] == "LocalQueue")
    queue["metadata"]["uid"] = "not a Kubernetes UID"
    topology_path = tmp_path / "topology.yaml"
    _write_documents(topology_path, topology)

    with pytest.raises(stack.DevStackError, match="malformed LocalQueue .* metadata.uid"):
        fixtures.validate_fixture_metadata(topology_path, WORKLOADS)


def test_fixture_validator_rejects_job_queue_mismatch(tmp_path: Path) -> None:
    workloads = _documents(WORKLOADS)
    job = workloads[0]
    job["metadata"]["labels"] = copy.deepcopy(job["metadata"]["labels"])
    job["metadata"]["labels"]["kueue.x-k8s.io/queue-name"] = "lq-fair-high"
    workloads_path = tmp_path / "workloads.yaml"
    _write_documents(workloads_path, workloads)

    with pytest.raises(stack.DevStackError, match="labels do not match LocalQueue"):
        fixtures.validate_fixture_metadata(TOPOLOGY, workloads_path)


def test_fixture_validator_rejects_pod_label_mismatch(tmp_path: Path) -> None:
    workloads = _documents(WORKLOADS)
    job = workloads[0]
    job["spec"]["template"]["metadata"]["labels"] = copy.deepcopy(
        job["spec"]["template"]["metadata"]["labels"]
    )
    job["spec"]["template"]["metadata"]["labels"]["canfar.net/community"] = "physics"
    workloads_path = tmp_path / "workloads.yaml"
    _write_documents(workloads_path, workloads)

    with pytest.raises(stack.DevStackError, match="Pod template .* community"):
        fixtures.validate_fixture_metadata(TOPOLOGY, workloads_path)
