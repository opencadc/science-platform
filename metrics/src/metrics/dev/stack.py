"""Pinned, fail-closed kind and Helm development lifecycle."""

from __future__ import annotations

import json
import os
import re
import socket
import subprocess
import tempfile
import time
import urllib.parse
import urllib.request
from collections.abc import Sequence
from pathlib import Path

from metrics.dev.fixtures import apply_fixtures
from metrics.errors import ProviderExecutionError
from metrics.providers.promql import (
    _DEFAULT_FUTURE_SAMPLE_TOLERANCE_SECONDS,
    _DEFAULT_MAX_SAMPLE_AGE_SECONDS,
    _DEFAULT_MAX_SERIES,
    _query,
    _validate_response,
)

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
WORKLOAD_NAMESPACES = ("canfar-workloads", "canfar-workloads-secondary")
CLUSTER_QUEUES = ("cq-proton", "cq-electron", "cq-fair")

METRICS_ROOT = Path(__file__).parents[3]
FIXTURES = METRICS_ROOT / "scripts" / "test-setup.yaml"
WORKLOAD_FIXTURES = METRICS_ROOT / "scripts" / "workload-fixtures.yaml"
TEST_DEPENDENCIES = METRICS_ROOT / "scripts" / "test-dependencies.yaml"
KUEUE_CONFIG = METRICS_ROOT / "scripts" / "kueue-config.yaml"
KIND_VALUES = METRICS_ROOT / "scripts" / "kind-values.yaml"
CHART = METRICS_ROOT / "helm" / "metrics-api"
_IMAGE_NAME_COMPONENT = r"[a-z0-9]+(?:(?:[._]|__|-+)[a-z0-9]+)*"
_IMAGE_REPOSITORY = re.compile(
    rf"{_IMAGE_NAME_COMPONENT}(?::[0-9]{{1,5}})?(?:/{_IMAGE_NAME_COMPONENT})*"
)
_IMAGE_TAG = re.compile(r"[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}")
_ACCOUNTING_PROFILE_SELECTOR = "metrics.canfar.net/profile=accounting"
_ACCOUNTING_PROFILE_NAMESPACED_KINDS = "deployment,service,configmap,serviceaccount"
_ACCOUNTING_PROFILE_WORKLOAD_KINDS = "role,rolebinding"
_ACCOUNTING_PROFILE_CLUSTER_KINDS = "clusterrole,clusterrolebinding"
_PROMETHEUS_READY_DEADLINE_SECONDS = 60.0
_PROMETHEUS_READY_POLL_INTERVAL_SECONDS = 1.0
_PROMETHEUS_REQUEST_TIMEOUT_SECONDS = 5.0


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


def test_dependencies() -> None:
    """Converge disposable Redis, Prometheus, KSM, and OTLP test fixtures."""
    assert_safe_context()
    _kubectl(
        "apply",
        "--server-side",
        "--field-manager=metrics-dev-test",
        "-f",
        str(TEST_DEPENDENCIES),
    )
    for deployment in (
        "metrics-test-redis",
        "metrics-test-kube-state-metrics",
        "metrics-test-prometheus",
        "metrics-test-otel-collector",
    ):
        _kubectl(
            "rollout",
            "status",
            f"deployment/{deployment}",
            "--namespace",
            METRICS_NAMESPACE,
            "--timeout=5m",
        )


def _validate_image_reference(repository: str, tag: str) -> tuple[str, str]:
    """Validate image parts before shell, YAML, or Helm interpolation."""
    if not repository or len(repository) > 255 or _IMAGE_REPOSITORY.fullmatch(repository) is None:
        raise DevStackError("METRICS_IMAGE_REPOSITORY must be a Docker repository name[:port]/path")
    first_component = repository.split("/", 1)[0]
    if ":" in first_component and not 1 <= int(first_component.rsplit(":", 1)[1]) <= 65_535:
        raise DevStackError("METRICS_IMAGE_REPOSITORY port is outside the TCP range")
    if not tag or len(tag) > 128 or tag.lower() == "latest" or _IMAGE_TAG.fullmatch(tag) is None:
        raise DevStackError(
            "METRICS_IMAGE_TAG must be a non-latest Docker tag of at most 128 characters"
        )
    return repository, tag


def _build_and_load_image() -> tuple[str, str]:
    """Build or verify a tagged production image, then load it into kind."""
    tag = os.environ.get("METRICS_IMAGE_TAG", f"dev-{int(time.time())}")
    repository = os.environ.get("METRICS_IMAGE_REPOSITORY", "canfar-metrics-local")
    _validate_image_reference(repository, tag)
    assert_safe_context()
    image_ref = f"{repository}:{tag}"
    if os.environ.get("KIND_SMOKE_SKIP_BUILD") == "1":
        _run(["docker", "image", "inspect", image_ref], capture=True)
    else:
        _run(["docker", "build", "--tag", image_ref, "."])
    _run(["kind", "load", "docker-image", image_ref, "--name", KIND_CLUSTER])
    return repository, tag


def _deploy(repository: str, tag: str) -> None:
    """Deploy only the Metrics API through its production Helm chart."""
    _validate_image_reference(repository, tag)
    command = [
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
    ]
    _helm(*command)


def image() -> None:
    """Build, load, and Helm-deploy the production image."""
    repository, tag = _build_and_load_image()
    _cleanup_retired_accounting_profile()
    _deploy(repository, tag)
    print(f"deployed {repository}:{tag}")


def _cleanup_retired_accounting_profile() -> None:
    """Remove only the retired, label-owned accounting profile resources."""
    assert_safe_context()
    _kubectl(
        "--namespace",
        METRICS_NAMESPACE,
        "delete",
        _ACCOUNTING_PROFILE_NAMESPACED_KINDS,
        "-l",
        _ACCOUNTING_PROFILE_SELECTOR,
        "--ignore-not-found",
        "--wait",
    )
    for namespace in WORKLOAD_NAMESPACES:
        _kubectl(
            "--namespace",
            namespace,
            "delete",
            _ACCOUNTING_PROFILE_WORKLOAD_KINDS,
            "-l",
            _ACCOUNTING_PROFILE_SELECTOR,
            "--ignore-not-found",
        )
    _kubectl(
        "delete",
        _ACCOUNTING_PROFILE_CLUSTER_KINDS,
        "-l",
        _ACCOUNTING_PROFILE_SELECTOR,
        "--ignore-not-found",
    )


def up() -> None:
    """Create or converge the single supported disposable development stack."""
    _ensure_cluster()
    _cleanup_retired_accounting_profile()
    _install_kueue()
    test_dependencies()
    fixtures()
    repository, tag = _build_and_load_image()
    _deploy(repository, tag)
    print(
        f"dev ready: kind={KIND_VERSION} kubernetes={KUBERNETES_VERSION} "
        f"kueue={KUEUE_VERSION} context={KUBE_CONTEXT}"
    )


def run_host() -> None:
    """Run the host API with narrowed kubeconfig against disposable test Redis."""
    assert_safe_context()
    forward = subprocess.Popen(
        [
            "kubectl",
            "--context",
            KUBE_CONTEXT,
            "--namespace",
            METRICS_NAMESPACE,
            "port-forward",
            "service/metrics-test-redis",
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
            _wait_for_tcp_port(
                forward,
                16379,
                timeout_seconds=15,
                description="Redis port-forward",
            )
            env = os.environ | {
                "KUBECONFIG": str(kubeconfig),
                "METRICS_CLUSTER_NAME": KUBE_CONTEXT,
                "METRICS_CACHE__KEY_SECRET": "metrics-dev-ephemeral-cache-key!!",
                "METRICS_REDIS_URL": "redis://127.0.0.1:16379/0",
                "METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES": '["cq-proton","cq-electron","cq-fair"]',
                "METRICS_PROVIDERS__KUEUE__NAMESPACES": '["canfar-workloads","canfar-workloads-secondary"]',
            }
            _run(["uv", "run", "python", "-m", "metrics.main"], env=env)
    finally:
        forward.terminate()
        try:
            forward.wait(timeout=5)
        except subprocess.TimeoutExpired:
            forward.kill()


def _wait_for_tcp_port(
    process: subprocess.Popen[str] | subprocess.Popen[bytes],
    port: int,
    *,
    timeout_seconds: float,
    description: str,
) -> None:
    """Wait for a local TCP listener or fail when the finite budget expires."""
    deadline = time.monotonic() + timeout_seconds
    while process.poll() is None:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise DevStackError(f"{description} did not become ready within {timeout_seconds:g}s")
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=min(1, remaining)):
                return
        except OSError:
            time.sleep(min(0.25, remaining))
    raise DevStackError(f"{description} failed")


def _restart_metrics_deployment() -> None:
    """Flush the local Redis database and restart the API deployment."""
    _kubectl(
        "--namespace",
        METRICS_NAMESPACE,
        "exec",
        "deployment/metrics-test-redis",
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


def _start_smoke_forwards(
    started: list[subprocess.Popen], specifications: Sequence[tuple[str, str]]
) -> None:
    """Start and record the requested local port-forwards for smoke."""
    for service, mapping in specifications:
        started.append(
            subprocess.Popen(
                [
                    "kubectl",
                    "--context",
                    KUBE_CONTEXT,
                    "--namespace",
                    METRICS_NAMESPACE,
                    "port-forward",
                    service,
                    mapping,
                ],
                cwd=METRICS_ROOT,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
        )


def _wait_for_prometheus_readiness(process: subprocess.Popen, port: str) -> None:
    """Wait for the disposable Prometheus to expose both platform resources."""
    query = _query(
        scope="platform",
        subject=None,
        cluster=KUBE_CONTEXT,
        namespaces=list(WORKLOAD_NAMESPACES),
    )
    deadline = time.monotonic() + _PROMETHEUS_READY_DEADLINE_SECONDS
    last_error: Exception | None = None
    while (remaining := deadline - time.monotonic()) > 0:
        if process.poll() is not None:
            raise DevStackError("Prometheus port-forward failed")
        try:
            request = urllib.request.Request(
                f"http://127.0.0.1:{port}/api/v1/query",
                data=urllib.parse.urlencode({"query": query}).encode(),
                headers={"Content-Type": "application/x-www-form-urlencoded"},
                method="POST",
            )
            with urllib.request.urlopen(
                request,
                timeout=min(_PROMETHEUS_REQUEST_TIMEOUT_SECONDS, remaining),
            ) as response:
                if response.status != 200:
                    raise OSError(f"Prometheus returned HTTP {response.status}")
                payload = json.loads(response.read())
            _validate_response(
                payload,
                max_series=_DEFAULT_MAX_SERIES,
                max_sample_age_seconds=_DEFAULT_MAX_SAMPLE_AGE_SECONDS,
                future_sample_tolerance_seconds=_DEFAULT_FUTURE_SAMPLE_TOLERANCE_SECONDS,
                cutoff=None,
            )
            return
        except (OSError, ProviderExecutionError, ValueError) as error:
            last_error = error
        if process.poll() is not None:
            raise DevStackError("Prometheus port-forward failed")
        remaining = deadline - time.monotonic()
        if remaining > 0:
            time.sleep(min(_PROMETHEUS_READY_POLL_INTERVAL_SECONDS, remaining))
    raise DevStackError(
        "Prometheus did not return a valid CPU and memory platform vector "
        f"within {_PROMETHEUS_READY_DEADLINE_SECONDS:g}s; last error={last_error!r}"
    )


def _wait_for_http_health(process: subprocess.Popen, port: str) -> None:
    """Wait for the API health endpoint while checking the forward process."""
    deadline = time.monotonic() + 60
    while True:
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{port}/healthz", timeout=2) as response:
                if response.status == 200:
                    return
        except OSError:
            if process.poll() is not None or time.monotonic() >= deadline:
                raise DevStackError("metrics API did not become healthy within 60s")
            time.sleep(1)


def _run_smoke_integration(port: str, redis_port: str) -> None:
    """Run integration checks and verify graceful API process restart."""
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


def _stop_smoke_forwards(*forwards: subprocess.Popen) -> None:
    """Stop local forwards, escalating to SIGKILL when a process ignores SIGTERM."""
    for process in forwards:
        try:
            process.terminate()
        except ProcessLookupError:
            pass
    for process in forwards:
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)


def smoke() -> None:
    """Exercise the Helm-deployed service through a real HTTP socket."""
    assert_safe_context()
    started_at = time.monotonic()
    port = "18080"
    redis_port = "16380"
    prometheus_port = "19090"
    fixtures()
    forwards: list[subprocess.Popen] = []
    try:
        _start_smoke_forwards(
            forwards,
            [("service/metrics-test-prometheus", f"{prometheus_port}:9090")],
        )
        prometheus_forward = forwards[-1]
        _wait_for_prometheus_readiness(prometheus_forward, prometheus_port)
        _stop_smoke_forwards(prometheus_forward)
        forwards.remove(prometheus_forward)
        _restart_metrics_deployment()
        _start_smoke_forwards(
            forwards,
            [
                ("service/metrics-api-metrics-api", f"{port}:8000"),
                ("service/metrics-test-redis", f"{redis_port}:6379"),
            ],
        )
        forward, redis_forward = forwards
        _wait_for_http_health(forward, port)
        _wait_for_tcp_port(
            redis_forward,
            int(redis_port),
            timeout_seconds=60,
            description="Redis port-forward",
        )
        _run_smoke_integration(port, redis_port)
    finally:
        _stop_smoke_forwards(*forwards)
    elapsed = time.monotonic() - started_at
    budget = 120
    print(f"warm dev smoke completed in {elapsed:.1f}s (budget: {budget}s)")
    if elapsed > budget:
        raise DevStackError(f"warm dev smoke exceeded its {budget}-second budget")


def down() -> None:
    """Remove the API and disposable test dependencies while retaining the cluster."""
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
    _cleanup_retired_accounting_profile()
    _kubectl("delete", "-f", str(TEST_DEPENDENCIES), "--ignore-not-found")


def reset() -> None:
    """Recreate workload fixtures while retaining cluster image caches."""
    assert_safe_context()
    fixtures()


def destroy(confirmation: str | None) -> None:
    """Delete only the exact approved cluster after explicit confirmation."""
    assert_safe_context()
    if confirmation != KUBE_CONTEXT:
        raise DevStackError(f"destroy requires --confirm {KUBE_CONTEXT}")
    _run(["kind", "delete", "cluster", "--name", KIND_CLUSTER])
