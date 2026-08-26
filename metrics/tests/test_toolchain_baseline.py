"""Toolchain and image baseline contracts."""

from __future__ import annotations

import ast
import re
import shutil
import subprocess
from pathlib import Path

import pytest
import yaml

from metrics.dev import stack
from metrics.dev.cli import _COMMANDS, build_parser

METRICS_ROOT = Path(__file__).parents[1]
WORKFLOW = METRICS_ROOT.parent / ".github" / "workflows" / "ci.metrics.yml"
WORKFLOW_DIR = WORKFLOW.parent
TOUCHED_WORKFLOWS = (
    WORKFLOW,
    WORKFLOW_DIR / "ci.commit.check.yml",
)


def test_pyproject_pins_ty_and_exposes_metrics_dev() -> None:
    text = (METRICS_ROOT / "pyproject.toml").read_text(encoding="utf-8")
    assert "ty==0.0.74" in text
    assert "mypy" not in text
    assert 'metrics-dev = "metrics.dev.cli:main"' in text


def test_metrics_dev_exposes_approved_commands() -> None:
    parser = build_parser()
    help_text = parser.format_help()
    for name in _COMMANDS:
        assert name in help_text


def test_dockerfile_uses_slim_uv_and_non_root() -> None:
    text = (METRICS_ROOT / "Dockerfile").read_text(encoding="utf-8")
    assert "python:3.13-slim" in text
    assert "ghcr.io/astral-sh/uv:" in text
    assert "UV_VERSION=0.12.5" in text
    assert "uv sync --frozen --no-dev --no-editable" in text
    assert "useradd" in text or "adduser" in text
    assert "USER metrics" in text
    assert 'CMD ["python", "-m", "metrics.main"]' in text


def test_ci_workflow_matches_local_toolchain_and_quality_contract() -> None:
    workflow = yaml.safe_load(WORKFLOW.read_text(encoding="utf-8"))
    assert isinstance(workflow, dict)

    jobs = workflow["jobs"]
    lint_job = jobs["metrics-lint-test"]
    lint_commands = "\n".join(
        step.get("run", "") for step in lint_job["steps"] if isinstance(step, dict)
    )
    assert "uv lock --check" in lint_commands
    assert "uv sync --locked --group dev" in lint_commands
    assert "uv run ruff check src tests" in lint_commands
    assert "uv run ruff format --check src tests" in lint_commands
    assert "uv run ty check" in lint_commands
    assert "--cov-fail-under=80" in lint_commands

    smoke_job = jobs["metrics-kind-smoke"]
    smoke_steps = {step["name"]: step for step in smoke_job["steps"] if isinstance(step, dict)}
    smoke_env = smoke_job["env"]
    kubectl_setup = smoke_steps["Setup kubectl"]["with"]
    kind_setup = smoke_steps["Setup kind cluster"]["with"]
    assert smoke_env["KUEUE_CHART_VERSION"] == stack.KUEUE_VERSION
    assert kubectl_setup["version"] == stack.KUBERNETES_VERSION
    assert kind_setup["version"] == f"v{stack.KIND_VERSION}"
    assert kind_setup["node_image"] == stack.KIND_NODE_IMAGE
    assert kind_setup["kubectl_version"] == stack.KUBERNETES_VERSION

    smoke_commands = "\n".join(
        step.get("run", "") for step in smoke_steps.values() if isinstance(step, dict)
    )
    assert "bash scripts/kind-smoke.sh" in smoke_commands
    smoke_env = smoke_steps["Kind smoke (Kueue, test-setup, Helm, integration tests)"]["env"]
    assert smoke_env["KIND_SMOKE_SKIP_BUILD"] == "1"
    for removed in ("KIND_SMOKE_CI", "KIND_IMAGE_LOAD_TIMEOUT_SECONDS", "KIND_PRELOAD_IMAGES"):
        assert removed not in smoke_env


def test_harden_runner_uses_repo_required_v2_tag() -> None:
    refs = [
        match.group(1)
        for workflow in TOUCHED_WORKFLOWS
        for match in re.finditer(
            r"step-security/harden-runner@([^\s]+)",
            workflow.read_text(encoding="utf-8"),
        )
    ]
    assert refs
    assert all(ref == "v2" for ref in refs)


def test_commit_check_scans_the_pr_delta_with_full_history() -> None:
    workflow = yaml.safe_load((WORKFLOW_DIR / "ci.commit.check.yml").read_text(encoding="utf-8"))
    checkout = next(
        step
        for step in workflow["jobs"]["commit-check"]["steps"]
        if step.get("name") == "Checkout code"
    )
    assert checkout["with"]["ref"] == "${{ github.event.pull_request.head.sha || github.sha }}"
    assert checkout["with"]["fetch-depth"] == 0
    commit_check = next(
        step
        for step in workflow["jobs"]["commit-check"]["steps"]
        if step.get("name") == "Run Commit Check"
    )
    assert commit_check["env"]["CCHK_ALLOW_MERGE_COMMITS"] == "true"
    assert "merge" in commit_check["env"]["CCHK_ALLOW_COMMIT_TYPES"].split(",")
    assert "commit-signoff" not in commit_check["with"]
    assert "merge-base" not in commit_check["with"]


def test_kind_smoke_wrapper_completes_the_single_lifecycle() -> None:
    script = (METRICS_ROOT / "scripts" / "kind-smoke.sh").read_text(encoding="utf-8")
    stack_source = (METRICS_ROOT / "src" / "metrics" / "dev" / "stack.py").read_text(
        encoding="utf-8"
    )

    assert "metrics-dev up" in script
    assert "metrics-dev smoke" in script
    assert "KIND_SMOKE_SKIP_BUILD" in stack_source
    assert "profile" not in script


def test_ci_kind_teardown_uses_the_guarded_lifecycle_command() -> None:
    workflow = (WORKFLOW_DIR / "ci.metrics.yml").read_text(encoding="utf-8")

    assert "uv run metrics-dev destroy --confirm kind-metrics" in workflow
    assert "kind-smoke-teardown.sh" not in workflow


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


def test_precommit_registers_fast_and_manual_stages() -> None:
    text = (METRICS_ROOT / ".pre-commit-config.yaml").read_text(encoding="utf-8")
    for hook_id in (
        "metrics-uv-lock",
        "metrics-ruff-check",
        "metrics-ruff-format",
        "metrics-ty",
        "metrics-pytest-fast",
        "metrics-image-smoke",
        "metrics-kind-smoke",
        "metrics-redis-outage-smoke",
        "metrics-otel-smoke",
    ):
        assert hook_id in text
    assert "stages: [manual]" in text
    assert "ruff check --fix" in text
    assert "ty check" in text


@pytest.mark.parametrize(
    "script_name",
    [
        "precommit-image-smoke.sh",
        "precommit-redis-outage-smoke.sh",
        "precommit-otel-smoke.sh",
    ],
)
def test_manual_stage_scripts_exist_and_are_executable(script_name: str) -> None:
    path = METRICS_ROOT / "scripts" / script_name
    assert path.is_file()
    assert path.stat().st_mode & 0o111


def test_uv_lock_lists_ty_not_mypy() -> None:
    lock = (METRICS_ROOT / "uv.lock").read_text(encoding="utf-8")
    assert re.search(r'(?m)^name = "ty"$', lock)
    assert 'name = "mypy"' not in lock


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


def test_host_redis_readiness_uses_bounded_tcp() -> None:
    source = (METRICS_ROOT / "src" / "metrics" / "dev" / "stack.py").read_text(encoding="utf-8")
    assert "_wait_for_tcp_port(" in source
    assert "socket.create_connection" in source
    assert 'urlopen("http://127.0.0.1:16379' not in source
    assert "did not become ready within" in source


def test_precommit_enforces_google_docstrings_via_ruff() -> None:
    text = (METRICS_ROOT / "pyproject.toml").read_text(encoding="utf-8")
    assert '"D"' in text or '"D",' in text
    assert 'convention = "google"' in text
    hooks = (METRICS_ROOT / ".pre-commit-config.yaml").read_text(encoding="utf-8")
    assert "metrics-ruff-check" in hooks
    assert "ruff check" in hooks
