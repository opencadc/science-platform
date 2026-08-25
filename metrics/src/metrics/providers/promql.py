"""Controlled PromQL source for active-workload lifetime accounting."""

from __future__ import annotations

import hashlib
import json
from collections import defaultdict
from datetime import UTC, datetime
from decimal import Decimal, InvalidOperation
from enum import StrEnum
from time import perf_counter
from typing import Any

import httpx

from metrics.core.settings import Settings
from metrics.errors import ProviderExecutionError, ProviderUnavailableError
from metrics.services.models import ActiveWorkloadLifetime, LifetimeIssue
from metrics.services.resources import aggregate_active_workload_hours
from metrics.telemetry import MetricsRecorder, NoopMetricsRecorder

SOURCE_REVISION = "1"
USAGE_METRIC = "canfar_active_workload_usage_hours_total"
REQUESTED_METRIC = "canfar_active_workload_requested_hours_total"
COMPLETE_METRIC = "canfar_active_workload_accounting_complete"

_BASE_LABELS = {
    "__name__",
    "cluster",
    "namespace",
    "pod_uid",
    "resource",
    "canfar_username",
    "canfar_community",
    "source_revision",
    "unit",
}
_UNITS = {
    "cpu": "core-hours",
    "memory": "GiB-hours",
    "nvidia.com/gpu": "GPU-hours",
}
_ISSUES = {issue.value: issue for issue in LifetimeIssue}


class QueryTemplate(StrEnum):
    """Names of the only instant queries this provider can execute."""

    USER_ACTIVE_LIFETIME = "user-active-lifetime"
    COMMUNITY_ACTIVE_LIFETIME = "community-active-lifetime"


def _escape_label(value: str) -> str:
    """Escape one exact PromQL string literal."""
    return value.replace("\\", "\\\\").replace("\n", "\\n").replace('"', '\\"')


def _query(template: QueryTemplate, subject: str) -> str:
    label = (
        "canfar_username" if template is QueryTemplate.USER_ACTIVE_LIFETIME else "canfar_community"
    )
    selector = (
        '{__name__=~"canfar_active_workload_(usage|requested)_hours_total|'
        'canfar_active_workload_accounting_complete",'
        f'source_revision="{SOURCE_REVISION}",{label}="{_escape_label(subject)}"' + "}"
    )
    running = (
        'label_replace(kube_pod_status_phase{phase="Running"} == 1,"pod_uid","$1","uid","(.*)")'
    )
    return f"({selector}) and on (namespace,pod_uid) ({running})"


def _decimal(value: Any) -> Decimal:
    try:
        result = Decimal(str(value))
    except (InvalidOperation, ValueError) as exc:
        raise ProviderExecutionError("PromQL returned a non-decimal sample") from exc
    if not result.is_finite() or result < 0:
        raise ProviderExecutionError("PromQL returned a negative or non-finite sample")
    return result


def _sample_time(value: Any) -> datetime:
    try:
        return datetime.fromtimestamp(float(value), tz=UTC)
    except (OSError, OverflowError, TypeError, ValueError) as exc:
        raise ProviderExecutionError("PromQL returned an invalid sample timestamp") from exc


class PromQLProvider:
    """Async form-POST adapter over a fixed, Metrics-owned query catalog."""

    name = "promql"

    def __init__(
        self,
        settings: Settings,
        *,
        client: httpx.AsyncClient | None = None,
        telemetry: MetricsRecorder | None = None,
    ) -> None:
        """Attach strict settings and an optional test-owned HTTP client."""
        self._cluster = settings.cluster_name
        self._config = settings.providers.promql
        self._endpoint = f"{str(self._config.base_url).rstrip('/')}/api/v1/query"
        self._headers = (
            {"X-Scope-OrgID": self._config.mimir_tenant_id}
            if self._config.mimir_tenant_id
            else None
        )
        self._client = client
        self._owns_client = client is None
        self._telemetry = telemetry or NoopMetricsRecorder()

    def cache_fingerprint(self) -> str:
        """Hash backend and tenant ownership for cache segregation."""
        identity = json.dumps(
            {
                "base_url": str(self._config.base_url),
                "tenant": self._config.mimir_tenant_id,
            },
            separators=(",", ":"),
            sort_keys=True,
        )
        return hashlib.sha256(identity.encode()).hexdigest()[:24]

    async def startup(self) -> None:
        """Create the single lifespan-scoped HTTP client."""
        if self._client is not None:
            return
        self._client = httpx.AsyncClient(
            headers=self._headers,
            timeout=self._config.request_timeout_seconds,
        )

    async def shutdown(self) -> None:
        """Close the owned HTTP client."""
        client, self._client = self._client, None
        if client is not None and self._owns_client:
            await client.aclose()

    async def read_user(self, username: str) -> ActiveWorkloadLifetime:
        """Read one exact user's validated accounting snapshot."""
        return await self._read(QueryTemplate.USER_ACTIVE_LIFETIME, username)

    async def read_community(self, community: str) -> ActiveWorkloadLifetime:
        """Read one exact community's validated accounting snapshot."""
        return await self._read(QueryTemplate.COMMUNITY_ACTIVE_LIFETIME, community)

    async def _read(
        self,
        template: QueryTemplate,
        subject: str,
    ) -> ActiveWorkloadLifetime:
        if not subject:
            raise ProviderExecutionError("Accounting subject must not be empty")
        if self._client is None:
            raise ProviderUnavailableError("PromQL provider has not started")
        started = perf_counter()
        status = "ok"
        try:
            with self._telemetry.span(
                "source.read",
                {
                    "metrics.scope": template.value.split("-", 1)[0],
                    "provider.name": self.name,
                    "promql.template": template.value,
                    "source.operation": "instant-query",
                },
            ):
                response = await self._client.post(
                    self._endpoint,
                    data={"query": _query(template, subject)},
                    headers=self._headers,
                )
                response.raise_for_status()
            return self._validate(response.json(), template, subject)
        except (httpx.TimeoutException, httpx.NetworkError) as exc:
            status = "error"
            raise ProviderUnavailableError("PromQL backend unavailable") from exc
        except httpx.HTTPStatusError as exc:
            status = "error"
            error = (
                ProviderUnavailableError("PromQL backend unavailable")
                if exc.response.status_code >= 500
                else ProviderExecutionError("PromQL backend rejected a controlled query")
            )
            raise error from exc
        except (json.JSONDecodeError, UnicodeDecodeError) as exc:
            status = "error"
            raise ProviderExecutionError("PromQL backend returned invalid JSON") from exc
        except Exception:
            status = "error"
            raise
        finally:
            self._telemetry.record_provider_duration(
                provider=self.name,
                scope=template.value,
                status=status,
                seconds=perf_counter() - started,
            )

    def _validate(
        self,
        payload: Any,
        template: QueryTemplate,
        subject: str,
    ) -> ActiveWorkloadLifetime:
        """Validate one vector completely before returning a normalized value."""
        if not isinstance(payload, dict) or payload.get("status") != "success":
            raise ProviderExecutionError("PromQL query did not succeed")
        data = payload.get("data")
        if not isinstance(data, dict) or data.get("resultType") != "vector":
            raise ProviderExecutionError("PromQL query did not return an instant vector")
        result = data.get("result")
        if not isinstance(result, list) or len(result) > self._config.max_series:
            raise ProviderExecutionError("PromQL vector cardinality is invalid")

        expected_label = (
            "canfar_username"
            if template is QueryTemplate.USER_ACTIVE_LIFETIME
            else "canfar_community"
        )
        groups: dict[tuple[str, ...], dict[str, tuple[dict[str, str], Decimal]]] = defaultdict(dict)
        observed_at: datetime | None = None
        for series in result:
            if not isinstance(series, dict):
                raise ProviderExecutionError("PromQL returned an invalid series")
            labels = series.get("metric")
            sample = series.get("value")
            if (
                not isinstance(labels, dict)
                or not all(
                    isinstance(key, str) and isinstance(value, str) for key, value in labels.items()
                )
                or not isinstance(sample, list)
                or len(sample) != 2
            ):
                raise ProviderExecutionError("PromQL returned an invalid series")
            metric = labels.get("__name__")
            allowed = _BASE_LABELS | ({"reason"} if metric == COMPLETE_METRIC else set())
            if set(labels) != allowed:
                raise ProviderExecutionError("PromQL returned unexpected series labels")
            if (
                labels["cluster"] != self._cluster
                or labels["source_revision"] != SOURCE_REVISION
                or labels[expected_label] != subject
                or not labels["namespace"]
                or not labels["pod_uid"]
            ):
                raise ProviderExecutionError(
                    "PromQL returned a series outside the selected population"
                )

            resource = labels["resource"]
            unit = "boolean" if metric == COMPLETE_METRIC else _UNITS.get(resource)
            if unit is None or labels["unit"] != unit:
                raise ProviderExecutionError("PromQL returned an invalid resource unit")
            timestamp = _sample_time(sample[0])
            now = datetime.now(UTC)
            age = (now - timestamp).total_seconds()
            if (
                age > self._config.max_sample_age_seconds
                or age < -self._config.future_sample_tolerance_seconds
            ):
                raise ProviderExecutionError("PromQL returned a stale or future sample")
            if observed_at is None:
                observed_at = timestamp
            elif timestamp != observed_at:
                raise ProviderExecutionError("PromQL series do not share one observation time")

            identity = (
                labels["namespace"],
                labels["pod_uid"],
                resource,
                labels["canfar_username"],
                labels["canfar_community"],
            )
            if metric not in {USAGE_METRIC, REQUESTED_METRIC, COMPLETE_METRIC}:
                raise ProviderExecutionError("PromQL returned an unexpected metric")
            if metric in groups[identity]:
                raise ProviderExecutionError("PromQL returned duplicate accounting series")
            groups[identity][metric] = (labels, _decimal(sample[1]))

        normalized: list[tuple[str, Decimal, Decimal, frozenset[LifetimeIssue]]] = []
        for identity, metrics in groups.items():
            resource = identity[2]
            if set(metrics) != {USAGE_METRIC, REQUESTED_METRIC, COMPLETE_METRIC}:
                raise ProviderExecutionError("PromQL accounting series are incomplete")
            complete_labels, complete_value = metrics[COMPLETE_METRIC]
            reason = complete_labels["reason"]
            if complete_value == 1 and reason == "complete":
                normalized.append(
                    (
                        resource,
                        metrics[USAGE_METRIC][1],
                        metrics[REQUESTED_METRIC][1],
                        frozenset(),
                    )
                )
            elif complete_value == 0 and reason in _ISSUES:
                normalized.append(
                    (
                        resource,
                        metrics[USAGE_METRIC][1],
                        metrics[REQUESTED_METRIC][1],
                        frozenset({_ISSUES[reason]}),
                    )
                )
            else:
                raise ProviderExecutionError("PromQL returned invalid completeness state")
        return aggregate_active_workload_hours(normalized)
