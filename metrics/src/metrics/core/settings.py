"""Runtime settings from the environment (``METRICS_*``, nested ``__``)."""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, SecretStr, field_validator, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class KueueProviderConfig(BaseModel):
    """Kueue settings for queue-only platform metrics.

    Kubernetes API discovery (endpoint, credentials, CA trust) is owned by the
    kr8s client, which reads the in-cluster service account or a kubeconfig;
    it is intentionally not configurable here (see ADR-0001).
    """

    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    cluster_queues: list[str] = Field(
        default_factory=list,
        description="ClusterQueue names included in platform aggregation.",
    )
    kueue_api_version: str = "kueue.x-k8s.io/v1beta2"
    kube_request_timeout_seconds: float = Field(default=5.0, gt=0)

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


class KubernetesProviderConfig(BaseModel):
    """Kubernetes settings for namespaced Running workload observations."""

    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    workload_namespaces: list[str] = Field(
        default_factory=list,
        description="Namespaces queried completely for subject workloads.",
    )
    kube_request_timeout_seconds: float = Field(default=5.0, gt=0)

    @field_validator("workload_namespaces")
    @classmethod
    def _strip_and_reject_duplicates(cls, value: list[str]) -> list[str]:
        """Normalize namespaces and reject duplicates."""
        names = [str(item).strip() for item in value if str(item).strip()]
        duplicates = sorted({name for name in names if names.count(name) > 1})
        if duplicates:
            raise ValueError(f"duplicate workload namespaces: {', '.join(duplicates)}")
        return names


class ProviderConfigs(BaseModel):
    """Container for active upstream provider configuration blocks."""

    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    kueue: KueueProviderConfig = Field(default_factory=KueueProviderConfig)
    kubernetes: KubernetesProviderConfig = Field(default_factory=KubernetesProviderConfig)


class SourceConfig(BaseModel):
    """Which provider powers each metric source."""

    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    platform: Literal["kueue"] = "kueue"
    user: Literal["kubernetes"] = "kubernetes"


class CacheConfig(BaseModel):
    """Required cache dependency, deadlines, and bounded local fallback."""

    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    backend: Literal["memory", "redis"] = "redis"
    key_secret: SecretStr | None = None
    redis_command_timeout_seconds: float = Field(default=0.5, gt=0)
    fill_timeout_seconds: float = Field(default=10.0, gt=0)
    cold_get_timeout_seconds: float = Field(default=12.0, gt=0)
    l1_max_entries: int = Field(default=128, gt=0)
    max_fills: int = Field(default=8, gt=0)

    @model_validator(mode="after")
    def _require_redis_secret(self) -> CacheConfig:
        if self.backend == "redis":
            secret = self.key_secret.get_secret_value() if self.key_secret else ""
            if len(secret.encode("utf-8")) < 32:
                raise ValueError("key_secret must contain at least 32 UTF-8 bytes")
        return self


class OTelConfig(BaseModel):
    """OpenTelemetry signal controls and bounded resource identity."""

    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    metrics_enabled: bool = False
    traces_enabled: bool = False
    logs_enabled: bool = False
    exporter_otlp_endpoint: str | None = None
    service_name: str = "canfar-metrics"
    export_interval_millis: int = Field(default=60_000, gt=0)
    deployment_environment: str = "unknown"
    kubernetes_namespace: str = "unknown"
    pod_uid: str = "unknown"

    @model_validator(mode="after")
    def _require_endpoint_when_enabled(self) -> OTelConfig:
        if (
            self.metrics_enabled or self.traces_enabled or self.logs_enabled
        ) and not self.exporter_otlp_endpoint:
            raise ValueError("exporter_otlp_endpoint is required when an OTel signal is enabled")
        return self


class Settings(BaseSettings):
    """Process configuration: defaults overridden by ``METRICS_*`` environment."""

    model_config = SettingsConfigDict(
        env_prefix="METRICS_",
        env_nested_delimiter="__",
        case_sensitive=False,
        env_ignore_empty=True,
    )

    app_name: str = "CANFAR Metrics API"
    app_version: str = "v1alpha1"

    host: str = "0.0.0.0"
    port: int = 8000

    log_level: Literal["critical", "error", "warning", "info", "debug", "trace"] = "info"

    cluster_name: str = "unknown"
    providers: ProviderConfigs = Field(default_factory=ProviderConfigs)
    sources: SourceConfig = Field(default_factory=SourceConfig)
    cache: CacheConfig = Field(default_factory=CacheConfig)
    otel: OTelConfig = Field(default_factory=OTelConfig)

    redis_url: str = "redis://localhost:6379/0"
    redis_key_prefix: str = "metrics:"
