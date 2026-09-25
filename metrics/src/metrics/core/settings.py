"""Parse and validate runtime configuration from ``METRICS_*`` variables."""

from __future__ import annotations

import ipaddress
import re
import socket
from collections.abc import Mapping
from collections import Counter
from typing import Literal
from urllib.parse import urlsplit

from pydantic import (
    AnyHttpUrl,
    BaseModel,
    ConfigDict,
    Field,
    SecretStr,
    field_validator,
    model_validator,
)
from pydantic_settings import BaseSettings, SettingsConfigDict


_DNS_LABEL_PATTERN = re.compile(r"^[a-z0-9](?:[-a-z0-9]{0,61}[a-z0-9])?$")
_HOST_DNS_LABEL = r"[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"
_HOST_PATTERN = re.compile(rf"{_HOST_DNS_LABEL}(?:\.{_HOST_DNS_LABEL})*")
_PLATFORM_NAME_PATTERN = re.compile(r"^[A-Za-z0-9](?:[-A-Za-z0-9_.]{0,61}[A-Za-z0-9])?$")
_REDIS_DATABASE_PATTERN = re.compile(r"^/[0-9]+$")

_PLACEHOLDER_SECRETS = frozenset(
    {"replace-with-at-least-32-random-bytes", "<secret-reference-or-injected-value>"}
)
LEASE_MARGIN_SECONDS = 2.0
"""Slack added to a fill lease for event-loop stalls; see ``RedisCoordinator``."""

SHORTEST_STALE_WINDOW_SECONDS = 60.0
"""Shortest stale window in ``FRESHNESS_POLICIES`` (Session)."""

MAX_CLUSTER_QUEUES = 256
MAX_NAMESPACES = 256
_MAX_REDIS_URL_LENGTH = 512
_MAX_REDIS_DB = 2_147_483_647
_MAX_HOST_LENGTH = 253
_MAX_OTEL_ENDPOINT_LENGTH = 512
_MAX_OTEL_SERVICE_NAME_LENGTH = 128
_MAX_OTEL_ENVIRONMENT_LENGTH = 63
_MAX_OTEL_NAMESPACE_LENGTH = 63
_MAX_OTEL_POD_UID_LENGTH = 128
_MAX_KUBE_REQUEST_TIMEOUT_SECONDS = 300.0
_MAX_PROMQL_REQUEST_TIMEOUT_SECONDS = 300.0
_MAX_PROMQL_SAMPLE_AGE_SECONDS = 7 * 24 * 60 * 60
_MAX_PROMQL_FUTURE_TOLERANCE_SECONDS = 60 * 60
_MAX_REDIS_COMMAND_TIMEOUT_SECONDS = 30.0
_MAX_CACHE_FILL_TIMEOUT_SECONDS = 300.0
_MAX_CACHE_COLD_GET_TIMEOUT_SECONDS = 600.0
_MAX_CACHE_L1_ENTRIES = 10_000
_MAX_CACHE_FAILURE_COOLDOWN_SECONDS = 60.0
_MAX_OTEL_EXPORT_INTERVAL_MILLIS = 60 * 60 * 1000
_MAX_STARTUP_VALIDATION_TIMEOUT_SECONDS = 300.0
_PROMQL_BASE_PATH_MAX_LENGTH = 128
_PROMQL_DEFAULT_MAX_RESPONSE_BYTES = 4 * 1024 * 1024
_PROMQL_HARD_MAX_RESPONSE_BYTES = 16 * 1024 * 1024


def _canonical_text(
    value: str,
    *,
    field_name: str,
    max_length: int | None = None,
    ascii_only: bool = False,
) -> str:
    """Normalize one configured identifier and reject unsafe delimiters."""
    normalized = value.strip()
    if not normalized:
        raise ValueError(f"{field_name} must not be empty")
    if any(
        character.isspace() or ord(character) < 32 or ord(character) == 127
        for character in normalized
    ):
        raise ValueError(f"{field_name} must not contain whitespace or control characters")
    if ascii_only and any(ord(character) > 127 for character in normalized):
        raise ValueError(f"{field_name} must contain printable ASCII characters")
    if max_length is not None and len(normalized) > max_length:
        raise ValueError(f"{field_name} must be at most {max_length} characters")
    return normalized


def _is_dns_subdomain(value: str) -> bool:
    """Return whether a value follows Kubernetes DNS subdomain naming rules."""
    return len(value) <= 253 and all(
        _DNS_LABEL_PATTERN.fullmatch(part) for part in value.split(".")
    )


def _validate_dns_or_ip_host(value: str, *, field_name: str) -> str:
    """Require a bounded DNS host or literal IP address."""
    if len(value) > _MAX_HOST_LENGTH:
        raise ValueError(f"{field_name} must be a bounded host name or IP address")
    try:
        ipaddress.ip_address(value)
    except ValueError:
        labels = value.split(".")
        if (
            _HOST_PATTERN.fullmatch(value) is None
            or len(labels) > 1
            and all(label.isdigit() for label in labels)
        ):
            raise ValueError(f"{field_name} must be a bounded host name or IP address") from None
    return value


def _normalize_kubernetes_names(
    value: list[str],
    *,
    field_name: str,
    grammar: str,
    subdomain: bool,
) -> list[str]:
    """Normalize and validate names used in Kubernetes requests."""
    names: list[str] = []
    for item in value:
        name = item.strip()
        if not name:
            raise ValueError(f"{field_name} must not contain empty names")
        valid = _is_dns_subdomain(name) if subdomain else bool(_DNS_LABEL_PATTERN.fullmatch(name))
        if not valid:
            raise ValueError(f"{field_name} must use Kubernetes {grammar} names")
        names.append(name)
    duplicates = sorted(name for name, count in Counter(names).items() if count > 1)
    if duplicates:
        raise ValueError(f"duplicate {field_name}: {', '.join(duplicates)}")
    return names


def _validate_redis_url(value: str) -> str:
    """Validate a Redis URL without copying credentials into errors."""
    normalized = value.strip()
    if not normalized or len(normalized) > _MAX_REDIS_URL_LENGTH:
        raise ValueError("redis_url must be a bounded non-empty URL")
    if any(ord(character) <= 32 or ord(character) == 127 for character in normalized):
        raise ValueError("redis_url must not contain control characters")
    if normalized.split(":", 1)[0] not in {"redis", "rediss"}:
        raise ValueError("redis_url scheme must be redis or rediss")
    try:
        parsed = urlsplit(normalized)
        port = parsed.port
    except ValueError as exc:
        raise ValueError("redis_url must contain a valid host and port") from exc
    if not parsed.hostname:
        raise ValueError("redis_url must contain a host")
    _validate_dns_or_ip_host(parsed.hostname, field_name="redis_url host")
    if port is not None and not 1 <= port <= 65535:
        raise ValueError("redis_url port must be between 1 and 65535")
    if parsed.path not in {"", "/"}:
        if (
            not _REDIS_DATABASE_PATTERN.fullmatch(parsed.path)
            or int(parsed.path[1:]) > _MAX_REDIS_DB
        ):
            raise ValueError("redis_url database is outside its bound")
    if parsed.query or parsed.fragment:
        raise ValueError("redis_url must not contain a query or fragment")
    return normalized


def _validate_otel_endpoint(value: str) -> str:
    """Require one bounded OTLP/HTTP destination."""
    normalized = value.strip()
    if not normalized or len(normalized) > _MAX_OTEL_ENDPOINT_LENGTH:
        raise ValueError("exporter_otlp_endpoint must be bounded and non-empty")
    if any(
        character.isspace() or ord(character) < 32 or ord(character) == 127
        for character in normalized
    ):
        raise ValueError("exporter_otlp_endpoint must not contain whitespace or controls")
    try:
        parsed = urlsplit(normalized)
        port = parsed.port
    except ValueError as exc:
        raise ValueError("exporter_otlp_endpoint must contain a valid host and port") from exc
    if parsed.scheme.lower() not in {"http", "https"} or not parsed.hostname:
        raise ValueError("exporter_otlp_endpoint must use http(s) with a host")
    if (
        parsed.username is not None
        or parsed.password is not None
        or parsed.query
        or parsed.fragment
    ):
        raise ValueError("exporter_otlp_endpoint must not contain credentials, query, or fragment")
    if port is not None and not 1 <= port <= 65535:
        raise ValueError("exporter_otlp_endpoint must contain a bounded port")
    return normalized


class KueueProviderConfig(BaseModel):
    """Configure the sole Kueue source used by all Metrics surfaces."""

    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    cluster_queues: list[str] = Field(
        min_length=1,
        max_length=MAX_CLUSTER_QUEUES,
        description="Configured ClusterQueue names included in Platform metrics.",
    )
    namespaces: list[str] = Field(
        min_length=1,
        max_length=MAX_NAMESPACES,
        description="Namespaces searched for LocalQueues, Session Jobs and Pods, and PodMetrics.",
    )
    kueue_api_version: Literal["kueue.x-k8s.io/v1beta2"] = "kueue.x-k8s.io/v1beta2"
    kube_request_timeout_seconds: float = Field(
        default=5.0, gt=0, le=_MAX_KUBE_REQUEST_TIMEOUT_SECONDS
    )

    @field_validator("cluster_queues")
    @classmethod
    def _normalize_cluster_queues(cls, value: list[str]) -> list[str]:
        """Normalize configured ClusterQueue names."""
        return _normalize_kubernetes_names(
            value,
            field_name="ClusterQueue names",
            grammar="DNS subdomain",
            subdomain=True,
        )

    @field_validator("namespaces")
    @classmethod
    def _normalize_namespaces(cls, value: list[str]) -> list[str]:
        """Normalize configured LocalQueue namespaces."""
        return _normalize_kubernetes_names(
            value,
            field_name="Kueue namespaces",
            grammar="DNS label",
            subdomain=False,
        )


class PromQLProviderConfig(BaseModel):
    """Bound optional Prometheus-compatible efficiency adapter settings.

    The base URL is the activation switch. A missing endpoint leaves the
    efficiency adapter disabled; no separate boolean or in-cluster default is
    used.
    """

    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    base_url: AnyHttpUrl | None = None
    request_timeout_seconds: float = Field(
        default=5.0, gt=0, le=_MAX_PROMQL_REQUEST_TIMEOUT_SECONDS
    )
    max_sample_age_seconds: int = Field(default=300, gt=0, le=_MAX_PROMQL_SAMPLE_AGE_SECONDS)
    future_sample_tolerance_seconds: int = Field(
        default=30, ge=0, le=_MAX_PROMQL_FUTURE_TOLERANCE_SECONDS
    )
    max_response_bytes: int = Field(
        default=_PROMQL_DEFAULT_MAX_RESPONSE_BYTES,
        gt=0,
        le=_PROMQL_HARD_MAX_RESPONSE_BYTES,
    )
    mimir_tenant_id: str | None = Field(default=None, min_length=1, max_length=150)

    @field_validator("base_url")
    @classmethod
    def _validate_base_url(cls, value: AnyHttpUrl | None) -> AnyHttpUrl | None:
        """Allow only an HTTP(S) origin with a bounded path prefix."""
        if value is None:
            return None
        parsed = urlsplit(str(value))
        if parsed.scheme not in {"http", "https"} or not parsed.hostname:
            raise ValueError("base_url must contain an http(s) host")
        if (
            parsed.username is not None
            or parsed.password is not None
            or parsed.query
            or parsed.fragment
        ):
            raise ValueError("base_url must not contain credentials, query, or fragment")
        if len(parsed.path or "/") > _PROMQL_BASE_PATH_MAX_LENGTH:
            raise ValueError("base_url path prefix is too long")
        return value


class ProviderConfigs(BaseModel):
    """Group the Kueue source and optional efficiency settings."""

    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    kueue: KueueProviderConfig
    promql: PromQLProviderConfig = Field(default_factory=PromQLProviderConfig)


class CacheConfig(BaseModel):
    """Configure mandatory shared Redis integrity and bounded local fallback."""

    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    key_secret: SecretStr
    redis_command_timeout_seconds: float = Field(
        default=0.5, gt=0, le=_MAX_REDIS_COMMAND_TIMEOUT_SECONDS
    )
    fill_timeout_seconds: float = Field(default=10.0, gt=0, le=_MAX_CACHE_FILL_TIMEOUT_SECONDS)
    cold_get_timeout_seconds: float = Field(
        default=15.0, gt=0, le=_MAX_CACHE_COLD_GET_TIMEOUT_SECONDS
    )
    l1_max_entries: int = Field(default=128, gt=0, le=_MAX_CACHE_L1_ENTRIES)
    failure_cooldown_seconds: float = Field(
        default=5.0, gt=0, le=_MAX_CACHE_FAILURE_COOLDOWN_SECONDS
    )

    @property
    def lease_seconds(self) -> float:
        """Return the fill lease: one source call plus two Redis commands and a margin."""
        return (
            self.fill_timeout_seconds
            + 2 * self.redis_command_timeout_seconds
            + LEASE_MARGIN_SECONDS
        )

    @model_validator(mode="after")
    def _validate_cache_contract(self) -> CacheConfig:
        """Require Redis integrity and deadlines that let followers take over a lease."""
        secret = self.key_secret.get_secret_value()
        if len(secret.encode()) < 32:
            raise ValueError("key_secret must contain at least 32 UTF-8 bytes")
        if secret in _PLACEHOLDER_SECRETS or len(set(secret)) == 1:
            raise ValueError(
                "key_secret is a placeholder; generate one with "
                "python -c 'import secrets; print(secrets.token_urlsafe(32))'"
            )
        if self.cold_get_timeout_seconds <= self.lease_seconds:
            raise ValueError(
                "cold_get_timeout_seconds must exceed the fill lease "
                "(fill_timeout_seconds + 2 * redis_command_timeout_seconds + 2)"
            )
        if self.lease_seconds >= SHORTEST_STALE_WINDOW_SECONDS:
            raise ValueError("fill_timeout_seconds must leave the shortest stale window usable")
        return self


class OTelConfig(BaseModel):
    """Control exported OpenTelemetry metrics and application identity."""

    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    metrics_enabled: bool = False
    exporter_otlp_endpoint: str | None = Field(default=None, max_length=_MAX_OTEL_ENDPOINT_LENGTH)
    service_name: str = Field(
        default="canfar-metrics", min_length=1, max_length=_MAX_OTEL_SERVICE_NAME_LENGTH
    )
    export_interval_millis: int = Field(default=60_000, gt=0, le=_MAX_OTEL_EXPORT_INTERVAL_MILLIS)
    deployment_environment: str = Field(
        default="unknown", min_length=1, max_length=_MAX_OTEL_ENVIRONMENT_LENGTH
    )
    kubernetes_namespace: str = Field(
        default="unknown", min_length=1, max_length=_MAX_OTEL_NAMESPACE_LENGTH
    )
    pod_uid: str = Field(
        default_factory=lambda: socket.gethostname() or "unknown",
        min_length=1,
        max_length=_MAX_OTEL_POD_UID_LENGTH,
    )

    @field_validator("exporter_otlp_endpoint", mode="before")
    @classmethod
    def _normalize_endpoint(cls, value: object) -> str | None:
        """Normalize an optional endpoint and reject unsafe destinations."""
        if value is None:
            return None
        if not isinstance(value, str):
            raise ValueError("exporter_otlp_endpoint must be a string")
        normalized = value.strip()
        return None if not normalized else _validate_otel_endpoint(normalized)

    @field_validator("service_name", "deployment_environment", "pod_uid")
    @classmethod
    def _validate_printable_identity(cls, value: str, info) -> str:
        """Require bounded printable ASCII exporter identity values."""
        return _canonical_text(value, field_name=info.field_name, ascii_only=True)

    @field_validator("kubernetes_namespace")
    @classmethod
    def _validate_namespace(cls, value: str) -> str:
        """Require one Kubernetes namespace label."""
        normalized = _canonical_text(value, field_name="kubernetes_namespace", ascii_only=True)
        if not _DNS_LABEL_PATTERN.fullmatch(normalized):
            raise ValueError("kubernetes_namespace must be a Kubernetes DNS label")
        return normalized

    @model_validator(mode="after")
    def _require_endpoint_when_enabled(self) -> OTelConfig:
        """Require an OTLP endpoint when metrics export is enabled."""
        if self.metrics_enabled and not self.exporter_otlp_endpoint:
            raise ValueError("exporter_otlp_endpoint is required when metrics export is enabled")
        return self


class Settings(BaseSettings):
    """Collect validated process configuration from ``METRICS_*`` variables."""

    model_config = SettingsConfigDict(
        env_prefix="METRICS_",
        env_nested_delimiter="__",
        case_sensitive=False,
        env_ignore_empty=True,
        hide_input_in_errors=True,
    )

    host: str = "0.0.0.0"
    port: int = Field(default=8000, ge=1, le=65535)
    log_level: Literal["critical", "error", "warning", "info", "debug", "trace"] = "info"
    startup_validation_timeout_seconds: float = Field(
        default=60.0,
        gt=0,
        le=_MAX_STARTUP_VALIDATION_TIMEOUT_SECONDS,
    )
    cluster_name: str
    platform_name: str = Field(default="canfar", min_length=1, max_length=63)
    providers: ProviderConfigs
    cache: CacheConfig
    otel: OTelConfig = Field(default_factory=OTelConfig)
    redis_url: SecretStr
    redis_key_prefix: str = "metrics:"

    @field_validator("host")
    @classmethod
    def _validate_host(cls, value: str) -> str:
        """Require a canonical DNS or literal IP bind host."""
        return _validate_dns_or_ip_host(
            _canonical_text(value, field_name="host", max_length=_MAX_HOST_LENGTH),
            field_name="host",
        )

    @field_validator("cluster_name")
    @classmethod
    def _validate_cluster_name(cls, value: str) -> str:
        """Require the lower-case DNS name used by cache identity."""
        normalized = _canonical_text(
            value, field_name="cluster_name", max_length=253, ascii_only=True
        )
        if not _is_dns_subdomain(normalized):
            raise ValueError("cluster_name must be a bounded lower-case DNS name")
        return normalized

    @field_validator("platform_name")
    @classmethod
    def _validate_platform_name(cls, value: str) -> str:
        """Require a path-safe public platform subject."""
        normalized = _canonical_text(
            value, field_name="platform_name", max_length=63, ascii_only=True
        )
        if _PLATFORM_NAME_PATTERN.fullmatch(normalized) is None:
            raise ValueError("platform_name must be a path-safe label value")
        return normalized

    @field_validator("redis_url")
    @classmethod
    def _validate_redis(cls, value: SecretStr) -> SecretStr:
        """Validate the configured Redis URL without exposing its credentials."""
        return SecretStr(_validate_redis_url(value.get_secret_value()))

    @field_validator("redis_key_prefix")
    @classmethod
    def _validate_prefix(cls, value: str) -> str:
        """Require a bounded non-empty Redis key namespace."""
        return _canonical_text(value, field_name="redis_key_prefix", max_length=128)


_OTEL_FIELDS = (
    "METRICS_ENABLED",
    "EXPORTER_OTLP_ENDPOINT",
    "SERVICE_NAME",
    "EXPORT_INTERVAL_MILLIS",
    "DEPLOYMENT_ENVIRONMENT",
    "KUBERNETES_NAMESPACE",
    "POD_UID",
)
RETIRED_ENVIRONMENT = {
    **{f"METRICS_OTEL_{name}": f"METRICS_OTEL__{name}" for name in _OTEL_FIELDS},
    "METRICS_ENVIRONMENT": "METRICS_OTEL__DEPLOYMENT_ENVIRONMENT",
    "METRICS_LOGLEVEL": "METRICS_LOG_LEVEL",
    "METRICS_CONFIG_FILE": None,
    "METRICS_API_GROUP": None,
    "METRICS_CACHE_CONTROL_PUBLIC": None,
    "METRICS_SOURCES__PLATFORM": None,
    "METRICS_CACHE__BACKEND": None,
    "METRICS_CACHE__TTL_SECONDS": None,
    "METRICS_CACHE__SCOPE_TTL_SECONDS": None,
    "METRICS_PROVIDERS__KUEUE__KUBE_API_URL": None,
    "METRICS_PROVIDERS__KUEUE__KUBE_API_TOKEN": None,
    "METRICS_PROVIDERS__KUEUE__KUBE_VERIFY_TLS": None,
    "METRICS_PROVIDERS__KUEUE__TOKEN_FILE": None,
    "METRICS_PROVIDERS__KUEUE__CA_FILE": None,
    "METRICS_PROVIDERS__KUEUE__KUBE_CLUSTERQUEUE_PATH": None,
    "METRICS_PROVIDERS__PROMQL__MAX_SERIES": None,
}
"""Retired ``METRICS_*`` names mapped to their replacement, or ``None`` when removed."""


def retired_environment(environ: Mapping[str, str]) -> list[str]:
    """Describe every retired ``METRICS_*`` name set in ``environ``.

    Retired top-level names would otherwise be ignored silently (for example,
    single-underscore OTel keys turn telemetry off), so startup rejects them
    with the replacement to use.
    """
    problems = []
    for name in sorted(environ):
        if name.upper() not in RETIRED_ENVIRONMENT:
            continue
        replacement = RETIRED_ENVIRONMENT[name.upper()]
        problems.append(
            f"{name} is no longer read; use {replacement}" if replacement else f"{name} was removed"
        )
    return problems
