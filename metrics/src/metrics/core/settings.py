"""Nested runtime settings (12-factor, environment-driven).

Platform metrics are composed from configured sources (Kueue, Prometheus, and a
reserved kube-metrics hook for M4). Legacy flat ``METRICS_*`` keys are merged
into nested models for operator continuity during chart upgrades.
"""

from __future__ import annotations

import json
import os
from typing import Any, Literal

from pydantic import BaseModel, Field, field_validator, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


def _as_dict(value: object) -> dict[str, Any]:
    if isinstance(value, dict):
        return dict(value)
    if isinstance(value, BaseModel):
        return value.model_dump()
    return {}


def _split_cluster_queue_env(raw: str) -> list[str]:
    """Parse comma list or JSON array for cluster queue names."""
    s = raw.strip()
    if not s:
        return []
    if s.startswith("["):
        try:
            parsed = json.loads(s)
            if isinstance(parsed, list):
                return [str(x).strip() for x in parsed if str(x).strip()]
        except json.JSONDecodeError:
            pass
    return [part.strip() for part in s.split(",") if part.strip()]


class PlatformKueueSettings(BaseModel):
    """Kueue + Kubernetes API client configuration for platform sources."""

    model_config = {"extra": "ignore"}

    cluster_queues: list[str] = Field(
        default_factory=list,
        description="ClusterQueue names included in platform aggregation.",
    )
    cohort: str = ""
    kube_api_url: str | None = None
    kube_api_token: str | None = None
    kube_verify_tls: bool = True
    kube_request_timeout_seconds: float = Field(default=10.0, gt=0)
    kube_clusterqueue_path: str = "/apis/kueue.x-k8s.io/v1beta2/clusterqueues"
    kube_cohort_path: str = "/apis/kueue.x-k8s.io/v1beta2/cohorts"

    @field_validator("cluster_queues", mode="before")
    @classmethod
    def _parse_cluster_queue_list(cls, value: object) -> list[str]:
        if value is None or value == "":
            return []
        if isinstance(value, list):
            return [str(item).strip() for item in value if str(item).strip()]
        if isinstance(value, str):
            return _split_cluster_queue_env(value)
        return []


class PlatformPrometheusSettings(BaseModel):
    """Prometheus endpoint and PromQL for cluster-scoped usage queries."""

    model_config = {"extra": "ignore"}

    url: str | None = None
    verify_tls: bool = True
    timeout_seconds: float = Field(default=10.0, gt=0)
    resource_requests_metric_name: str = "kube_pod_container_resource_requests"
    promql_requested_cpu_cores: str = (
        'sum(kube_pod_container_resource_requests{resource="cpu",unit="core"})'
    )
    promql_requested_memory_bytes: str = (
        'sum(kube_pod_container_resource_requests{resource="memory",unit="byte"})'
    )


class PlatformKubeMetricsSettings(BaseModel):
    """Reserved kube-metrics source; runtime depth lands in M4."""

    model_config = {"extra": "ignore"}

    enabled: bool = False

    @model_validator(mode="after")
    def _reject_enabled_until_m4(self) -> PlatformKubeMetricsSettings:
        if self.enabled:
            raise ValueError(
                "METRICS_PLATFORM__KUBE_METRICS__ENABLED is reserved for milestone M4; "
                "leave kube_metrics disabled until kube-metrics runtime ships."
            )
        return self


class PlatformSettings(BaseModel):
    """Configured platform metric sources."""

    model_config = {"extra": "ignore"}

    kueue: PlatformKueueSettings = Field(default_factory=PlatformKueueSettings)
    prometheus: PlatformPrometheusSettings = Field(
        default_factory=PlatformPrometheusSettings
    )
    kube_metrics: PlatformKubeMetricsSettings = Field(
        default_factory=PlatformKubeMetricsSettings
    )


class UserPrometheusSettings(BaseModel):
    """Prometheus label keys for user and session scoped queries."""

    model_config = {"extra": "ignore"}

    user_label_key: str = "canfar-userid"
    session_label_key: str = "canfar-sessionid"


class UserMetricsSettings(BaseModel):
    """User and session metrics configuration (extension path for M5/M6)."""

    model_config = {"extra": "ignore"}

    prometheus: UserPrometheusSettings = Field(default_factory=UserPrometheusSettings)


class Settings(BaseSettings):
    """Runtime settings for the API service."""

    model_config = SettingsConfigDict(
        env_prefix="METRICS_",
        env_nested_delimiter="__",
        case_sensitive=False,
        env_ignore_empty=True,
    )

    app_name: str = "CANFAR Metrics API"
    app_version: str = "v1"
    api_group: str = "metrics.canfar.net"
    environment: Literal["dev", "integration", "staging", "production"] = "dev"

    host: str = "0.0.0.0"
    port: int = 8000

    cluster_name: str = "unknown"
    cache_ttl_seconds: int = Field(default=300, ge=0)
    cache_backend: Literal["memory", "redis"] = "redis"
    redis_url: str = "redis://localhost:6379/0"
    redis_key_prefix: str = "metrics:"
    cache_control_public: bool = True

    otel_metrics_enabled: bool = False
    otel_service_name: str = "canfar-metrics"
    otel_exporter_otlp_endpoint: str | None = None
    otel_export_interval_millis: int = Field(default=60_000, gt=0)

    platform: PlatformSettings = Field(default_factory=PlatformSettings)
    user: UserMetricsSettings = Field(default_factory=UserMetricsSettings)

    @model_validator(mode="before")
    @classmethod
    def _merge_legacy_environment(cls, data: object) -> object:
        """Fold legacy flat ``METRICS_*`` and operator aliases into nested fields.

        ``pydantic-settings`` does not bind unknown top-level env keys to nested
        models, so this runs in ``mode='before'`` and reads ``os.environ``
        directly when nested values are absent.
        """
        if data is None:
            d: dict[str, Any] = {}
        elif isinstance(data, dict):
            d = {str(k): v for k, v in data.items()}
        else:
            return data

        def env(name: str) -> str | None:
            v = os.getenv(name)
            return v if v is not None and str(v).strip() != "" else None

        plat = _as_dict(d.get("platform"))
        kueue = _as_dict(plat.get("kueue"))
        prom = _as_dict(plat.get("prometheus"))
        user = _as_dict(d.get("user"))
        user_p = _as_dict(user.get("prometheus"))

        if not kueue.get("kube_api_url") and env("METRICS_KUBE_API_URL"):
            kueue["kube_api_url"] = env("METRICS_KUBE_API_URL")
        if not kueue.get("kube_api_token") and env("METRICS_KUBE_API_TOKEN"):
            kueue["kube_api_token"] = env("METRICS_KUBE_API_TOKEN")
        if not kueue.get("cluster_queues") and env("METRICS_KUEUE_CLUSTER_QUEUES"):
            kueue["cluster_queues"] = _split_cluster_queue_env(
                env("METRICS_KUEUE_CLUSTER_QUEUES") or ""
            )
        if not kueue.get("cohort") and env("METRICS_KUEUE_COHORT"):
            kueue["cohort"] = env("METRICS_KUEUE_COHORT")

        if not kueue.get("kube_api_url") and env("KUEUE_METRICS_URL"):
            kueue["kube_api_url"] = env("KUEUE_METRICS_URL")
        if not kueue.get("cluster_queues") and env("KUEUE_METRICS_CLUSTER_QUEUES"):
            kueue["cluster_queues"] = _split_cluster_queue_env(
                env("KUEUE_METRICS_CLUSTER_QUEUES") or ""
            )
        if not kueue.get("cohort") and env("KUEUE_METRICS_COHORT"):
            kueue["cohort"] = env("KUEUE_METRICS_COHORT")

        if not prom.get("url") and env("METRICS_PROMETHEUS_URL"):
            prom["url"] = env("METRICS_PROMETHEUS_URL")
        if raw := env("METRICS_PROMETHEUS_TIMEOUT_SECONDS"):
            try:
                prom["timeout_seconds"] = float(raw)
            except ValueError:
                pass
        if raw := env("METRICS_PROMETHEUS_VERIFY_TLS"):
            prom["verify_tls"] = str(raw).strip().lower() in ("1", "true", "yes")
        if raw := env("METRICS_RESOURCE_REQUESTS_METRIC_NAME"):
            prom["resource_requests_metric_name"] = raw
        if raw := env("METRICS_PROMQL_REQUESTED_CPU_CORES"):
            prom["promql_requested_cpu_cores"] = raw
        if raw := env("METRICS_PROMQL_REQUESTED_MEMORY_BYTES"):
            prom["promql_requested_memory_bytes"] = raw

        if "user_label_key" not in user_p and env("METRICS_USER_LABEL_KEY"):
            user_p["user_label_key"] = env("METRICS_USER_LABEL_KEY")
        if "session_label_key" not in user_p and env("METRICS_SESSION_LABEL_KEY"):
            user_p["session_label_key"] = env("METRICS_SESSION_LABEL_KEY")

        plat["kueue"] = kueue
        plat["prometheus"] = prom
        d["platform"] = plat
        user["prometheus"] = user_p
        d["user"] = user
        return d

    @field_validator("environment", mode="before")
    @classmethod
    def _coerce_environment(cls, value: object) -> str:
        if value is None:
            return "dev"
        key = str(value).strip().lower()
        mapping = {
            "dev": "dev",
            "staging": "staging",
            "integration": "integration",
            "int": "integration",
            "production": "production",
            "prod": "production",
        }
        if key not in mapping:
            raise ValueError(
                "METRICS_ENVIRONMENT must be one of: dev, integration, staging, production "
                "(legacy aliases int and prod are still accepted)."
            )
        return mapping[key]
