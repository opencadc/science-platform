"""Runtime settings: YAML file, then environment (METRICS_*, nested __)."""

from __future__ import annotations

import json
import logging
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator
from pydantic_settings import (
    BaseSettings,
    PydanticBaseSettingsSource,
    SettingsConfigDict,
)

from metrics.core.yaml_config import MetricsYamlSettingsSource

# --- Application config tree (typed providers, sources, cache) ---


class HttpClientConfig(BaseModel):
    """Connection pool and HTTP/2 options for upstream httpx clients."""

    model_config = ConfigDict(extra="ignore", populate_by_name=True)

    max_connections: int = Field(default=100, ge=1)
    max_keepalive_connections: int = Field(default=20, ge=1)
    keepalive_expiry_seconds: float = Field(default=30.0, gt=0)
    http2: bool = False


class KueueProviderConfig(BaseModel):
    """Kueue-related settings: ClusterQueues, cohort, and Kubernetes API access."""

    model_config = ConfigDict(extra="ignore", populate_by_name=True)

    cluster_queues: list[str] = Field(
        default_factory=list,
        description="ClusterQueue names included in platform aggregation.",
    )
    cohort: str = ""
    kube_api_url: str | None = None
    kube_api_token: str | None = None
    token_file: str | None = None
    ca_file: str | None = None
    kube_verify_tls: bool = True
    kube_request_timeout_seconds: float = Field(default=10.0, gt=0)
    kube_clusterqueue_path: str = "/apis/kueue.x-k8s.io/v1beta2/clusterqueues"
    kube_cohort_path: str = "/apis/kueue.x-k8s.io/v1beta2/cohorts"
    http: HttpClientConfig = Field(default_factory=HttpClientConfig)

    @field_validator("cluster_queues", mode="before")
    @classmethod
    def _parse_cluster_queue_list(cls, value: object) -> list[str]:
        """Normalize ``cluster_queues`` from YAML, env, or kwargs.

        Nested env and string inputs must be a JSON array of strings. Comma-separated
        plain strings are not accepted.

        Args:
            value: Raw list, JSON array string, or empty.

        Returns:
            Stripped queue names (possibly empty).

        Raises:
            TypeError: If the decoded JSON value is not a list.
            ValueError: If a non-empty string is not valid JSON or does not decode to a list.
        """
        if value is None or value == "":
            return []
        if isinstance(value, list):
            return [str(item).strip() for item in value if str(item).strip()]
        if isinstance(value, str):
            stripped = value.strip()
            if not stripped:
                return []
            try:
                parsed: Any = json.loads(stripped)
            except json.JSONDecodeError as exc:
                msg = (
                    "cluster_queues must be a JSON array of strings "
                    f"(nested env uses JSON only): {exc}"
                )
                raise ValueError(msg) from exc
            if not isinstance(parsed, list):
                msg = "cluster_queues must be a JSON array of strings (nested env uses JSON only)"
                raise TypeError(msg)
            return [str(x).strip() for x in parsed if str(x).strip()]
        msg = f"cluster_queues must be a list or JSON array string, got {type(value).__name__}"
        raise TypeError(msg)


class PrometheusProviderConfig(BaseModel):
    """Prometheus provider settings (reserved for future metric scopes)."""

    model_config = ConfigDict(extra="ignore", populate_by_name=True)

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
    http: HttpClientConfig = Field(default_factory=HttpClientConfig)


class KubeProviderConfig(BaseModel):
    """Reserved for future kube-metrics; must remain disabled for now."""

    model_config = ConfigDict(extra="ignore", populate_by_name=True)

    enabled: bool = False

    @model_validator(mode="after")
    def _kube_must_stay_disabled(self) -> KubeProviderConfig:
        if self.enabled:
            raise ValueError("METRICS_PROVIDERS__KUBE is reserved; leave kube provider disabled")
        return self


class ProviderConfigs(BaseModel):
    """Container for all optional upstream provider configuration blocks."""

    model_config = ConfigDict(extra="ignore", populate_by_name=True)

    kueue: KueueProviderConfig = Field(default_factory=KueueProviderConfig)
    prometheus: PrometheusProviderConfig = Field(default_factory=PrometheusProviderConfig)
    kube: KubeProviderConfig = Field(default_factory=KubeProviderConfig)


class SourceConfig(BaseModel):
    """Which provider powers each metric source (platform only for now)."""

    model_config = ConfigDict(extra="ignore", populate_by_name=True)

    platform: str = "kueue"


class ScopeTTLConfig(BaseModel):
    """Explicit per-scope cache TTL overrides (extend with new fields when scopes are added)."""

    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    platform: int | None = Field(default=None, ge=0)


class CacheConfig(BaseModel):
    """TTL cache backend and optional per-scope overrides."""

    model_config = ConfigDict(extra="ignore", populate_by_name=True)

    backend: Literal["memory", "redis"] = "redis"
    ttl_seconds: int = Field(default=300, ge=0)
    scope_ttl_seconds: ScopeTTLConfig = Field(default_factory=ScopeTTLConfig)

    @field_validator("scope_ttl_seconds", mode="before")
    @classmethod
    def _parse_scope_ttls(cls, value: object) -> Any:
        """Coerce dict or JSON string inputs into :class:`ScopeTTLConfig`.

        Rejects unknown scope keys (``extra="forbid"`` on :class:`ScopeTTLConfig`).
        Non-empty string values must be valid JSON that decodes to an object (mapping);
        bare numbers, arrays, or opaque text are rejected.

        Args:
            value: Raw ``scope_ttl_seconds`` from YAML, env, or kwargs.

        Returns:
            A dict for Pydantic to build :class:`ScopeTTLConfig`, or an existing model.

        Raises:
            ValueError: If a non-empty string is not valid JSON, JSON is not an object,
                or the value is not a supported input type.
        """
        if value is None:
            return {}
        if isinstance(value, ScopeTTLConfig):
            return value
        if isinstance(value, str):
            stripped = value.strip()
            if not stripped:
                return {}
            try:
                parsed: Any = json.loads(stripped)
            except json.JSONDecodeError as exc:
                msg = (
                    "scope_ttl_seconds must be a JSON object mapping scope names to integers "
                    f"(nested env uses JSON only): {exc}"
                )
                raise ValueError(msg) from exc
            if not isinstance(parsed, dict):
                msg = (
                    "scope_ttl_seconds must be a JSON object mapping scope names to integers "
                    "(nested env uses JSON only)"
                )
                raise ValueError(msg)
            return parsed
        if isinstance(value, dict):
            return {str(name): amount for name, amount in value.items()}
        if isinstance(value, (int, float, bool, list)):
            msg = (
                "scope_ttl_seconds must be a mapping or a JSON object string "
                f"(nested env uses JSON only), not {type(value).__name__}"
            )
            raise ValueError(msg)
        msg = (
            f"scope_ttl_seconds must be a mapping or JSON object string, got {type(value).__name__}"
        )
        raise ValueError(msg)

    def platform_ttl(self) -> int:
        """Return TTL for the ``platform`` scope, falling back to the global default."""
        override = self.scope_ttl_seconds.platform
        return self.ttl_seconds if override is None else override


# --- Top-level app settings (12-factor) ---


class Settings(BaseSettings):
    """Process configuration: defaults, optional YAML, then environment."""

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

    @classmethod
    def settings_customise_sources(
        cls,
        settings_cls: type[BaseSettings],
        init_settings: PydanticBaseSettingsSource,
        env_settings: PydanticBaseSettingsSource,
        dotenv_settings: PydanticBaseSettingsSource,
        file_secret_settings: PydanticBaseSettingsSource,
    ) -> tuple[PydanticBaseSettingsSource, ...]:
        """Order settings sources so environment overrides optional YAML and secrets.

        Order matches pydantic merge: earlier source inputs have higher priority than
        later. Place env before the YAML file so `METRICS_*` overrides
        ``/etc/canfar/metrics/config.yaml`` (see :file:`core/yaml_config.py`).

        Args:
            settings_cls: The settings model class.
            init_settings: Constructor keyword arguments.
            env_settings: ``METRICS_*`` environment variables.
            dotenv_settings: Not included in the tuple; reserved if ``env_file`` is used
                later.
            file_secret_settings: Optional Docker/Kubernetes secret files.

        Returns:
            The ordered list of active sources. ``dotenv`` is omitted unless added
            explicitly when loading ``.env`` is enabled.
        """
        return (
            init_settings,
            env_settings,
            MetricsYamlSettingsSource(settings_cls),
            file_secret_settings,
        )

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


_METRICS_LOG_LEVELS: dict[str, int] = {
    "critical": logging.CRITICAL,
    "error": logging.ERROR,
    "warning": logging.WARNING,
    "info": logging.INFO,
    "debug": logging.DEBUG,
    "trace": logging.DEBUG,
}


def apply_metrics_package_log_level(settings: Settings) -> None:
    """Set the ``metrics`` logger tree level from :attr:`Settings.log_level`."""
    key = str(settings.log_level).strip().lower()
    level = _METRICS_LOG_LEVELS.get(key, logging.INFO)
    logging.getLogger("metrics").setLevel(level)
