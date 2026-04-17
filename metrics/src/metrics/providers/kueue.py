"""Kueue ClusterQueue-based platform capacity provider."""

from __future__ import annotations

from datetime import UTC, datetime
from typing import Any

import httpx

from metrics.config import Settings
from metrics.errors import ProviderExecutionError, ProviderUnavailableError
from metrics.models import CapacityReading
from metrics.quantity import parse_cpu_to_cores, parse_memory_to_gib


class KueueCapacityProvider:
    """Read platform capacity from Kueue ClusterQueue objects."""

    source_name = "kueue"

    def __init__(self, settings: Settings) -> None:
        self._settings = settings

    async def get_capacity(self) -> CapacityReading:
        if not self._settings.kube_api_url:
            raise ProviderUnavailableError("METRICS_KUBE_API_URL is not configured")

        url = f"{self._settings.kube_api_url.rstrip('/')}{self._settings.kube_clusterqueue_path}"

        try:
            payload = await _request_json(
                url,
                headers=self._auth_headers(),
                timeout=self._settings.kube_request_timeout_seconds,
                verify=self._settings.kube_verify_tls,
            )
        except Exception as exc:  # pragma: no cover - exercised via monkeypatch
            raise ProviderExecutionError(
                f"Failed querying Kueue ClusterQueues: {exc}"
            ) from exc

        items = payload.get("items") or []
        if not items:
            raise ProviderUnavailableError("No ClusterQueue objects were returned")

        total_cpu = 0.0
        total_memory = 0.0

        for item in items:
            cpu, memory = self._extract_queue_capacity(item)
            total_cpu += cpu
            total_memory += memory

        if total_cpu <= 0 and total_memory <= 0:
            raise ProviderUnavailableError(
                "ClusterQueue data did not include capacity values"
            )

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

    def _extract_queue_capacity(self, item: dict[str, object]) -> tuple[float, float]:
        cpu = 0.0
        memory = 0.0

        spec_resources = item.get("spec", {}).get("resourceGroups", [])
        for group in spec_resources:
            for flavor in group.get("flavors", []):
                for resource in flavor.get("resources", []):
                    name = str(resource.get("name", "")).lower()
                    quota = resource.get("nominalQuota")
                    if name == "cpu":
                        cpu += parse_cpu_to_cores(quota)
                    elif name == "memory":
                        memory += parse_memory_to_gib(quota)

        status_resources = item.get("status", {}).get("flavorsReservation", [])
        for flavor in status_resources:
            for resource in flavor.get("resources", []):
                name = str(resource.get("name", "")).lower()
                total = resource.get("total")
                if name == "cpu":
                    cpu += parse_cpu_to_cores(total)
                elif name == "memory":
                    memory += parse_memory_to_gib(total)

        return cpu, memory


async def _request_json(
    url: str,
    *,
    headers: dict[str, str],
    timeout: float,
    verify: bool,
) -> dict[str, Any]:
    async with httpx.AsyncClient(timeout=timeout, verify=verify) as client:
        response = await client.get(url, headers=headers)
        response.raise_for_status()
        return response.json()
