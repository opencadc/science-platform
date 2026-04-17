"""Node capacity fallback provider."""

from __future__ import annotations

from datetime import UTC, datetime
from typing import Any

import httpx

from metrics.config import Settings
from metrics.errors import ProviderExecutionError, ProviderUnavailableError
from metrics.models import CapacityReading
from metrics.quantity import parse_cpu_to_cores, parse_memory_to_gib


class NodeCapacityProvider:
    """Aggregate capacity from Kubernetes nodes as a fallback."""

    source_name = "kubernetes-nodes"

    def __init__(self, settings: Settings) -> None:
        self._settings = settings

    async def get_capacity(self) -> CapacityReading:
        if not self._settings.kube_api_url:
            raise ProviderUnavailableError("METRICS_KUBE_API_URL is not configured")

        url = f"{self._settings.kube_api_url.rstrip('/')}{self._settings.kube_nodes_path}"
        params: dict[str, str] = {}
        if self._settings.node_label_selector:
            params["labelSelector"] = self._settings.node_label_selector

        try:
            payload = await _request_json(
                url,
                headers=self._auth_headers(),
                params=params or None,
                timeout=self._settings.kube_request_timeout_seconds,
                verify=self._settings.kube_verify_tls,
            )
        except Exception as exc:  # pragma: no cover - exercised via monkeypatch
            raise ProviderExecutionError(f"Failed querying nodes API: {exc}") from exc

        items = payload.get("items") or []
        total_cpu = 0.0
        total_memory = 0.0

        for node in items:
            if not self._is_schedulable_ready_node(node):
                continue

            capacity = node.get("status", {}).get("capacity", {})
            total_cpu += parse_cpu_to_cores(capacity.get("cpu"))
            total_memory += parse_memory_to_gib(capacity.get("memory"))

        if total_cpu <= 0 and total_memory <= 0:
            raise ProviderUnavailableError("No valid nodes with allocatable capacity were found")

        return CapacityReading(
            cpu_cores=total_cpu,
            memory_gib=total_memory,
            source=self.source_name,
            observed_at=datetime.now(UTC),
        )

    def _auth_headers(self) -> dict[str, str]:
        token = self._settings.kube_api_token
        if token:
            return {"Authorization": f"Bearer {token}"}
        return {}

    def _is_schedulable_ready_node(self, node: dict[str, object]) -> bool:
        spec = node.get("spec", {})
        if spec.get("unschedulable") is True:
            return False

        conditions = node.get("status", {}).get("conditions", [])
        for condition in conditions:
            if condition.get("type") == "Ready":
                return condition.get("status") == "True"

        return True


async def _request_json(
    url: str,
    *,
    headers: dict[str, str],
    params: dict[str, str] | None,
    timeout: float,
    verify: bool,
) -> dict[str, Any]:
    async with httpx.AsyncClient(timeout=timeout, verify=verify) as client:
        response = await client.get(url, headers=headers, params=params)
        response.raise_for_status()
        return response.json()
