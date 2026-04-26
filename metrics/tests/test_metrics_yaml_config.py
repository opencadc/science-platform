"""YAML ``metrics`` block loads before environment overrides."""

from __future__ import annotations

import textwrap
from pathlib import Path

import pytest
from pydantic import ValidationError

from metrics.core.settings import Settings


def test_metrics_yaml_merges_into_settings(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    cfg = tmp_path / "metrics.yaml"
    cfg.write_text(
        textwrap.dedent(
            """
            metrics:
              sources:
                platform: kueue
              cache:
                ttl_seconds: 120
              providers:
                kueue:
                  cluster_queues: ["cq-y"]
        """
        ),
        encoding="utf-8",
    )
    monkeypatch.setenv("METRICS_CONFIG_FILE", str(cfg))
    s = Settings()
    assert s.providers.kueue.cluster_queues == ["cq-y"]
    assert s.cache.ttl_seconds == 120
    assert s.sources.platform == "kueue"


def test_env_overrides_metrics_yaml(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    cfg = tmp_path / "c.yaml"
    cfg.write_text(
        textwrap.dedent(
            """
            metrics:
              cache:
                ttl_seconds: 120
              providers:
                kueue:
                  cluster_queues: ["a"]
        """
        ),
        encoding="utf-8",
    )
    monkeypatch.setenv("METRICS_CONFIG_FILE", str(cfg))
    monkeypatch.setenv("METRICS_CACHE__TTL_SECONDS", "999")
    s = Settings()
    assert s.cache.ttl_seconds == 999
    assert s.providers.kueue.cluster_queues == ["a"]


def test_require_config_file_raises(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    missing = tmp_path / "nope.yaml"
    monkeypatch.setenv("METRICS_CONFIG_FILE", str(missing))
    monkeypatch.setenv("METRICS_REQUIRE_CONFIG_FILE", "true")
    with pytest.raises(FileNotFoundError):
        Settings()


def test_missing_config_file_without_require_flag_ok(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    missing = tmp_path / "absent.yaml"
    monkeypatch.setenv("METRICS_CONFIG_FILE", str(missing))
    monkeypatch.delenv("METRICS_REQUIRE_CONFIG_FILE", raising=False)
    s = Settings()
    assert s.cache.ttl_seconds == 300


def test_metrics_yaml_scope_ttl_platform(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    cfg = tmp_path / "m.yaml"
    cfg.write_text(
        textwrap.dedent(
            """
            metrics:
              cache:
                ttl_seconds: 300
                scope_ttl_seconds:
                  platform: 120
        """
        ),
        encoding="utf-8",
    )
    monkeypatch.setenv("METRICS_CONFIG_FILE", str(cfg))
    s = Settings()
    assert s.cache.platform_ttl() == 120


def test_metrics_yaml_unknown_scope_ttl_rejected(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    cfg = tmp_path / "bad-scope.yaml"
    cfg.write_text(
        textwrap.dedent(
            """
            metrics:
              cache:
                scope_ttl_seconds:
                  platform: 60
                  future_scope: 10
        """
        ),
        encoding="utf-8",
    )
    monkeypatch.setenv("METRICS_CONFIG_FILE", str(cfg))
    with pytest.raises(ValidationError):
        Settings()


def test_metrics_yaml_missing_metrics_key_raises(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    cfg = tmp_path / "no-metrics.yaml"
    cfg.write_text(
        "sources:\n  platform: kueue\n",
        encoding="utf-8",
    )
    monkeypatch.setenv("METRICS_CONFIG_FILE", str(cfg))
    with pytest.raises(ValueError, match="metrics") as excinfo:
        Settings()
    assert "sources" in str(excinfo.value)


def test_metrics_yaml_non_mapping_metrics_raises(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    cfg = tmp_path / "bad-metrics.yaml"
    cfg.write_text(
        "metrics: [1, 2]\n",
        encoding="utf-8",
    )
    monkeypatch.setenv("METRICS_CONFIG_FILE", str(cfg))
    with pytest.raises(ValueError, match="metrics"):
        Settings()


def test_metrics_yaml_top_level_non_mapping_raises(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    cfg = tmp_path / "list-root.yaml"
    cfg.write_text(
        "- item\n",
        encoding="utf-8",
    )
    monkeypatch.setenv("METRICS_CONFIG_FILE", str(cfg))
    with pytest.raises(ValueError):
        Settings()


def test_metrics_yaml_empty_file_ok(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    cfg = tmp_path / "empty.yaml"
    cfg.write_text("", encoding="utf-8")
    monkeypatch.setenv("METRICS_CONFIG_FILE", str(cfg))
    s = Settings()
    assert s.sources.platform == "kueue"
