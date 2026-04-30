from __future__ import annotations

import pytest
from pydantic import ValidationError
from pydantic_settings.exceptions import SettingsError

from metrics.core.settings import (
    CacheConfig,
    KubeProviderConfig,
    KueueProviderConfig,
    ProviderConfigs,
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


def test_kube_provider_enabled_rejected() -> None:
    with pytest.raises(ValidationError, match="reserved"):
        Settings(
            providers=ProviderConfigs(
                kube=KubeProviderConfig(enabled=True),
            ),
        )


def test_kueue_cluster_queues_accepts_json_array_env(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES", '["cq-a","cq-b"]')
    assert Settings().providers.kueue.cluster_queues == ["cq-a", "cq-b"]


def test_kueue_cluster_queues_comma_separated_env_rejected(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES", "cq-a,cq-b")
    with pytest.raises((ValidationError, SettingsError)):
        Settings()


def test_kueue_cluster_queues_accepts_direct_list_in_model() -> None:
    cfg = KueueProviderConfig(cluster_queues=["cq-x", "cq-y"])
    assert cfg.cluster_queues == ["cq-x", "cq-y"]


def test_kueue_cluster_queues_plain_string_not_json_array_rejected() -> None:
    with pytest.raises(ValidationError):
        KueueProviderConfig(cluster_queues="cq-single")


def test_nested_prometheus_url_from_env(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("METRICS_PROVIDERS__PROMETHEUS__URL", "http://prom.example:9090")
    assert Settings().providers.prometheus.url == "http://prom.example:9090"


def test_cache_scope_ttl_platform_from_dict() -> None:
    cache = CacheConfig(ttl_seconds=300, scope_ttl_seconds={"platform": 120})
    assert cache.platform_ttl() == 120


def test_cache_scope_ttl_platform_ttl_falls_back_to_global() -> None:
    cache = CacheConfig(ttl_seconds=300, scope_ttl_seconds={})
    assert cache.platform_ttl() == 300


def test_cache_scope_ttl_rejects_unknown_scope_keys() -> None:
    with pytest.raises(ValidationError):
        CacheConfig(scope_ttl_seconds={"platform": 60, "unknown_scope": 10})


def test_cache_scope_ttl_from_env_json(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("METRICS_CACHE__SCOPE_TTL_SECONDS", '{"platform": 90}')
    s = Settings()
    assert s.cache.platform_ttl() == 90


def test_cache_scope_ttl_invalid_json_env_raises(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("METRICS_CACHE__SCOPE_TTL_SECONDS", "{not json")
    with pytest.raises(SettingsError):
        Settings()


def test_cache_scope_ttl_non_json_object_string_rejected() -> None:
    for bad in ("garbage", "300", "platform=30", "[]", "null"):
        with pytest.raises(ValidationError):
            CacheConfig(scope_ttl_seconds=bad)


def test_cache_scope_ttl_empty_string_means_no_override() -> None:
    cache = CacheConfig(ttl_seconds=300, scope_ttl_seconds="   ")
    assert cache.platform_ttl() == 300


def test_cache_scope_ttl_non_object_env_rejected(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("METRICS_CACHE__SCOPE_TTL_SECONDS", "300")
    with pytest.raises((ValidationError, SettingsError)):
        Settings()
