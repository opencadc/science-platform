"""Toolchain and image baseline contracts."""

from __future__ import annotations

import ast
import shutil
import subprocess
from pathlib import Path

import pytest
import yaml

from metrics.dev import stack
from metrics.dev.cli import _COMMANDS, build_parser

METRICS_ROOT = Path(__file__).parents[1]
WORKFLOW = METRICS_ROOT.parent / ".github" / "workflows" / "ci.metrics.yml"


def test_metrics_dev_exposes_approved_commands() -> None:
    parser = build_parser()
    help_text = parser.format_help()
    for name in _COMMANDS:
        assert name in help_text


def test_ci_kind_versions_match_the_local_lifecycle() -> None:
    """CI and `metrics-dev` must run the same Kubernetes, kind, and Kueue versions."""
    workflow = yaml.safe_load(WORKFLOW.read_text(encoding="utf-8"))
    smoke_job = workflow["jobs"]["metrics-kind-smoke"]
    steps = {step["name"]: step for step in smoke_job["steps"] if isinstance(step, dict)}
    kubectl_setup = steps["Setup kubectl"]["with"]
    kind_setup = steps["Setup kind cluster"]["with"]
    assert smoke_job["env"]["KUEUE_CHART_VERSION"] == stack.KUEUE_VERSION
    assert kubectl_setup["version"] == stack.KUBERNETES_VERSION
    assert kind_setup["version"] == f"v{stack.KIND_VERSION}"
    assert kind_setup["node_image"] == stack.KIND_NODE_IMAGE
    assert kind_setup["kubectl_version"] == stack.KUBERNETES_VERSION


def test_entrypoint_forces_one_uvicorn_worker() -> None:
    source = (METRICS_ROOT / "src" / "metrics" / "main.py").read_text(encoding="utf-8")
    tree = ast.parse(source)
    calls = [
        node
        for node in ast.walk(tree)
        if isinstance(node, ast.Call)
        and isinstance(node.func, ast.Attribute)
        and node.func.attr == "run"
    ]
    assert calls, "expected uvicorn.run call"
    kwargs = {kw.arg: kw.value for kw in calls[0].keywords if kw.arg}
    workers = kwargs.get("workers")
    assert isinstance(workers, ast.Constant)
    assert workers.value == 1


def test_metrics_dev_console_script_resolves() -> None:
    result = subprocess.run(
        ["uv", "run", "metrics-dev", "--help"],
        cwd=METRICS_ROOT,
        check=False,
        capture_output=True,
        text=True,
    )
    assert result.returncode == 0
    for name in _COMMANDS:
        assert name in result.stdout


def test_chart_renders_default_network_policy() -> None:
    helm = shutil.which("helm")
    if helm is None:
        pytest.skip("helm is not installed")
    rendered = subprocess.run(
        [
            helm,
            "template",
            "test",
            "helm/metrics-api",
            "--set",
            "clusterName=test-cluster",
            "--set",
            "kueue.clusterQueues[0]=cq-test",
            "--set",
            "kueue.namespaces[0]=workloads",
            "--set",
            "serviceAccount.name=metrics-test",
        ],
        cwd=METRICS_ROOT,
        check=True,
        capture_output=True,
        text=True,
    ).stdout
    assert "kind: NetworkPolicy" in rendered
    assert "kubernetes.io/metadata.name: kube-system" in rendered
    assert "name: METRICS_PLATFORM_NAME" in rendered
    assert 'value: "canfar"' in rendered
