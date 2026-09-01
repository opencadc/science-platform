"""Collect live CPU and memory usage from metrics.k8s.io."""

from __future__ import annotations

from decimal import Decimal
from typing import Any

import kr8s

from metrics.core.settings import KueueProviderConfig, Settings
from metrics.errors import ProviderExecutionError, ProviderUnavailableError
from metrics.providers.kueue import (
    _REQUEST_ERRORS,
    _bounded_map,
    _json_response,
    _list,
    _mapping,
    _validate_subject,
    create_kube_api,
)
from metrics.services.resources import format_resource_amount, merge_resource_totals, parse_resource_amount


_METRICS_API_VERSION = "metrics.k8s.io/v1beta1"
_SESSION_LABEL = "canfar.net/id"
_PAUSE_CONTAINERS = frozenset({"pause", "POD"})
_USAGE_RESOURCES = frozenset({"cpu", "memory"})
_MAX_RESULT_OBJECTS = 3_000


def _container_usage(container: dict[str, Any]) -> dict[str, Decimal]:
    """Parse one metrics.k8s.io container usage map."""
    name = container.get("name")
    if not isinstance(name, str) or name in _PAUSE_CONTAINERS:
        return {}
    usage = container.get("usage")
    if not isinstance(usage, dict):
        return {}
    totals: dict[str, Decimal] = {}
    for resource_name in _USAGE_RESOURCES:
        raw = usage.get(resource_name)
        if raw is None:
            continue
        merge_resource_totals(totals, resource_name, parse_resource_amount(resource_name, raw))
    return totals


async def fetch_pod_metrics_docs(
    api: Any,
    api_version: str,
    namespace: str,
    *,
    label_selector: str | None = None,
) -> list[dict[str, Any]]:
    """List PodMetrics objects in one namespace."""
    params: dict[str, str] = {}
    if label_selector:
        params["labelSelector"] = label_selector
    async with api.call_api(
        method="GET",
        version=api_version,
        namespace=namespace,
        url="pods",
        params=params or None,
    ) as response:
        payload = _json_response(response, "PodMetrics list")
    items = payload.get("items")
    if not isinstance(items, list) or not all(isinstance(item, dict) for item in items):
        raise ProviderExecutionError("PodMetrics list contained an invalid object shape")
    if len(items) > _MAX_RESULT_OBJECTS:
        raise ProviderExecutionError("PodMetrics result exceeded the result limit")
    return items


class KubeMetricsProvider:
    """Read summed pod usage for one session from metrics.k8s.io."""

    name = "kubemetrics"

    def __init__(self, settings: Settings, api: Any | None = None) -> None:
        """Attach validated settings and an optional kr8s-compatible API fake."""
        self._settings = settings
        self._config: KueueProviderConfig = settings.providers.kueue
        self._api = api

    async def _ensure_api(self) -> Any:
        """Create and retain the Kubernetes API handle on first use."""
        if self._api is None:
            try:
                self._api = await create_kube_api(self._config)
            except Exception as exc:
                raise ProviderUnavailableError("Could not configure Kubernetes API access") from exc
        return self._api

    async def read_session_usage(self, session_id: str) -> dict[str, str]:
        """Return summed Running-pod usage for one session id."""
        session_id = _validate_subject(session_id)
        api = await self._ensure_api()
        selector = f"{_SESSION_LABEL}={session_id}"

        async def fetch(namespace: str) -> list[dict[str, Any]]:
            return await fetch_pod_metrics_docs(
                api,
                _METRICS_API_VERSION,
                namespace,
                label_selector=selector,
            )

        try:
            docs_by_namespace = await _bounded_map(self._config.namespaces, fetch)
        except kr8s.NotFoundError as exc:
            raise ProviderUnavailableError("metrics.k8s.io is unavailable") from exc
        except kr8s.ServerError as exc:
            raise ProviderUnavailableError("Configured PodMetrics namespace access failed") from exc
        except _REQUEST_ERRORS as exc:
            raise ProviderUnavailableError("PodMetrics access failed") from exc

        totals: dict[str, Decimal] = {}
        for docs in docs_by_namespace:
            for doc in docs:
                containers = _list(
                    _mapping(doc, "PodMetrics object was invalid").get("containers"),
                    "PodMetrics containers were missing or invalid",
                )
                for container_value in containers:
                    container = _mapping(container_value, "PodMetrics container was invalid")
                    for name, value in _container_usage(container).items():
                        merge_resource_totals(totals, name, value)
        if not totals:
            return {}
        return {
            name: format_resource_amount(name, value) for name, value in sorted(totals.items())
        }

    async def shutdown(self) -> None:
        """Release the provider's API handle reference."""
        self._api = None
