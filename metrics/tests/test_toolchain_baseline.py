"""Toolchain and image baseline contracts."""

from __future__ import annotations

import ast
import re
import shutil
import subprocess
from pathlib import Path

import pytest

from metrics.dev.cli import _COMMANDS, build_parser

METRICS_ROOT = Path(__file__).parents[1]


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
        [helm, "template", "test", "helm/metrics-api"],
        cwd=METRICS_ROOT,
        check=True,
        capture_output=True,
        text=True,
    ).stdout
    assert "kind: NetworkPolicy" in rendered
    assert "kubernetes.io/metadata.name: kube-system" in rendered
    assert "name: METRICS_PLATFORM_NAME" in rendered
    assert 'value: "canfar"' in rendered


def test_precommit_enforces_google_docstrings_via_ruff() -> None:
    text = (METRICS_ROOT / "pyproject.toml").read_text(encoding="utf-8")
    assert '"D"' in text or '"D",' in text
    assert 'convention = "google"' in text
    hooks = (METRICS_ROOT / ".pre-commit-config.yaml").read_text(encoding="utf-8")
    assert "metrics-ruff-check" in hooks
    assert "ruff check" in hooks
