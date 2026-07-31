"""Final configuration and documentation contract checks."""

from __future__ import annotations

import os
import re
from pathlib import Path

import pytest
import yaml

from metrics.core.settings import Settings

METRICS_ROOT = Path(__file__).parents[1]


@pytest.mark.parametrize(
    "values_path",
    [
        METRICS_ROOT / "helm" / "metrics-api" / "values.yaml",
        METRICS_ROOT / "helm" / "metrics-api" / "values-dev.yaml",
        METRICS_ROOT / "scripts" / "kind-values.yaml",
    ],
)
def test_shipped_values_env_validates_against_settings(
    monkeypatch: pytest.MonkeyPatch,
    values_path: Path,
) -> None:
    for name in tuple(os.environ):
        if name.startswith("METRICS_"):
            monkeypatch.delenv(name)

    values = yaml.safe_load(values_path.read_text(encoding="utf-8"))
    for name, value in values.get("env", {}).items():
        monkeypatch.setenv(name, str(value))

    settings = Settings()
    assert settings.sources.platform == "kueue"
    if values.get("env"):
        assert settings.providers.kueue.cluster_queues


@pytest.mark.parametrize(
    "rbac_template",
    [
        METRICS_ROOT / "helm" / "metrics-api" / "templates" / "rbac.yaml",
        METRICS_ROOT.parent / "helm" / "templates" / "metricsBackend-rbac.yaml",
    ],
)
def test_shipped_clusterqueue_rbac_requires_get_only(rbac_template: Path) -> None:
    manifest = rbac_template.read_text(encoding="utf-8")

    assert 'resources: ["clusterqueues"]' in manifest
    assert 'verbs: ["get"]' in manifest


def test_current_contract_docs_have_valid_relative_links() -> None:
    docs = [
        METRICS_ROOT / "CONTEXT.md",
        METRICS_ROOT / "README.md",
        METRICS_ROOT / "docs" / "architecture.md",
        METRICS_ROOT / "docs" / "design.md",
        METRICS_ROOT / "docs" / "environment-contracts.md",
        METRICS_ROOT / "docs" / "kueue-platform.md",
        METRICS_ROOT / "docs" / "specs.md",
        METRICS_ROOT / "docs" / "adr" / "README.md",
        METRICS_ROOT / "docs" / "adr" / "0023-kr8s-kubernetes-client.md",
    ]
    broken: list[str] = []
    for document in docs:
        text = document.read_text(encoding="utf-8")
        for target in re.findall(r"\[[^\]]+\]\(([^)]+)\)", text):
            if "://" in target or target.startswith("#"):
                continue
            relative_target = target.split("#", 1)[0]
            if relative_target and not (document.parent / relative_target).resolve().exists():
                broken.append(f"{document.relative_to(METRICS_ROOT)} -> {target}")

    assert broken == []
