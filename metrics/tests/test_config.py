from __future__ import annotations

import pytest
from pydantic import ValidationError

from metrics.core.settings import (
    PlatformKubeMetricsSettings,
    PlatformSettings,
    Settings,
)


def test_environment_accepts_canonical_values() -> None:
    for raw in ("dev", "integration", "staging", "production"):
        s = Settings(environment=raw)
        assert s.environment == raw


def test_environment_accepts_legacy_aliases() -> None:
    assert Settings(environment="int").environment == "integration"
    assert Settings(environment="INT").environment == "integration"
    assert Settings(environment="prod").environment == "production"
    assert Settings(environment="PROD").environment == "production"


def test_environment_rejects_unknown_tokens() -> None:
    with pytest.raises(ValidationError):
        Settings(environment="qa")


def test_kube_metrics_enabled_rejected_until_m4() -> None:
    with pytest.raises(ValidationError, match="M4"):
        Settings(
            platform=PlatformSettings(
                kube_metrics=PlatformKubeMetricsSettings(enabled=True),
            ),
        )


def test_kueue_cluster_queues_accepts_comma_separated_env(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("METRICS_KUEUE_CLUSTER_QUEUES", "cq-a,cq-b")
    assert Settings().platform.kueue.cluster_queues == ["cq-a", "cq-b"]


def test_kueue_cluster_queues_accepts_json_array_env(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("METRICS_KUEUE_CLUSTER_QUEUES", '["cq-a","cq-b"]')
    assert Settings().platform.kueue.cluster_queues == ["cq-a", "cq-b"]


def test_legacy_prometheus_url_env_merges_into_nested(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("METRICS_PROMETHEUS_URL", "http://prom.example:9090")
    assert Settings().platform.prometheus.url == "http://prom.example:9090"
