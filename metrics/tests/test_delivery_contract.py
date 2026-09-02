"""Final configuration contract checks for shipped values and RBAC."""

from __future__ import annotations

import json
import os
from pathlib import Path

import pytest
import yaml
from pydantic import ValidationError

from metrics.core.settings import Settings

METRICS_ROOT = Path(__file__).parents[1]


def _settings_environment(values: dict[str, object]) -> dict[str, str]:
    """Build Settings input from chart values and external Secret references."""
    environment = {str(name): str(value) for name, value in (values.get("env") or {}).items()}
    if values.get("clusterName"):
        environment.setdefault("METRICS_CLUSTER_NAME", str(values["clusterName"]))
    kueue = values.get("kueue") or {}
    if "METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES" not in environment:
        cluster_queues = kueue.get("clusterQueues", [])
        if cluster_queues:
            environment["METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES"] = json.dumps(
                cluster_queues, separators=(",", ":")
            )
    if "METRICS_PROVIDERS__KUEUE__NAMESPACES" not in environment:
        namespaces = kueue.get("namespaces", [])
        if namespaces:
            environment["METRICS_PROVIDERS__KUEUE__NAMESPACES"] = json.dumps(
                namespaces, separators=(",", ":")
            )
    if values.get("redis", {}).get("urlSecret"):
        environment.setdefault("METRICS_REDIS_URL", "redis://external.example:6379/0")
    if values.get("cacheKeySecret"):
        environment.setdefault("METRICS_CACHE__KEY_SECRET", "x" * 32)
    return environment


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
    environment = _settings_environment(values)
    for name, value in environment.items():
        monkeypatch.setenv(name, str(value))

    if not environment.get("METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES"):
        with pytest.raises(ValidationError, match="providers"):
            Settings()
        return
    settings = Settings()
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
    assert "resourceNames:" in manifest
    assert 'verbs: ["get"]' in manifest


def test_metrics_spec_requires_no_store_without_last_modified_or_304() -> None:
    """The HTTP cache contract stays no-store without validator semantics."""
    specification = (METRICS_ROOT / "docs" / "specs.md").read_text(encoding="utf-8")

    assert "Cache-Control: no-store" in specification
    assert "Last-Modified" not in specification
    assert "304" not in specification
