"""Settings: env-only configuration, strict unknown-key rejection, field contracts."""

from __future__ import annotations

import pytest
from pydantic import ValidationError
from pydantic_settings.exceptions import SettingsError

from metrics.core.settings import CacheConfig, KueueProviderConfig, PromQLProviderConfig, Settings


@pytest.mark.parametrize(
    ("settings", "path"),
    [
        ({"sources": {"platfrom": "kueue"}}, "sources.platfrom"),
        ({"providers": {"kueee": {}}}, "providers.kueee"),
        ({"providers": {"kueue": {"cluster_queue": ["cq-a"]}}}, "providers.kueue.cluster_queue"),
        ({"providers": {"kueue": {"cohort": "legacy"}}}, "providers.kueue.cohort"),
        ({"cache": {"ttl_second": 10, "backend": "memory"}}, "cache.ttl_second"),
        ({"cache": {"scope_ttl_seconds": {"platform": 10}}}, "cache.scope_ttl_seconds"),
        ({"sources": {"platform": "prometheus"}}, "sources.platform"),
    ],
)
def test_unknown_nested_settings_identify_rejected_path(
    settings: dict[str, object],
    path: str,
) -> None:
    with pytest.raises(ValidationError) as excinfo:
        Settings.model_validate({"cache": {"backend": "memory"}} | settings)
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
        Settings.model_validate(
            {
                "cache": {"backend": "memory"},
                "providers": {"kueue": {"cluster_queues": ["cq-a", "cq-a"]}},
            }
        )
    with pytest.raises(ValidationError):
        KueueProviderConfig.model_validate({"cluster_queues": "cq-single"})


def test_kueue_cluster_queues_env_requires_json_array(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("METRICS_CACHE__BACKEND", "memory")
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
    monkeypatch.setenv("METRICS_CACHE__BACKEND", "memory")
    monkeypatch.setenv(f"METRICS_PROVIDERS__{provider}__ENABLED", "true")
    with pytest.raises((ValidationError, SettingsError), match=provider.lower()):
        Settings()


def test_cache_secret_and_deadline_contract(monkeypatch: pytest.MonkeyPatch) -> None:
    with pytest.raises(ValidationError, match="at least 32 UTF-8 bytes"):
        CacheConfig()
    with pytest.raises(ValidationError, match="at least 32 UTF-8 bytes"):
        CacheConfig(key_secret="too-short")

    monkeypatch.setenv("METRICS_CACHE__KEY_SECRET", "é" * 16)
    settings = Settings()
    assert settings.cache.redis_command_timeout_seconds == 0.5
    assert settings.cache.fill_timeout_seconds == 10
    assert settings.cache.cold_get_timeout_seconds == 12


def test_promql_configuration_exposes_no_caller_transport_escape_hatches() -> None:
    config = PromQLProviderConfig(mimir_tenant_id="tenant-a")
    assert str(config.base_url) == "http://prometheus.metrics.svc:9090/"
    assert config.request_timeout_seconds == 5

    for field in ("query", "headers", "endpoint", "range", "credentials"):
        with pytest.raises(ValidationError, match=field):
            PromQLProviderConfig.model_validate({field: "unsafe"})
    with pytest.raises(ValidationError, match="mimir_tenant_id"):
        PromQLProviderConfig(mimir_tenant_id="tenant\r\nX-Unsafe: value")
