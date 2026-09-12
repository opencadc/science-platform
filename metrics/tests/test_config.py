"""Focused configuration tests for the simplified Kueue source."""

from __future__ import annotations

import pytest
from pydantic import ValidationError
from pydantic_settings.exceptions import SettingsError

from metrics.core.settings import (
    CacheConfig,
    KueueProviderConfig,
    PromQLProviderConfig,
    ProviderConfigs,
    Settings,
)

_CACHE_SECRET = "x" * 32


def _settings(**kueue: object) -> Settings:
    """Build Redis-backed settings with valid static Kueue lists."""
    return Settings(
        cluster_name="cluster-a",
        redis_url="redis://localhost:6379/0",
        cache=CacheConfig(key_secret=_CACHE_SECRET),
        providers=ProviderConfigs(
            kueue=KueueProviderConfig(
                cluster_queues=kueue.get("cluster_queues", ["cq-a"]),
                namespaces=kueue.get("namespaces", ["workloads"]),
            )
        ),
    )


def test_kueue_lists_are_loaded_from_json_environment(monkeypatch: pytest.MonkeyPatch) -> None:
    """Both static Kueue lists use Pydantic Settings JSON parsing."""
    monkeypatch.setenv("METRICS_CLUSTER_NAME", "cluster-a")
    monkeypatch.setenv("METRICS_REDIS_URL", "redis://localhost:6379/0")
    monkeypatch.setenv("METRICS_CACHE__KEY_SECRET", _CACHE_SECRET)
    monkeypatch.setenv("METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES", '["cq-a", "cq-b"]')
    monkeypatch.setenv("METRICS_PROVIDERS__KUEUE__NAMESPACES", '["work-a", "work-b"]')

    settings = Settings()

    assert settings.providers.kueue.cluster_queues == ["cq-a", "cq-b"]
    assert settings.providers.kueue.namespaces == ["work-a", "work-b"]


def test_static_lists_reject_csv_and_empty_values(monkeypatch: pytest.MonkeyPatch) -> None:
    """Malformed and empty source lists fail before runtime startup."""
    monkeypatch.setenv("METRICS_CLUSTER_NAME", "cluster-a")
    monkeypatch.setenv("METRICS_REDIS_URL", "redis://localhost:6379/0")
    monkeypatch.setenv("METRICS_CACHE__KEY_SECRET", _CACHE_SECRET)
    monkeypatch.setenv("METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES", "cq-a,cq-b")
    monkeypatch.setenv("METRICS_PROVIDERS__KUEUE__NAMESPACES", '["work-a"]')
    with pytest.raises((ValidationError, SettingsError)):
        Settings()

    with pytest.raises(ValidationError):
        KueueProviderConfig(cluster_queues=[], namespaces=["work-a"])
    with pytest.raises(ValidationError):
        KueueProviderConfig(cluster_queues=["cq-a"], namespaces=[])


def test_kueue_names_are_normalized_and_unique() -> None:
    """Configured names are trimmed and duplicate populations are rejected."""
    config = KueueProviderConfig(
        cluster_queues=[" cq-a "],
        namespaces=[" work-a "],
    )
    assert config.cluster_queues == ["cq-a"]
    assert config.namespaces == ["work-a"]

    with pytest.raises(ValidationError, match="duplicate"):
        KueueProviderConfig(cluster_queues=["cq-a", "cq-a"], namespaces=["work-a"])
    with pytest.raises(ValidationError, match="duplicate"):
        KueueProviderConfig(cluster_queues=["cq-a"], namespaces=["work-a", "work-a"])


@pytest.mark.parametrize("name", ["CQ-upper", "work/a", "work_underscore", "work-"])
def test_kueue_names_use_kubernetes_grammars(name: str) -> None:
    """ClusterQueues and namespaces reject names outside their path grammars."""
    with pytest.raises(ValidationError):
        KueueProviderConfig(cluster_queues=[name], namespaces=["work-a"])
    with pytest.raises(ValidationError):
        KueueProviderConfig(cluster_queues=["cq-a"], namespaces=[name])


def test_unknown_provider_and_source_keys_are_rejected() -> None:
    """The service has no alternate Pod/Kubernetes source configuration."""
    with pytest.raises(ValidationError):
        Settings.model_validate(
            {
                "cluster_name": "cluster-a",
                "redis_url": "redis://localhost:6379/0",
                "cache": {"key_secret": _CACHE_SECRET},
                "providers": {
                    "kueue": {"cluster_queues": ["cq-a"], "namespaces": ["work-a"]},
                    "kubernetes": {},
                },
            }
        )


def test_promql_endpoint_alone_controls_efficiency_activation() -> None:
    """PromQL has no second switch or synthetic in-cluster endpoint."""
    assert PromQLProviderConfig().base_url is None
    configured = PromQLProviderConfig(base_url="https://mimir.example/api/prom")
    assert str(configured.base_url) == "https://mimir.example/api/prom"
    with pytest.raises(ValidationError):
        PromQLProviderConfig.model_validate(
            {"enabled": True, "base_url": "https://mimir.example/api/prom"}
        )


def test_cache_secret_and_cluster_name_are_mandatory() -> None:
    """Redis integrity and cache identity inputs cannot use production defaults."""
    assert _settings().cluster_name == "cluster-a"
    assert _settings().redis_url == "redis://localhost:6379/0"
    assert _settings().cache.key_secret.get_secret_value() == _CACHE_SECRET
    with pytest.raises(ValidationError):
        Settings(
            redis_url="redis://localhost:6379/0",
            cache=CacheConfig(key_secret=_CACHE_SECRET),
            providers=ProviderConfigs(
                kueue=KueueProviderConfig(cluster_queues=["cq-a"], namespaces=["work-a"])
            ),
        )
    with pytest.raises(ValidationError):
        Settings(
            cluster_name="cluster-a",
            cache=CacheConfig(key_secret=_CACHE_SECRET),
            providers=ProviderConfigs(
                kueue=KueueProviderConfig(cluster_queues=["cq-a"], namespaces=["work-a"])
            ),
        )
    with pytest.raises(ValidationError):
        CacheConfig()
    with pytest.raises(ValidationError, match="at least 32"):
        CacheConfig(key_secret="short")
    with pytest.raises(ValidationError):
        CacheConfig.model_validate({"backend": "memory", "key_secret": _CACHE_SECRET})


def test_cluster_name_requires_lowercase_dns() -> None:
    """The cluster identity remains a required lower-case DNS name."""
    with pytest.raises(ValidationError):
        Settings(
            cluster_name="Cluster-A",
            redis_url="redis://localhost:6379/0",
            cache=CacheConfig(key_secret=_CACHE_SECRET),
            providers=ProviderConfigs(
                kueue=KueueProviderConfig(cluster_queues=["cq-a"], namespaces=["work-a"])
            ),
        )
