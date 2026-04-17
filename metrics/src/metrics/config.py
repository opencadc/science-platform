"""Application configuration loaded from environment variables."""

from __future__ import annotations

from typing import Literal

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Runtime settings for the API service."""

    model_config = SettingsConfigDict(env_prefix="METRICS_", case_sensitive=False)

    app_name: str = "CANFAR Metrics API"
    app_version: str = "v1"
    api_group: str = "metrics.canfar.net"
    environment: Literal["prod", "int", "staging", "dev"] = "dev"
    host: str = "0.0.0.0"
    port: int = 8000

    cluster_name: str = "unknown"
    cache_ttl_seconds: int = Field(default=30, ge=0)
    cache_backend: Literal["memory", "redis"] = "redis"
    redis_url: str = "redis://localhost:6379/0"
    redis_key_prefix: str = "metrics:"
    cache_control_public: bool = True

    otel_metrics_enabled: bool = False
    otel_service_name: str = "canfar-metrics"
    otel_exporter_otlp_endpoint: str | None = None
    otel_export_interval_millis: int = Field(default=60_000, gt=0)

    provider_mode: Literal["live", "static"] = "live"
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
    kube_clusterqueue_path: str = "/apis/kueue.x-k8s.io/v1beta1/clusterqueues"
    kube_nodes_path: str = "/api/v1/nodes"
    node_label_selector: str | None = None

    prometheus_url: str | None = None
    prometheus_verify_tls: bool = True
    prometheus_timeout_seconds: float = Field(default=10.0, gt=0)
    user_label_key: str = "canfar-userid"
    session_label_key: str = "canfar-sessionid"
    resource_requests_metric_name: str = "kube_pod_container_resource_requests"
    promql_requested_cpu_cores: str = (
        "sum(kube_pod_container_resource_requests{resource=\"cpu\",unit=\"core\"})"
    )
    promql_requested_memory_bytes: str = (
        "sum(kube_pod_container_resource_requests{resource=\"memory\",unit=\"byte\"})"
    )
