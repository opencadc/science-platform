"""Runtime settings from the environment (``METRICS_*``, nested ``__``)."""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class KueueProviderConfig(BaseModel):
    """Kueue settings for queue-only platform metrics.

    Kubernetes API discovery (endpoint, credentials, CA trust) is owned by the
    kr8s client, which reads the in-cluster service account or a kubeconfig;
    it is intentionally not configurable here (see ADR-0023).
    """

    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    cluster_queues: list[str] = Field(
        default_factory=list,
        description="ClusterQueue names included in platform aggregation.",
    )
    kueue_api_version: str = "kueue.x-k8s.io/v1beta2"
    kube_request_timeout_seconds: float = Field(default=10.0, gt=0)

    @field_validator("cluster_queues")
    @classmethod
    def _strip_and_reject_duplicates(cls, value: list[str]) -> list[str]:
        """Strip queue names and reject duplicates.

        JSON decoding of nested env values (``METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES``
        must be a JSON array of strings) is handled by pydantic-settings before
        this validator runs.
        """
        names = [str(item).strip() for item in value if str(item).strip()]
        duplicates = sorted({name for name in names if names.count(name) > 1})
        if duplicates:
            raise ValueError(f"duplicate ClusterQueue names: {', '.join(duplicates)}")
        return names


class ProviderConfigs(BaseModel):
    """Container for active upstream provider configuration blocks."""

    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    kueue: KueueProviderConfig = Field(default_factory=KueueProviderConfig)


class SourceConfig(BaseModel):
    """Which provider powers each metric source (platform only for now)."""

    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    platform: Literal["kueue"] = "kueue"


class CacheConfig(BaseModel):
    """TTL cache backend selection."""

    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    backend: Literal["memory", "redis"] = "redis"
    ttl_seconds: int = Field(default=300, ge=0)


class Settings(BaseSettings):
    """Process configuration: defaults overridden by ``METRICS_*`` environment."""

    model_config = SettingsConfigDict(
        env_prefix="METRICS_",
        env_nested_delimiter="__",
        case_sensitive=False,
        env_ignore_empty=True,
    )

    app_name: str = "CANFAR Metrics API"
    app_version: str = "v1"
    api_group: str = "metrics.canfar.net"

    host: str = "0.0.0.0"
    port: int = 8000

    log_level: Literal["critical", "error", "warning", "info", "debug", "trace"] = "info"

    cluster_name: str = "unknown"
    providers: ProviderConfigs = Field(default_factory=ProviderConfigs)
    sources: SourceConfig = Field(default_factory=SourceConfig)
    cache: CacheConfig = Field(default_factory=CacheConfig)

    redis_url: str = "redis://localhost:6379/0"
    redis_key_prefix: str = "metrics:"
    cache_control_public: bool = True

    otel_metrics_enabled: bool = False
    otel_service_name: str = "canfar-metrics"
    otel_exporter_otlp_endpoint: str | None = None
    otel_export_interval_millis: int = Field(default=60_000, gt=0)
