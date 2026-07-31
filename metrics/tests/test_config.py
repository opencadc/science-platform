from __future__ import annotations

import pytest
from pydantic import ValidationError
from pydantic_settings.exceptions import SettingsError

from metrics.core.settings import (
    CacheConfig,
    KueueProviderConfig,
    Settings,
)


def test_environment_accepts_canonical_values() -> None:
    for raw in ("dev", "integration", "staging", "production"):
        s = Settings(environment=raw)
        assert s.environment == raw


def test_environment_accepts_legacy_aliases() -> None:
    assert Settings.model_validate({"environment": "int"}).environment == "integration"
    assert Settings.model_validate({"environment": "INT"}).environment == "integration"
    assert Settings.model_validate({"environment": "prod"}).environment == "production"
    assert Settings.model_validate({"environment": "PROD"}).environment == "production"


def test_environment_rejects_unknown_tokens() -> None:
    with pytest.raises(ValidationError):
        Settings.model_validate({"environment": "qa"})


@pytest.mark.parametrize(
    ("settings", "path"),
    [
        ({"sources": {"platfrom": "kueue"}}, "sources.platfrom"),
        ({"providers": {"kueee": {}}}, "providers.kueee"),
        ({"providers": {"kueue": {"cluster_queue": ["cq-a"]}}}, "providers.kueue.cluster_queue"),
        ({"providers": {"kueue": {"http": {"htt2": True}}}}, "providers.kueue.http.htt2"),
        ({"cache": {"ttl_second": 10}}, "cache.ttl_second"),
        ({"cache": {"scope_ttl_seconds": {"platfrom": 10}}}, "cache.scope_ttl_seconds.platfrom"),
    ],
)
def test_unknown_nested_settings_identify_rejected_path(
    settings: dict[str, object],
    path: str,
) -> None:
    with pytest.raises(ValidationError) as excinfo:
        Settings.model_validate(settings)

    assert path in str(excinfo.value).replace("\n", ".")


@pytest.mark.parametrize("provider", ["prometheus", "kube", "unknown"])
def test_platform_source_accepts_only_kueue(provider: str) -> None:
    with pytest.raises(ValidationError, match="kueue"):
        Settings.model_validate({"sources": {"platform": provider}})


@pytest.mark.parametrize(
    "url",
    [
        "http://localhost:8080",
        "https://kubernetes.default.svc",
    ],
)
def test_kueue_api_url_accepts_http_and_https(url: str) -> None:
    assert KueueProviderConfig(kube_api_url=url).kube_api_url == url


@pytest.mark.parametrize(
    "url",
    [
        "ftp://kubernetes.example",
        "file:///var/run/kubernetes.sock",
        "not-a-url",
        "https://",
        "https://kubernetes.example:not-a-port",
        "https://not a host",
    ],
)
def test_kueue_api_url_rejects_malformed_or_unsupported_urls(url: str) -> None:
    with pytest.raises(ValidationError, match="kube_api_url"):
        KueueProviderConfig(kube_api_url=url)


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


def test_kueue_cluster_queues_reject_duplicates_at_settings_boundary() -> None:
    with pytest.raises(ValidationError) as excinfo:
        Settings.model_validate(
            {"providers": {"kueue": {"cluster_queues": ["cq-a", "cq-b", "cq-a"]}}}
        )

    message = str(excinfo.value).replace("\n", ".")
    assert "providers.kueue.cluster_queues" in message
    assert "duplicate ClusterQueue names: cq-a" in message


def test_kueue_cluster_queues_plain_string_not_json_array_rejected() -> None:
    with pytest.raises(ValidationError):
        KueueProviderConfig.model_validate({"cluster_queues": "cq-single"})


def test_kueue_http2_setting_is_rejected() -> None:
    with pytest.raises(ValidationError, match="http2"):
        KueueProviderConfig.model_validate({"http": {"http2": True}})


def test_kueue_provider_config_contract_has_no_cohort_fields() -> None:
    assert "cohort" not in KueueProviderConfig.model_fields
    assert "kube_cohort_path" not in KueueProviderConfig.model_fields


def test_kueue_provider_config_rejects_removed_cohort_field() -> None:
    with pytest.raises(ValidationError, match="cohort"):
        KueueProviderConfig.model_validate({"cluster_queues": ["cq-a"], "cohort": "legacy-cohort"})


@pytest.mark.parametrize("provider", ["PROMETHEUS", "KUBE"])
def test_removed_provider_env_blocks_are_rejected(
    monkeypatch: pytest.MonkeyPatch,
    provider: str,
) -> None:
    monkeypatch.setenv(f"METRICS_PROVIDERS__{provider}__ENABLED", "true")
    with pytest.raises((ValidationError, SettingsError), match=provider.lower()):
        Settings()


def test_cache_scope_ttl_platform_from_dict() -> None:
    cache = CacheConfig.model_validate({"ttl_seconds": 300, "scope_ttl_seconds": {"platform": 120}})
    assert cache.platform_ttl() == 120


def test_cache_scope_ttl_platform_ttl_falls_back_to_global() -> None:
    cache = CacheConfig.model_validate({"ttl_seconds": 300, "scope_ttl_seconds": {}})
    assert cache.platform_ttl() == 300


def test_cache_scope_ttl_rejects_unknown_scope_keys() -> None:
    with pytest.raises(ValidationError):
        CacheConfig.model_validate({"scope_ttl_seconds": {"platform": 60, "unknown_scope": 10}})


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
            CacheConfig.model_validate({"scope_ttl_seconds": bad})


def test_cache_scope_ttl_empty_string_means_no_override() -> None:
    cache = CacheConfig.model_validate({"ttl_seconds": 300, "scope_ttl_seconds": "   "})
    assert cache.platform_ttl() == 300


def test_cache_scope_ttl_non_object_env_rejected(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("METRICS_CACHE__SCOPE_TTL_SECONDS", "300")
    with pytest.raises((ValidationError, SettingsError)):
        Settings()
