"""Condition-driven Kueue development fixtures."""

from __future__ import annotations

import time
from collections.abc import Sequence
from decimal import Decimal
from pathlib import Path

WORKLOAD_LABEL = "metrics.canfar.net/fixture-phase"
JOB_UID_LABEL = "kueue.x-k8s.io/job-uid"
TIMEOUT = "180s"


def _stack():
    """Import the lifecycle owner without creating a module cycle."""
    from metrics.dev import stack

    return stack


def _kubectl_output(*args: str) -> str:
    """Return output from kubectl against the guarded context."""
    stack = _stack()
    return stack._output(["kubectl", "--context", stack.KUBE_CONTEXT, *args])


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


def _millicpu(value: str) -> Decimal:
    """Convert a Kueue CPU quantity used by the fixtures to millicores."""
    suffixes = {
        "n": Decimal("0.000001"),
        "u": Decimal("0.001"),
        "m": Decimal("1"),
    }
    if value[-1] in suffixes:
        return Decimal(value[:-1]) * suffixes[value[-1]]
    return Decimal(value) * 1000


def _prepare(topology: Path) -> None:
    """Remove prior phased state and activate the topology."""
    stack = _stack()
    stack._kubectl(
        "--namespace",
        stack.WORKLOAD_NAMESPACE,
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
        stack.WORKLOAD_NAMESPACE,
        "wait",
        "pod",
        "-l",
        WORKLOAD_LABEL,
        "--for=delete",
        f"--timeout={TIMEOUT}",
    )
    stack._kubectl(
        "--namespace",
        stack.WORKLOAD_NAMESPACE,
        "delete",
        "job/integration-busy",
        "workload/integration-idle",
        "--ignore-not-found",
        "--wait",
        f"--timeout={TIMEOUT}",
    )
    stack._kubectl(
        "--namespace",
        stack.WORKLOAD_NAMESPACE,
        "delete",
        "localqueue/lq-fair-high",
        "localqueue/lq-fair-low",
        "--ignore-not-found",
        "--wait",
        f"--timeout={TIMEOUT}",
    )
    stack._kubectl(
        "delete",
        "clusterqueue/cq-fair",
        "--ignore-not-found",
        "--wait",
        f"--timeout={TIMEOUT}",
    )
    stack._kubectl("apply", "-f", str(topology))
    for queue in ("cq-proton", "cq-electron", "cq-fair"):
        stack._kubectl(
            "wait",
            f"clusterqueue/{queue}",
            "--for=condition=Active",
            f"--timeout={TIMEOUT}",
        )
    for queue in ("lq-smoke", "lq-fair-high", "lq-fair-low"):
        stack._kubectl(
            "--namespace",
            stack.WORKLOAD_NAMESPACE,
            "wait",
            f"localqueue/{queue}",
            "--for=condition=Active",
            f"--timeout={TIMEOUT}",
        )


def _assert_borrowing(manifest: Path) -> None:
    """Prove a Job exceeds nominal quota and is admitted by its cohort."""
    stack = _stack()
    _apply_phase(manifest, "borrowing")
    _wait_admitted("integration-idle")
    _wait_running("integration-idle")
    deadline = time.monotonic() + 30
    while True:
        used = _kubectl_output(
            "get",
            "clusterqueue/cq-electron",
            "-o",
            "jsonpath={.status.flavorsUsage[?(@.name=='default-flavor')].resources[?(@.name=='cpu')].total}",
        )
        if _millicpu(used) > 100:
            return
        if time.monotonic() >= deadline:
            raise stack.DevStackError(
                f"borrowing assertion failed: cq-electron CPU usage is {used}, expected more than 100m"
            )
        time.sleep(0.25)


def _apply_controls(manifest: Path) -> None:
    """Create resource-shape and exclusion controls."""
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
        "job/unlabelled-control",
        "job/empty-subject-control",
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


def _assert_fair_sharing(manifest: Path) -> None:
    """Warm one LocalQueue and prove the lower-use peer wins next admission."""
    stack = _stack()
    _apply_phase(manifest, "fair-warm")
    _wait_admitted("fair-warm-high")
    _wait_running("fair-warm-high")
    deadline = time.monotonic() + 180
    while True:
        high_usage = _kubectl_output(
            "--namespace",
            stack.WORKLOAD_NAMESPACE,
            "get",
            "localqueue/lq-fair-high",
            "-o",
            "jsonpath={.status.fairSharing.admissionFairSharingStatus.consumedResources.cpu}",
        )
        if high_usage and _millicpu(high_usage) >= 25:
            break
        if time.monotonic() >= deadline:
            raise stack.DevStackError(
                f"fair-share warmup recorded only {high_usage or 'no'} CPU usage"
            )
        time.sleep(0.25)
    low_usage = _kubectl_output(
        "--namespace",
        stack.WORKLOAD_NAMESPACE,
        "get",
        "localqueue/lq-fair-low",
        "-o",
        "jsonpath={.status.fairSharing.admissionFairSharingStatus.consumedResources.cpu}",
    )
    if low_usage and _millicpu(low_usage) >= _millicpu(high_usage):
        raise stack.DevStackError(
            f"fair-share warmup is not asymmetric: high={high_usage}, low={low_usage}"
        )

    _apply_phase(manifest, "fair-contenders")
    high = _workload_name("fair-next-high")
    _workload_name("fair-next-low")
    stack._kubectl(
        "--namespace",
        stack.WORKLOAD_NAMESPACE,
        "delete",
        "job/fair-warm-high",
        "--wait",
        f"--timeout={TIMEOUT}",
    )
    stack._kubectl(
        "--namespace",
        stack.WORKLOAD_NAMESPACE,
        "wait",
        "pod",
        "-l",
        "job-name=fair-warm-high",
        "--for=delete",
        f"--timeout={TIMEOUT}",
    )
    _wait_admitted("fair-next-low")
    _wait_running("fair-next-low")
    high_reserved = _kubectl_output(
        "--namespace",
        stack.WORKLOAD_NAMESPACE,
        "get",
        f"workload/{high}",
        "-o",
        "jsonpath={.status.conditions[?(@.type=='QuotaReserved')].status}",
    )
    if high_reserved == "True":
        raise stack.DevStackError("higher-use LocalQueue won the fair-share admission")


def _diagnose(commands: Sequence[Sequence[str]]) -> None:
    """Print bounded fixture diagnostics without masking the original failure."""
    stack = _stack()
    for args in commands:
        stack._run(
            ["kubectl", "--context", stack.KUBE_CONTEXT, *args],
            check=False,
        )


def apply_fixtures(topology: Path, workloads: Path) -> None:
    """Apply and assert borrowing, controls, and fair sharing."""
    stack = _stack()
    stack.assert_safe_context()
    try:
        _prepare(topology)
        _assert_borrowing(workloads)
        _apply_controls(workloads)
        _assert_fair_sharing(workloads)
    except Exception:
        _diagnose(
            (
                (
                    "get",
                    "pods,jobs,workloads,localqueues",
                    "-n",
                    stack.WORKLOAD_NAMESPACE,
                    "-o",
                    "wide",
                ),
                ("get", "clusterqueues,cohorts", "-o", "wide"),
                ("get", "events", "-n", stack.WORKLOAD_NAMESPACE, "--sort-by=.lastTimestamp"),
            )
        )
        raise
