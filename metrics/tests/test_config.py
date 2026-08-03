"""Settings: env-only configuration, strict unknown-key rejection, field contracts."""

from __future__ import annotations

import pytest
from pydantic import ValidationError
from pydantic_settings.exceptions import SettingsError

from metrics.core.settings import CacheConfig, KueueProviderConfig, Settings


@pytest.mark.parametrize(
    ("settings", "path"),
    [
        ({"sources": {"platfrom": "kueue"}}, "sources.platfrom"),
        ({"providers": {"kueee": {}}}, "providers.kueee"),
        ({"providers": {"kueue": {"cluster_queue": ["cq-a"]}}}, "providers.kueue.cluster_queue"),
        ({"providers": {"kueue": {"cohort": "legacy"}}}, "providers.kueue.cohort"),
        ({"cache": {"ttl_second": 10}}, "cache.ttl_second"),
        ({"cache": {"scope_ttl_seconds": {"platform": 10}}}, "cache.scope_ttl_seconds"),
        ({"sources": {"platform": "prometheus"}}, "sources.platform"),
    ],
)
def test_unknown_nested_settings_identify_rejected_path(
    settings: dict[str, object],
    path: str,
) -> None:
    with pytest.raises(ValidationError) as excinfo:
        Settings.model_validate(settings)
    assert path in str(excinfo.value).replace("\n", ".")


@pytest.mark.parametrize(
    "removed_field",
    [
        "kube_api_url",
        "kube_api_token",
        "token_file",
        "ca_file",
        "kube_verify_tls",
        "kube_clusterqueue_path",
        "http",
    ],
)
def test_kueue_removed_transport_fields_are_rejected(removed_field: str) -> None:
    """Endpoint/credential/TLS discovery moved to kr8s (ADR-0001); old keys fail loudly."""
    with pytest.raises(ValidationError, match=removed_field):
        KueueProviderConfig.model_validate({removed_field: "x"})


def test_kueue_defaults_and_cluster_queue_parsing() -> None:
    config = KueueProviderConfig(cluster_queues=["cq-x", " cq-y "])
    assert config.kueue_api_version == "kueue.x-k8s.io/v1beta2"
    assert config.cluster_queues == ["cq-x", "cq-y"]

    with pytest.raises(ValidationError, match="duplicate ClusterQueue names: cq-a"):
        Settings.model_validate({"providers": {"kueue": {"cluster_queues": ["cq-a", "cq-a"]}}})
    with pytest.raises(ValidationError):
        KueueProviderConfig.model_validate({"cluster_queues": "cq-single"})


def test_kueue_cluster_queues_env_requires_json_array(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES", '["cq-a","cq-b"]')
    assert Settings().providers.kueue.cluster_queues == ["cq-a", "cq-b"]

    monkeypatch.setenv("METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES", "cq-a,cq-b")
    with pytest.raises((ValidationError, SettingsError)):
        Settings()


@pytest.mark.parametrize("provider", ["PROMETHEUS", "KUBE"])
def test_removed_provider_env_blocks_are_rejected(
    monkeypatch: pytest.MonkeyPatch,
    provider: str,
) -> None:
    monkeypatch.setenv(f"METRICS_PROVIDERS__{provider}__ENABLED", "true")
    with pytest.raises((ValidationError, SettingsError), match=provider.lower()):
        Settings()


def test_cache_ttl_from_env_and_bounds(monkeypatch: pytest.MonkeyPatch) -> None:
    assert CacheConfig().ttl_seconds == 300
    monkeypatch.setenv("METRICS_CACHE__TTL_SECONDS", "90")
    assert Settings().cache.ttl_seconds == 90
    with pytest.raises(ValidationError):
        CacheConfig.model_validate({"ttl_seconds": -1})
