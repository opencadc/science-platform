from __future__ import annotations

import pytest
from pydantic import ValidationError

from metrics.config import Settings


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


def test_provider_mode_legacy_live_maps_to_kueue() -> None:
    assert Settings(provider_mode="live").provider_mode == "kueue"


def test_provider_mode_legacy_live_case_insensitive() -> None:
    assert Settings(provider_mode="LIVE").provider_mode == "kueue"


def test_kueue_cluster_queues_accepts_comma_separated_env(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Helm/charts pass comma-separated METRICS_KUEUE_CLUSTER_QUEUES (not JSON)."""
    monkeypatch.setenv("METRICS_KUEUE_CLUSTER_QUEUES", "cq-a,cq-b")
    assert Settings().kueue_cluster_queues == ["cq-a", "cq-b"]


def test_kueue_cluster_queues_accepts_json_array_env(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("METRICS_KUEUE_CLUSTER_QUEUES", '["cq-a","cq-b"]')
    assert Settings().kueue_cluster_queues == ["cq-a", "cq-b"]
