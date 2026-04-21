"""Application configuration loaded from environment variables (12-factor).

All tunables are exposed through :class:`Settings` with the ``METRICS_`` prefix
by default, plus documented operator aliases (``KUEUE_METRICS_*``) merged in
via :meth:`Settings._apply_operator_kueue_env_aliases`.

Environment tokens ``dev``, ``integration``, ``staging``, and ``production`` are
canonical; legacy ``int`` / ``prod`` inputs are normalized for backward
compatibility with older charts.
"""

from __future__ import annotations

import json
import os
from typing import Any, Literal

from pydantic import Field, field_validator, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


def _split_cluster_queue_env(raw: str) -> list[str]:
    """Parse ``METRICS_KUEUE_CLUSTER_QUEUES`` / operator alias (comma list or JSON array)."""
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


class Settings(BaseSettings):
    """Runtime settings for the API service.

    Fields map from environment variables using ``METRICS_<FIELD>`` naming
    (case-insensitive), except where :class:`pydantic.Field` aliases or the
    Kueue operator-alias validator applies. Unknown env keys are ignored unless
    ``extra`` policy changes upstream in pydantic-settings.
    """

    model_config = SettingsConfigDict(env_prefix="METRICS_", case_sensitive=False)

    app_name: str = "CANFAR Metrics API"
    app_version: str = "v1"
    api_group: str = "metrics.canfar.net"
    environment: Literal["dev", "integration", "staging", "production"] = "dev"

    @field_validator("environment", mode="before")
    @classmethod
    def _coerce_environment(cls, value: object) -> str:
        """Normalize METRICS_ENVIRONMENT; accept legacy ``int`` / ``prod`` aliases."""
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

    provider_mode: Literal["static", "kueue"] = "static"

    @field_validator("provider_mode", mode="before")
    @classmethod
    def _coerce_legacy_provider_mode(cls, value: object) -> str:
        """Map removed ``live`` mode to ``kueue`` for chart upgrades (M2 closure)."""
        if value is None:
            return "static"
        key = str(value).strip().lower()
        if key == "live":
            return "kueue"
        return str(value).strip().lower()

    static_capacity_cpu_cores: float = Field(default=100.0, ge=0)
    static_capacity_memory_gib: float = Field(default=512.0, ge=0)
    static_usage_cpu_cores: float = Field(default=25.0, ge=0)
    static_usage_memory_gib: float = Field(default=128.0, ge=0)
    static_user_usage_fraction: float = Field(default=0.1, ge=0, le=1)
    static_session_usage_fraction: float = Field(default=0.05, ge=0, le=1)

    kube_api_url: str | None = None
    kube_api_token: str | None = None
    kube_verify_tls: bool = True
    kube_request_timeout_seconds: float = Field(default=10.0, gt=0)
    kube_clusterqueue_path: str = "/apis/kueue.x-k8s.io/v1beta2/clusterqueues"
    kube_cohort_path: str = "/apis/kueue.x-k8s.io/v1beta2/cohorts"
    kube_nodes_path: str = "/api/v1/nodes"
    node_label_selector: str | None = None

    # str | list so pydantic-settings does not require JSON for list-typed env vars
    # (comma-separated values are common in Helm; see _split_cluster_queue_env).
    kueue_cluster_queues: str | list[str] = ""
    kueue_cohort: str = ""

    @field_validator("kueue_cluster_queues", mode="before")
    @classmethod
    def _parse_cluster_queue_list(cls, value: object) -> list[str]:
        if value is None or value == "":
            return []
        if isinstance(value, list):
            return [str(item).strip() for item in value if str(item).strip()]
        if isinstance(value, str):
            return _split_cluster_queue_env(value)
        return []

    @model_validator(mode="after")
    def _apply_operator_kueue_env_aliases(self) -> Settings:
        """Support operator-facing KUEUE_METRICS_* env vars alongside METRICS_*."""
        updates: dict[str, Any] = {}
        if not self.kube_api_url and os.getenv("KUEUE_METRICS_URL"):
            updates["kube_api_url"] = os.environ["KUEUE_METRICS_URL"]
        if not self.kueue_cluster_queues and os.getenv("KUEUE_METRICS_CLUSTER_QUEUES"):
            raw = os.environ["KUEUE_METRICS_CLUSTER_QUEUES"]
            updates["kueue_cluster_queues"] = _split_cluster_queue_env(raw)
        if not self.kueue_cohort and os.getenv("KUEUE_METRICS_COHORT"):
            updates["kueue_cohort"] = os.environ["KUEUE_METRICS_COHORT"]
        return self.model_copy(update=updates) if updates else self

    prometheus_url: str | None = None
    prometheus_verify_tls: bool = True
    prometheus_timeout_seconds: float = Field(default=10.0, gt=0)
    user_label_key: str = "canfar-userid"
    session_label_key: str = "canfar-sessionid"
    resource_requests_metric_name: str = "kube_pod_container_resource_requests"
    promql_requested_cpu_cores: str = (
        'sum(kube_pod_container_resource_requests{resource="cpu",unit="core"})'
    )
    promql_requested_memory_bytes: str = (
        'sum(kube_pod_container_resource_requests{resource="memory",unit="byte"})'
    )
