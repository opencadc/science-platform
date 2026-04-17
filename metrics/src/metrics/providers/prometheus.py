"""Prometheus-backed requested usage provider."""

from __future__ import annotations

from datetime import UTC, datetime
from typing import Any

import httpx

from metrics.config import Settings
from metrics.errors import ProviderExecutionError, ProviderUnavailableError
from metrics.models import UsageReading


class PrometheusUsageProvider:
    """Read requested CPU and memory from Prometheus queries."""

    source_name = "prometheus"

    def __init__(self, settings: Settings) -> None:
        self._settings = settings

    async def get_usage(self) -> UsageReading:
        if not self._settings.prometheus_url:
            raise ProviderUnavailableError("METRICS_PROMETHEUS_URL is not configured")

        return await self._build_usage_reading(
            cpu_query=self._settings.promql_requested_cpu_cores,
            memory_query=self._settings.promql_requested_memory_bytes,
            source=self.source_name,
        )

    async def get_usage_for_user(self, user_id: str) -> UsageReading:
        return await self._build_usage_reading(
            cpu_query=self._resource_request_query(
                resource="cpu",
                unit="core",
                labels={self._settings.user_label_key: user_id},
            ),
            memory_query=self._resource_request_query(
                resource="memory",
                unit="byte",
                labels={self._settings.user_label_key: user_id},
            ),
            source=f"{self.source_name}:user",
        )

    async def get_usage_for_session(self, user_id: str, session_id: str) -> UsageReading:
        labels = {
            self._settings.user_label_key: user_id,
            self._settings.session_label_key: session_id,
        }
        return await self._build_usage_reading(
            cpu_query=self._resource_request_query(
                resource="cpu",
                unit="core",
                labels=labels,
            ),
            memory_query=self._resource_request_query(
                resource="memory",
                unit="byte",
                labels=labels,
            ),
            source=f"{self.source_name}:session",
        )

    async def _build_usage_reading(
        self,
        *,
        cpu_query: str,
        memory_query: str,
        source: str,
    ) -> UsageReading:
        requested_cpu = await self._query_scalar(cpu_query)
        requested_memory_bytes = await self._query_scalar(memory_query)
        return UsageReading(
            requested_cpu_cores=max(requested_cpu, 0.0),
            requested_memory_gib=max(requested_memory_bytes / (1024**3), 0.0),
            source=source,
            observed_at=datetime.now(UTC),
        )

    async def _query_scalar(self, query: str) -> float:
        endpoint = f"{self._settings.prometheus_url.rstrip('/')}/api/v1/query"

        try:
            payload = await _request_json(
                endpoint,
                params={"query": query},
                timeout=self._settings.prometheus_timeout_seconds,
                verify=self._settings.prometheus_verify_tls,
            )
        except Exception as exc:  # pragma: no cover - exercised via monkeypatch
            raise ProviderExecutionError(f"Prometheus query failed: {exc}") from exc

        if payload.get("status") != "success":
            raise ProviderExecutionError("Prometheus returned a non-success status")

        result = payload.get("data", {}).get("result", [])
        if not result:
            return 0.0

        value = result[0].get("value", [None, "0"])
        if len(value) < 2:
            return 0.0

        return float(value[1])

    def _resource_request_query(
        self,
        *,
        resource: str,
        unit: str,
        labels: dict[str, str],
    ) -> str:
        labels_with_resource = {
            "resource": resource,
            "unit": unit,
            **labels,
        }
        selector = ",".join(
            f'{key}="{_escape_label_value(value)}"'
            for key, value in labels_with_resource.items()
        )
        return f"sum({self._settings.resource_requests_metric_name}{{{selector}}})"


async def _request_json(
    url: str,
    *,
    params: dict[str, str],
    timeout: float,
    verify: bool,
) -> dict[str, Any]:
    async with httpx.AsyncClient(timeout=timeout, verify=verify) as client:
        response = await client.get(url, params=params)
        response.raise_for_status()
        return response.json()


def _escape_label_value(value: str) -> str:
    return value.replace("\\", "\\\\").replace('"', '\\"')
