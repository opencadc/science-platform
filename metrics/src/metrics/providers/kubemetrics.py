"""Collect live CPU and memory usage from metrics.k8s.io."""

from __future__ import annotations

from datetime import datetime
from decimal import Decimal
from typing import Any

from metrics.core.settings import Settings
from metrics.errors import ProviderExecutionError
from metrics.providers.kube import (
    MAX_RESULT_OBJECTS,
    KubeReader,
    fan_out,
    label_selector,
    mapping,
    observation_time,
    parse_timestamp,
    sequence,
)
from metrics.services.models import SessionObservation, SessionUsageObservation
from metrics.services.resources import (
    format_resource_amount,
    merge_resource_totals,
    parse_resource_amount,
)


_METRICS_API_VERSION = "metrics.k8s.io/v1beta1"
_SESSION_LABEL = "canfar.net/id"
_USAGE_RESOURCES = ("cpu", "memory")


def _container_usage(container: dict[str, Any]) -> dict[str, Decimal]:
    """Parse one metrics.k8s.io container usage map."""
    usage = container.get("usage")
    if not isinstance(usage, dict):
        return {}
    totals: dict[str, Decimal] = {}
    for resource_name in _USAGE_RESOURCES:
        raw = usage.get(resource_name)
        if raw is not None:
            merge_resource_totals(totals, resource_name, parse_resource_amount(resource_name, raw))
    return totals


class KubeMetricsProvider:
    """Read summed Running-pod usage for one session from metrics.k8s.io."""

    name = "kubemetrics"

    def __init__(self, settings: Settings, api: Any | None = None) -> None:
        """Attach validated settings and an optional kr8s-compatible API fake."""
        config = settings.providers.kueue
        self._kube = KubeReader(timeout=config.kube_request_timeout_seconds, api=api)

    async def read_session_usage(self, observation: SessionObservation) -> SessionUsageObservation:
        """Sum usage of the session's Running pods, reading only namespaces that have one."""
        running = observation.running_pods_by_namespace
        if not running:
            return SessionUsageObservation(usage={}, observed_at=observation.observed_at)
        namespaces = sorted(running)
        selector = label_selector(_SESSION_LABEL, observation.session)

        async def fetch(namespace: str) -> list[Any]:
            payload = await self._kube.get(
                version=_METRICS_API_VERSION,
                url="pods",
                kind="PodMetrics list",
                namespace=namespace,
                params={"labelSelector": selector},
            )
            items = sequence(payload.get("items"), "PodMetrics list contained an invalid shape")
            if len(items) > MAX_RESULT_OBJECTS:
                raise ProviderExecutionError("PodMetrics result exceeded the result limit")
            return items

        totals: dict[str, Decimal] = {}
        observed_at: datetime | None = None
        for namespace, docs in zip(namespaces, await fan_out(namespaces, fetch), strict=True):
            for value in docs:
                doc = mapping(value, "PodMetrics object was invalid")
                metadata = mapping(doc.get("metadata"), "PodMetrics metadata was invalid")
                if metadata.get("name") not in running[namespace]:
                    continue
                raw_timestamp = doc.get("timestamp")
                if raw_timestamp is not None:
                    parsed = parse_timestamp(raw_timestamp, "PodMetrics timestamp was invalid")
                    observed_at = parsed if observed_at is None else min(observed_at, parsed)
                for container in sequence(
                    doc.get("containers"), "PodMetrics containers were missing or invalid"
                ):
                    for name, amount in _container_usage(
                        mapping(container, "PodMetrics container was invalid")
                    ).items():
                        merge_resource_totals(totals, name, amount)
        if not totals:
            return SessionUsageObservation(usage={}, observed_at=observation.observed_at)
        return SessionUsageObservation(
            usage={
                name: format_resource_amount(name, value) for name, value in sorted(totals.items())
            },
            observed_at=observed_at or observation_time(),
        )

    async def shutdown(self) -> None:
        """Release the provider's API handle reference."""
        self._kube.close()
