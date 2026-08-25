"""Pinned, fail-closed kind and Helm development lifecycle."""

from __future__ import annotations

import os
import re
import socket
import subprocess
import tempfile
import time
import urllib.request
from collections.abc import Sequence
from pathlib import Path

from metrics.dev.fixtures import apply_fixtures

KIND_VERSION = "0.32.0"
KIND_CLUSTER = "metrics"
KUBE_CONTEXT = "kind-metrics"
CONTROL_PLANE = "metrics-control-plane"
KIND_NODE_IMAGE = (
    "kindest/node:v1.33.12@sha256:3f5c8443c620245e4d355cfe09e96a91ead32ceaa569d3f1ca9edf0cb2fe2ff4"
)
KUBERNETES_VERSION = "v1.33.12"
KUEUE_VERSION = "0.19.2"
METRICS_NAMESPACE = "metrics"
WORKLOAD_NAMESPACE = "canfar-workloads"

METRICS_ROOT = Path(__file__).parents[3]
FIXTURES = METRICS_ROOT / "scripts" / "test-setup.yaml"
WORKLOAD_FIXTURES = METRICS_ROOT / "scripts" / "workload-fixtures.yaml"
ACCOUNTING_PROFILE = METRICS_ROOT / "scripts" / "accounting-profile.yaml"
KUEUE_CONFIG = METRICS_ROOT / "scripts" / "kueue-config.yaml"
KIND_VALUES = METRICS_ROOT / "scripts" / "kind-values.yaml"
CHART = METRICS_ROOT / "helm" / "metrics-api"


class DevStackError(RuntimeError):
    """Report a safe, actionable local-stack failure."""


def _run(
    command: Sequence[str],
    *,
    capture: bool = False,
    env: dict[str, str] | None = None,
    check: bool = True,
) -> subprocess.CompletedProcess[str]:
    """Run one finite command and raise a concise lifecycle error."""
    try:
        return subprocess.run(
            command,
            cwd=METRICS_ROOT,
            check=check,
            text=True,
            capture_output=capture,
            env=env,
        )
    except FileNotFoundError as error:
        raise DevStackError(f"required command not found: {command[0]}") from error
    except subprocess.CalledProcessError as error:
        detail = (error.stderr or error.stdout or "").strip()
        raise DevStackError(f"{' '.join(command)} failed: {detail}") from error


def _output(command: Sequence[str]) -> str:
    """Return stripped stdout from one checked command."""
    return _run(command, capture=True).stdout.strip()


def _clusters() -> set[str]:
    """Return local kind cluster names."""
    output = _output(["kind", "get", "clusters"])
    return set(output.splitlines()) if output else set()


def assert_safe_context() -> None:
    """Refuse mutation unless the exact local context and control-plane node match."""
    current = _output(["kubectl", "config", "current-context"])
    if current != KUBE_CONTEXT:
        raise DevStackError(f"refusing context {current!r}; select {KUBE_CONTEXT!r}")
    if KIND_CLUSTER not in _clusters():
        raise DevStackError(f"kind cluster {KIND_CLUSTER!r} does not exist")
    cluster = _output(
        [
            "kubectl",
            "--context",
            KUBE_CONTEXT,
            "config",
            "view",
            "--minify",
            "-o",
            "jsonpath={.contexts[0].context.cluster}",
        ]
    )
    node = _output(
        [
            "kubectl",
            "--context",
            KUBE_CONTEXT,
            "get",
            "nodes",
            "-l",
            "node-role.kubernetes.io/control-plane",
            "-o",
            "jsonpath={.items[0].metadata.name}",
        ]
    )
    if cluster != KUBE_CONTEXT or node != CONTROL_PLANE:
        raise DevStackError(
            f"refusing mutation: {KUBE_CONTEXT!r} resolved to cluster={cluster!r}, node={node!r}"
        )


def _ensure_cluster() -> None:
    """Create only the approved cluster, or verify the existing one."""
    version = _output(["kind", "version"])
    match = re.search(r"\bv?(\d+\.\d+\.\d+)\b", version)
    if not match or match.group(1) != KIND_VERSION:
        raise DevStackError(f"kind {KIND_VERSION} required; found {version}")
    _run(["docker", "info"], capture=True)
    if KIND_CLUSTER not in _clusters():
        _run(
            [
                "kind",
                "create",
                "cluster",
                "--name",
                KIND_CLUSTER,
                "--image",
                KIND_NODE_IMAGE,
                "--wait",
                "180s",
            ]
        )
        _run(["kubectl", "config", "use-context", KUBE_CONTEXT])
    assert_safe_context()
    node_version = _output(
        [
            "kubectl",
            "--context",
            KUBE_CONTEXT,
            "get",
            "node",
            CONTROL_PLANE,
            "-o",
            "jsonpath={.status.nodeInfo.kubeletVersion}",
        ]
    )
    node_image = _output(["docker", "inspect", CONTROL_PLANE, "--format", "{{.Config.Image}}"])
    if node_version != KUBERNETES_VERSION:
        raise DevStackError(
            f"existing cluster runs {node_version}, expected {KUBERNETES_VERSION}; "
            f"run `metrics-dev destroy --confirm {KUBE_CONTEXT}` first"
        )
    # kind may report the image with or without the digest suffix.
    if KIND_NODE_IMAGE.split("@", 1)[0] not in node_image and node_image != KIND_NODE_IMAGE:
        raise DevStackError(
            "existing cluster does not use the pinned node image; "
            f"run `metrics-dev destroy --confirm {KUBE_CONTEXT}` first"
        )


def _kubectl(*args: str) -> None:
    """Run kubectl against only the approved context."""
    _run(["kubectl", "--context", KUBE_CONTEXT, *args])


def _helm(*args: str) -> subprocess.CompletedProcess[str]:
    """Run Helm against only the approved context."""
    return _run(["helm", "--kube-context", KUBE_CONTEXT, *args])


def _install_kueue() -> None:
    """Install the pinned Kueue prerequisite."""
    assert_safe_context()
    _helm(
        "upgrade",
        "--install",
        "kueue",
        "oci://registry.k8s.io/kueue/charts/kueue",
        "--version",
        KUEUE_VERSION,
        "--namespace",
        "kueue-system",
        "--create-namespace",
        "--set-file",
        f"managerConfig.controllerManagerConfigYaml={KUEUE_CONFIG}",
        "--atomic",
        "--timeout",
        "10m",
    )
    _kubectl(
        "wait",
        "--namespace",
        "kueue-system",
        "deployment/kueue-controller-manager",
        "--for=condition=available",
        "--timeout=5m",
    )


def fixtures() -> None:
    """Converge deterministic core Kueue fixtures."""
    apply_fixtures(FIXTURES, WORKLOAD_FIXTURES)


def _build_and_load_image() -> tuple[str, str]:
    """Build a uniquely tagged production image and load it into kind."""
    assert_safe_context()
    tag = os.environ.get("METRICS_IMAGE_TAG", f"dev-{int(time.time())}")
    repository = "canfar-metrics-local"
    image_ref = f"{repository}:{tag}"
    _run(["docker", "build", "--tag", image_ref, "."])
    _run(["kind", "load", "docker-image", image_ref, "--name", KIND_CLUSTER])
    return repository, tag


def _deploy(repository: str, tag: str) -> None:
    """Deploy Redis, Collector, RBAC, and Metrics through its chart."""
    _helm(
        "upgrade",
        "--install",
        "metrics-api",
        str(CHART),
        "--namespace",
        METRICS_NAMESPACE,
        "--create-namespace",
        "--values",
        str(KIND_VALUES),
        "--set",
        f"image.repository={repository}",
        "--set",
        f"image.tag={tag}",
        "--wait",
        "--timeout=5m",
    )


def image() -> None:
    """Build, load, and Helm-deploy the production image."""
    repository, tag = _build_and_load_image()
    _deploy(repository, tag)
    print(f"deployed {repository}:{tag}")


def up(profile: str = "core") -> None:
    """Create or converge the approved core or accounting profile."""
    _ensure_cluster()
    _install_kueue()
    fixtures()
    if profile == "accounting":
        _kubectl(
            "apply", "--server-side", "--field-manager=metrics-dev", "-f", str(ACCOUNTING_PROFILE)
        )
        deployments = (
            "metrics-kube-state-metrics",
            "metrics-accounting-producer",
            "metrics-accounting-prometheus",
        )
        _kubectl(
            "rollout",
            "restart",
            *(f"deployment/{deployment}" for deployment in deployments),
            "--namespace",
            METRICS_NAMESPACE,
        )
        for deployment in deployments:
            _kubectl(
                "rollout",
                "status",
                f"deployment/{deployment}",
                "--namespace",
                METRICS_NAMESPACE,
                "--timeout=5m",
            )
    elif profile != "core":
        raise DevStackError(f"unknown profile: {profile}")
    repository, tag = _build_and_load_image()
    _deploy(repository, tag)
    print(
        f"{profile} ready: kind={KIND_VERSION} kubernetes={KUBERNETES_VERSION} "
        f"kueue={KUEUE_VERSION} context={KUBE_CONTEXT}"
    )


def run_host() -> None:
    """Run the host API with narrowed kubeconfig against the kind Redis Service."""
    assert_safe_context()
    forward = subprocess.Popen(
        [
            "kubectl",
            "--context",
            KUBE_CONTEXT,
            "--namespace",
            METRICS_NAMESPACE,
            "port-forward",
            "service/metrics-api-redis",
            "16379:6379",
        ],
        cwd=METRICS_ROOT,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    try:
        with tempfile.TemporaryDirectory(prefix="metrics-dev-") as directory:
            kubeconfig = Path(directory) / "kubeconfig"
            kubeconfig.write_text(
                _output(
                    [
                        "kubectl",
                        "--context",
                        KUBE_CONTEXT,
                        "config",
                        "view",
                        "--minify",
                        "--flatten",
                        "--raw",
                    ]
                ),
                encoding="utf-8",
            )
            kubeconfig.chmod(0o600)
            deadline = time.monotonic() + 15
            while forward.poll() is None and time.monotonic() < deadline:
                try:
                    with urllib.request.urlopen("http://127.0.0.1:16379", timeout=1):
                        break
                except OSError:
                    time.sleep(0.25)
            if forward.poll() is not None:
                raise DevStackError("Redis port-forward failed")
            env = os.environ | {
                "KUBECONFIG": str(kubeconfig),
                "METRICS_CLUSTER_NAME": KUBE_CONTEXT,
                "METRICS_CACHE__BACKEND": "redis",
                "METRICS_CACHE__KEY_SECRET": "metrics-dev-ephemeral-cache-key!!",
                "METRICS_REDIS_URL": "redis://127.0.0.1:16379/0",
                "METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES": '["cq-proton","cq-electron"]',
            }
            _run(["uv", "run", "python", "-m", "metrics.main"], env=env)
    finally:
        forward.terminate()
        try:
            forward.wait(timeout=5)
        except subprocess.TimeoutExpired:
            forward.kill()


def smoke() -> None:
    """Exercise the Helm-deployed service through a real HTTP socket."""
    assert_safe_context()
    started = time.monotonic()
    port = "18080"
    redis_port = "16380"
    _kubectl(
        "--namespace",
        METRICS_NAMESPACE,
        "exec",
        "deployment/metrics-api-redis",
        "--",
        "redis-cli",
        "FLUSHDB",
    )
    _kubectl(
        "--namespace",
        METRICS_NAMESPACE,
        "rollout",
        "restart",
        "deployment/metrics-api-metrics-api",
    )
    _kubectl(
        "--namespace",
        METRICS_NAMESPACE,
        "rollout",
        "status",
        "deployment/metrics-api-metrics-api",
        "--timeout=120s",
    )
    forward = subprocess.Popen(
        [
            "kubectl",
            "--context",
            KUBE_CONTEXT,
            "--namespace",
            METRICS_NAMESPACE,
            "port-forward",
            "service/metrics-api-metrics-api",
            f"{port}:8000",
        ],
        cwd=METRICS_ROOT,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    redis_forward = subprocess.Popen(
        [
            "kubectl",
            "--context",
            KUBE_CONTEXT,
            "--namespace",
            METRICS_NAMESPACE,
            "port-forward",
            "service/metrics-api-redis",
            f"{redis_port}:6379",
        ],
        cwd=METRICS_ROOT,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    try:
        deadline = time.monotonic() + 60
        while True:
            try:
                with urllib.request.urlopen(
                    f"http://127.0.0.1:{port}/healthz", timeout=2
                ) as response:
                    if response.status == 200:
                        break
            except OSError:
                if forward.poll() is not None or time.monotonic() >= deadline:
                    raise DevStackError("metrics API did not become healthy within 60s")
                time.sleep(1)
        while True:
            try:
                with socket.create_connection(("127.0.0.1", int(redis_port)), timeout=1):
                    break
            except OSError:
                if redis_forward.poll() is not None or time.monotonic() >= deadline:
                    raise DevStackError("Redis port-forward did not become ready")
                time.sleep(0.25)
        env = os.environ | {
            "METRICS_BASE_URL": f"http://127.0.0.1:{port}",
            "METRICS_TEST_REDIS_URL": f"redis://127.0.0.1:{redis_port}/0",
        }
        _run(["uv", "run", "pytest", "tests/integration", "-m", "integration", "-q"], env=env)
        pod = _output(
            [
                "kubectl",
                "--context",
                KUBE_CONTEXT,
                "--namespace",
                METRICS_NAMESPACE,
                "get",
                "pod",
                "-l",
                "app.kubernetes.io/component=api",
                "-o",
                "jsonpath={.items[0].metadata.name}",
            ]
        )
        before = int(
            _output(
                [
                    "kubectl",
                    "--context",
                    KUBE_CONTEXT,
                    "--namespace",
                    METRICS_NAMESPACE,
                    "get",
                    f"pod/{pod}",
                    "-o",
                    "jsonpath={.status.containerStatuses[0].restartCount}",
                ]
            )
        )
        _kubectl(
            "--namespace",
            METRICS_NAMESPACE,
            "exec",
            f"pod/{pod}",
            "--",
            "/bin/sh",
            "-c",
            "kill -TERM 1",
        )
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            restarts = int(
                _output(
                    [
                        "kubectl",
                        "--context",
                        KUBE_CONTEXT,
                        "--namespace",
                        METRICS_NAMESPACE,
                        "get",
                        f"pod/{pod}",
                        "-o",
                        "jsonpath={.status.containerStatuses[0].restartCount}",
                    ]
                )
            )
            if restarts > before:
                break
            time.sleep(0.5)
        else:
            raise DevStackError("API container did not restart after SIGTERM")
        previous_logs = _output(
            [
                "kubectl",
                "--context",
                KUBE_CONTEXT,
                "--namespace",
                METRICS_NAMESPACE,
                "logs",
                f"pod/{pod}",
                "--previous",
            ]
        )
        if "Runtime shutdown completed" not in previous_logs:
            raise DevStackError("graceful shutdown log was not emitted")
    finally:
        forward.terminate()
        redis_forward.terminate()
        try:
            forward.wait(timeout=5)
        except subprocess.TimeoutExpired:
            forward.kill()
        try:
            redis_forward.wait(timeout=5)
        except subprocess.TimeoutExpired:
            redis_forward.kill()
    elapsed = time.monotonic() - started
    print(f"warm smoke completed in {elapsed:.1f}s (budget: 120s)")
    if elapsed > 120:
        raise DevStackError("warm smoke exceeded the nominal two-minute budget")


def down() -> None:
    """Remove the Helm workload while retaining the cluster and image cache."""
    assert_safe_context()
    _run(
        [
            "helm",
            "--kube-context",
            KUBE_CONTEXT,
            "uninstall",
            "metrics-api",
            "--namespace",
            METRICS_NAMESPACE,
            "--ignore-not-found",
        ]
    )


def reset() -> None:
    """Recreate workload fixtures while retaining cluster image caches."""
    assert_safe_context()
    _kubectl(
        "delete",
        "namespace",
        WORKLOAD_NAMESPACE,
        "--ignore-not-found",
        "--wait",
        "--timeout=120s",
    )
    fixtures()


def destroy(confirmation: str | None) -> None:
    """Delete only the exact approved cluster after explicit confirmation."""
    assert_safe_context()
    if confirmation != KUBE_CONTEXT:
        raise DevStackError(f"destroy requires --confirm {KUBE_CONTEXT}")
    _run(["kind", "delete", "cluster", "--name", KIND_CLUSTER])
