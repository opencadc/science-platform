"""Validated, condition-driven Kueue fixtures for the disposable dev stack."""

from __future__ import annotations

from collections.abc import Sequence
from pathlib import Path

import yaml

WORKLOAD_LABEL = "metrics.canfar.net/fixture-phase"
FIXTURE_OWNER_LABEL = "metrics.canfar.net/fixture-owner"
FIXTURE_OWNER = "metrics-dev"
FIXTURE_OWNER_SELECTOR = f"{FIXTURE_OWNER_LABEL}={FIXTURE_OWNER}"
JOB_UID_LABEL = "kueue.x-k8s.io/job-uid"
TIMEOUT = "180s"
USERNAME_LABEL = "canfar.net/username"
COMMUNITY_LABEL = "canfar.net/community"


def _stack():
    """Import the lifecycle owner without creating a module cycle."""
    from metrics.dev import stack

    return stack


def _kubectl_output(*args: str) -> str:
    """Return output from kubectl against the guarded context."""
    stack = _stack()
    return stack._output(["kubectl", "--context", stack.KUBE_CONTEXT, *args])


def _documents(manifest: Path) -> list[dict[str, object]]:
    """Load non-empty YAML documents from a fixture manifest."""
    documents: list[dict[str, object]] = []
    for document in yaml.safe_load_all(manifest.read_text(encoding="utf-8")):
        if document is not None:
            if not isinstance(document, dict):
                raise _stack().DevStackError(f"fixture document is not a mapping: {manifest}")
            documents.append(document)
    return documents


def _metadata(document: dict[str, object], *, context: str) -> dict[str, object]:
    """Return required Kubernetes metadata or fail closed."""
    metadata = document.get("metadata")
    if not isinstance(metadata, dict):
        raise _stack().DevStackError(f"{context} is missing metadata")
    return metadata


def _required_label(labels: object, key: str, *, context: str) -> str:
    """Require one non-empty platform label."""
    if not isinstance(labels, dict):
        raise _stack().DevStackError(f"{context} is missing labels")
    value = labels.get(key)
    if not isinstance(value, str) or not value.strip():
        raise _stack().DevStackError(f"{context} requires non-empty {key}")
    return value.strip()


def _validate_uid(metadata: dict[str, object], *, context: str, seen_uids: set[str]) -> None:
    """Validate opaque metadata.uid values and enforce global uniqueness."""
    uid = metadata.get("uid")
    if uid is None:
        return
    if not isinstance(uid, str) or not uid or any(character.isspace() for character in uid):
        raise _stack().DevStackError(f"malformed {context} metadata.uid")
    if uid in seen_uids:
        raise _stack().DevStackError(f"duplicate {context} metadata.uid")
    seen_uids.add(uid)


def validate_fixture_metadata(topology: Path, workloads: Path) -> None:
    """Validate queue and workload identity labels before applying fixtures.

    The fixture labels model platform-stamped metadata. The lifecycle validates
    them before sending manifests to Kubernetes so optional PromQL tests cannot
    accidentally exercise user-supplied or ambiguous identity labels.
    """
    stack = _stack()
    cluster_communities: dict[str, str] = {}
    local_queue_identities: set[tuple[str, str]] = set()
    local_queues: dict[tuple[str, str], tuple[str, str]] = {}
    seen_uids: set[str] = set()
    topology_documents = _documents(topology)
    for document in topology_documents:
        kind = document.get("kind")
        metadata = _metadata(document, context=f"{kind or 'fixture'}")
        name = metadata.get("name")
        if not isinstance(name, str) or not name:
            raise stack.DevStackError(f"{kind or 'fixture'} is missing metadata.name")
        context = f"{kind or 'fixture'} {name}"
        _validate_uid(metadata, context=context, seen_uids=seen_uids)
        if kind in {"ResourceFlavor", "RuntimeClass", "ClusterQueue", "LocalQueue"}:
            owner = _required_label(metadata.get("labels"), FIXTURE_OWNER_LABEL, context=context)
            if owner != FIXTURE_OWNER:
                raise stack.DevStackError(f"{context} has unexpected {FIXTURE_OWNER_LABEL}")
        if kind == "ClusterQueue":
            cluster_communities[name] = _required_label(
                metadata.get("labels"), COMMUNITY_LABEL, context=f"ClusterQueue {name}"
            )

    configured = set(stack.CLUSTER_QUEUES)
    if set(cluster_communities) != configured:
        raise stack.DevStackError(
            "fixture ClusterQueues must exactly match configured queues: "
            f"expected {sorted(configured)}, found {sorted(cluster_communities)}"
        )

    for document in topology_documents:
        if document.get("kind") != "LocalQueue":
            continue
        metadata = _metadata(document, context="LocalQueue")
        name = metadata.get("name")
        namespace = metadata.get("namespace")
        if not isinstance(name, str) or not name:
            raise stack.DevStackError("LocalQueue is missing metadata.name")
        if not isinstance(namespace, str) or not namespace:
            raise stack.DevStackError(f"LocalQueue {name} is missing metadata.namespace")
        if namespace not in stack.WORKLOAD_NAMESPACES:
            raise stack.DevStackError(
                f"LocalQueue {namespace}/{name} is outside configured workload namespaces"
            )
        identity = (namespace, name)
        if identity in local_queue_identities:
            raise stack.DevStackError(
                f"duplicate LocalQueue Kubernetes identity: namespace={namespace!r}, name={name!r}"
            )
        local_queue_identities.add(identity)
        username = _required_label(
            metadata.get("labels"), USERNAME_LABEL, context=f"LocalQueue {namespace}/{name}"
        )
        community = _required_label(
            metadata.get("labels"), COMMUNITY_LABEL, context=f"LocalQueue {namespace}/{name}"
        )
        spec = document.get("spec")
        cluster_queue = spec.get("clusterQueue") if isinstance(spec, dict) else None
        if not isinstance(cluster_queue, str) or cluster_queue not in cluster_communities:
            raise stack.DevStackError(
                f"LocalQueue {namespace}/{name} must reference a configured ClusterQueue"
            )
        if cluster_communities[cluster_queue] != community:
            raise stack.DevStackError(
                f"LocalQueue {namespace}/{name} community does not match {cluster_queue}"
            )
        local_queues[identity] = (username, community)

    for document in _documents(workloads):
        if document.get("kind") != "Job":
            continue
        metadata = _metadata(document, context="Job")
        name = str(metadata.get("name", "<unnamed>"))
        labels = metadata.get("labels")
        _required_label(labels, WORKLOAD_LABEL, context=f"Job {name}")
        username = _required_label(labels, USERNAME_LABEL, context=f"Job {name}")
        community = _required_label(labels, COMMUNITY_LABEL, context=f"Job {name}")
        namespace = str(metadata.get("namespace", ""))
        queue_name = _required_label(
            labels,
            "kueue.x-k8s.io/queue-name",
            context=f"Job {namespace}/{name}",
        )
        queue_identity = local_queues.get((namespace, queue_name))
        if queue_identity is None:
            raise stack.DevStackError(
                f"Job {namespace}/{name} references unknown LocalQueue {queue_name}"
            )
        if queue_identity != (username, community):
            raise stack.DevStackError(
                f"Job {namespace}/{name} labels do not match LocalQueue {queue_name}"
            )
        spec = document.get("spec")
        template = spec.get("template") if isinstance(spec, dict) else None
        template_metadata = template.get("metadata") if isinstance(template, dict) else None
        template_labels = (
            template_metadata.get("labels") if isinstance(template_metadata, dict) else None
        )
        if (
            _required_label(template_labels, USERNAME_LABEL, context=f"Pod template {name}")
            != username
        ):
            raise stack.DevStackError(f"Pod template {name} username does not match Job metadata")
        if (
            _required_label(template_labels, COMMUNITY_LABEL, context=f"Pod template {name}")
            != community
        ):
            raise stack.DevStackError(f"Pod template {name} community does not match Job metadata")


def _apply_phase(manifest: Path, phase: str) -> None:
    """Apply one labelled fixture phase."""
    _stack()._kubectl(
        "apply",
        "-f",
        str(manifest),
        "-l",
        f"{WORKLOAD_LABEL}={phase}",
    )


def _workload_name(job: str) -> str:
    """Wait for and return the Workload owned by a Job."""
    stack = _stack()
    uid = _kubectl_output(
        "--namespace",
        stack.WORKLOAD_NAMESPACE,
        "get",
        f"job/{job}",
        "-o",
        "jsonpath={.metadata.uid}",
    )
    selector = f"{JOB_UID_LABEL}={uid}"
    stack._kubectl(
        "--namespace",
        stack.WORKLOAD_NAMESPACE,
        "wait",
        "workload",
        "-l",
        selector,
        "--for=create",
        f"--timeout={TIMEOUT}",
    )
    return _kubectl_output(
        "--namespace",
        stack.WORKLOAD_NAMESPACE,
        "get",
        "workload",
        "-l",
        selector,
        "-o",
        "jsonpath={.items[0].metadata.name}",
    )


def _wait_admitted(job: str) -> str:
    """Wait for a Job-owned Workload to become admitted."""
    stack = _stack()
    workload = _workload_name(job)
    stack._kubectl(
        "--namespace",
        stack.WORKLOAD_NAMESPACE,
        "wait",
        f"workload/{workload}",
        "--for=condition=Admitted",
        f"--timeout={TIMEOUT}",
    )
    return workload


def _wait_running(job: str) -> None:
    """Wait for a Job Pod to reach Running."""
    stack = _stack()
    stack._kubectl(
        "--namespace",
        stack.WORKLOAD_NAMESPACE,
        "wait",
        "pod",
        "-l",
        f"job-name={job}",
        "--for=jsonpath={.status.phase}=Running",
        f"--timeout={TIMEOUT}",
    )


def _prepare(topology: Path) -> None:
    """Remove prior phased state and activate the topology."""
    stack = _stack()
    for namespace in stack.WORKLOAD_NAMESPACES:
        stack._kubectl(
            "--namespace",
            namespace,
            "delete",
            "job",
            "-l",
            WORKLOAD_LABEL,
            "--ignore-not-found",
            "--wait",
            f"--timeout={TIMEOUT}",
        )
        stack._kubectl(
            "--namespace",
            namespace,
            "wait",
            "pod",
            "-l",
            WORKLOAD_LABEL,
            "--for=delete",
            f"--timeout={TIMEOUT}",
        )
        stack._kubectl(
            "--namespace",
            namespace,
            "delete",
            "localqueue",
            "-l",
            FIXTURE_OWNER_SELECTOR,
            "--ignore-not-found",
            "--wait",
            f"--timeout={TIMEOUT}",
        )
    stack._kubectl(
        "delete",
        "resourceflavor,runtimeclass,clusterqueue",
        "-l",
        FIXTURE_OWNER_SELECTOR,
        "--ignore-not-found",
        "--wait",
        f"--timeout={TIMEOUT}",
    )
    stack._kubectl("apply", "-f", str(topology))
    for queue in stack.CLUSTER_QUEUES:
        stack._kubectl(
            "wait",
            f"clusterqueue/{queue}",
            "--for=condition=Active",
            f"--timeout={TIMEOUT}",
        )
    for namespace, queue_names in (
        (stack.WORKLOAD_NAMESPACE, ("lq-smoke", "lq-fair-high", "lq-fair-low")),
        ("canfar-workloads-secondary", ("lq-bob-physics",)),
    ):
        for queue in queue_names:
            stack._kubectl(
                "--namespace",
                namespace,
                "wait",
                f"localqueue/{queue}",
                "--for=condition=Active",
                f"--timeout={TIMEOUT}",
            )


def _assert_reserving(manifest: Path) -> None:
    """Create one long-running admitted workload for reservingWorkloads."""
    _apply_phase(manifest, "borrowing")
    _wait_admitted("integration-idle")
    _wait_running("integration-idle")


def _apply_controls(manifest: Path) -> None:
    """Create a second admitted workload and one intentionally pending workload."""
    stack = _stack()
    _apply_phase(manifest, "controls")
    _wait_admitted("resource-shapes")
    _wait_running("resource-shapes")
    pending = _workload_name("pending-demand")
    stack._kubectl(
        "--namespace",
        stack.WORKLOAD_NAMESPACE,
        "wait",
        "job/terminal-control",
        "--for=condition=Complete",
        f"--timeout={TIMEOUT}",
    )
    reserved = _kubectl_output(
        "--namespace",
        stack.WORKLOAD_NAMESPACE,
        "get",
        f"workload/{pending}",
        "-o",
        "jsonpath={.status.conditions[?(@.type=='QuotaReserved')].status}",
    )
    if reserved == "True":
        raise stack.DevStackError("pending-demand unexpectedly received quota")


def _diagnose(commands: Sequence[Sequence[str]]) -> None:
    """Print bounded fixture diagnostics without masking the original failure."""
    stack = _stack()
    for args in commands:
        stack._run(
            ["kubectl", "--context", stack.KUBE_CONTEXT, *args],
            check=False,
        )


def apply_fixtures(topology: Path, workloads: Path) -> None:
    """Validate and apply the small Kueue topology used by integration smoke."""
    stack = _stack()
    stack.assert_safe_context()
    try:
        validate_fixture_metadata(topology, workloads)
        _prepare(topology)
        _assert_reserving(workloads)
        _apply_controls(workloads)
    except Exception:
        _diagnose(
            (
                ("get", "pods,jobs,workloads,localqueues", "--all-namespaces", "-o", "wide"),
                ("get", "clusterqueues", "-o", "wide"),
                ("get", "events", "--all-namespaces", "--sort-by=.lastTimestamp"),
            )
        )
        raise
