"""Kueue-backed aggregation for the platform metrics API contract.

Intent
------
The platform endpoint exposes **open-ended** ``capacity`` and ``allocated`` maps
(string quantities keyed by Kubernetes resource name). This module is the
only place that:

1. Fetches the configured ``ClusterQueue`` objects plus the shared ``Cohort``.
2. Sums **nominalQuota** from queue specs and adds cohort nominal quota **once**
   (deduplication across cohort members).
3. Derives **allocated** from ``status.flavorsUsage`` (``total`` + ``borrowed``)
   per resource, summed across queues only—cohort objects do not carry the same
   usage shape for this milestone. Resources listed in ``capacity`` also appear
   in ``allocated`` with formatted zeros when Kueue reports no usage rows yet.

HTTP and URL concerns stay in :mod:`metrics.providers.kube_http` and
:mod:`metrics.kueue_api`; quantity parsing stays in :mod:`metrics.quantity` and
:mod:`metrics.kueue_spec`.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

import httpx

from metrics.core.settings import Settings
from metrics.errors import ProviderExecutionError, ProviderUnavailableError
from metrics.kueue_api import cluster_queue_object_url, cohort_object_url
from metrics.kueue_spec import sum_nominal_quotas_by_resource
from metrics.quantity import (
    format_resource_amount,
    merge_resource_totals,
    parse_resource_amount,
)
from metrics.providers.kube_http import (
    kube_auth_headers,
    kube_parallel_get_json,
    resolve_kube_tls_verify,
    resolve_kube_token,
)


@dataclass(slots=True)
class PlatformResourceMaps:
    """Structured result returned by :class:`KueuePlatformEngine` before API shaping.

    Attributes:
        capacity: Aggregated nominal quota per resource name (string quantities).
        allocated: Aggregated admitted usage per resource name (string quantities).
    """

    capacity: dict[str, str]
    allocated: dict[str, str]


def _sum_usage_from_status(doc: dict[str, Any]) -> dict[str, float]:
    """Sum ``flavorsUsage`` ``total`` and ``borrowed`` per resource for one queue.

    Kueue reports usage per flavor; this helper flattens all flavors into one
    map keyed by resource name for the configured queue subset aggregation.

    Args:
        doc: A ``ClusterQueue`` API object including ``status``.

    Returns:
        Resource name → float in the same internal units as
        :func:`metrics.quantity.parse_resource_amount`.
    """
    totals: dict[str, float] = {}
    status = doc.get("status") or {}
    for flavor in status.get("flavorsUsage") or []:
        for resource in flavor.get("resources") or []:
            name = str(resource.get("name", "")).strip()
            if not name:
                continue
            total = resource.get("total")
            borrowed = resource.get("borrowed")
            merge_resource_totals(
                totals,
                name,
                parse_resource_amount(name, str(total) if total is not None else ""),
            )
            merge_resource_totals(
                totals,
                name,
                parse_resource_amount(
                    name, str(borrowed) if borrowed is not None else ""
                ),
            )
    return totals


def _float_maps_to_strings(values: dict[str, float]) -> dict[str, str]:
    """Convert internal floats to stable Kubernetes-style quantity strings."""
    return {
        name: format_resource_amount(name, val) for name, val in sorted(values.items())
    }


def _align_allocated_with_capacity(
    capacity: dict[str, str],
    allocated: dict[str, str],
) -> dict[str, str]:
    """Ensure every resource name in ``capacity`` appears in ``allocated``.

    When no workloads are admitted, Kueue may omit ``flavorsUsage`` rows, so raw
    aggregation yields an empty map. Clients expect the same keys as ``capacity``
    with explicit zero quantities (``0`` CPU, ``0Gi`` memory, etc.).
    """
    out = dict(allocated)
    for name in capacity:
        if name not in out:
            out[name] = format_resource_amount(name, 0.0)
    return dict(sorted(out.items()))


class KueuePlatformEngine:
    """Collect platform ``capacity`` / ``allocated`` maps for configured queues.

    The engine is stateless aside from ``Settings``; each :meth:`collect` call
    performs fresh reads suitable for TTL caching at the service layer.
    """

    def __init__(self, settings: Settings) -> None:
        self._settings = settings

    async def collect(self) -> PlatformResourceMaps:
        """Fetch Kueue objects and return aggregated resource maps.

        Raises:
            ProviderUnavailableError: Missing API URL or empty nominal quota sums.
            ProviderExecutionError: Transport or non-success HTTP responses.
        """
        k = self._settings.platform.kueue
        if not k.kube_api_url:
            raise ProviderUnavailableError(
                "METRICS_PLATFORM__KUEUE__KUBE_API_URL is not configured"
            )

        token = resolve_kube_token(k.kube_api_token)
        headers = kube_auth_headers(token)
        verify = resolve_kube_tls_verify(k.kube_verify_tls)
        timeout = k.kube_request_timeout_seconds

        queue_totals: dict[str, float] = {}
        allocated_totals: dict[str, float] = {}

        queue_urls = [
            cluster_queue_object_url(self._settings, q) for q in k.cluster_queues
        ]
        cohort_url = cohort_object_url(self._settings, k.cohort)

        try:
            docs = await kube_parallel_get_json(
                [*queue_urls, cohort_url],
                headers=headers,
                timeout=timeout,
                verify=verify,
            )
        except httpx.HTTPStatusError as exc:
            raise ProviderExecutionError(
                f"Kubernetes returned HTTP {exc.response.status_code} querying Kueue objects"
            ) from exc
        except httpx.RequestError as exc:
            raise ProviderExecutionError(
                f"Failed querying Kueue objects: {exc}"
            ) from exc

        queue_docs = docs[:-1]
        cohort_doc = docs[-1]

        for item in queue_docs:
            for name, val in sum_nominal_quotas_by_resource(item).items():
                merge_resource_totals(queue_totals, name, val)
            for name, val in _sum_usage_from_status(item).items():
                merge_resource_totals(allocated_totals, name, val)

        for name, val in sum_nominal_quotas_by_resource(cohort_doc).items():
            merge_resource_totals(queue_totals, name, val)

        if not queue_totals:
            raise ProviderUnavailableError(
                "Kueue ClusterQueue and Cohort specs did not include nominal quota values"
            )

        capacity_str = _float_maps_to_strings(queue_totals)
        allocated_str = _align_allocated_with_capacity(
            capacity_str,
            _float_maps_to_strings(allocated_totals),
        )

        return PlatformResourceMaps(
            capacity=capacity_str,
            allocated=allocated_str,
        )
