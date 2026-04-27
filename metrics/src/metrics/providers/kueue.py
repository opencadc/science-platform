"""Kueue-backed platform metrics, startup checks, URL building, and spec parsing."""

from __future__ import annotations

import hashlib
from dataclasses import dataclass
from typing import Any

import httpx

from metrics.core.settings import KueueProviderConfig, Settings
from metrics.errors import (
    ProviderExecutionError,
    ProviderUnavailableError,
    RuntimeStartupError,
)
from metrics.providers.base import (
    MetricScope,
    Provider,
    ProviderMetrics,
)
from metrics.providers.kube import (
    kube_auth_headers,
    kube_get_json,
    kube_parallel_get_json,
    resolve_kube_token,
    resolve_kube_verify,
)
from metrics.quantity import (
    format_resource_amount,
    merge_resource_totals,
    parse_resource_amount,
)
from metrics.schemas.metrics import PlatformMetricsData


def kueue_http_client(kueue_config: KueueProviderConfig) -> httpx.AsyncClient:
    """Build a shared ``httpx.AsyncClient`` for Kueue and Kubernetes list/get calls.

    Args:
        kueue_config: Kueue provider settings (timeouts, TLS, pool sizes, HTTP/2).

    Returns:
        A configured async client; callers own lifecycle and must call ``aclose()``.
    """
    http_config = kueue_config.http
    verify = resolve_kube_verify(kueue_config.kube_verify_tls, ca_file=kueue_config.ca_file)
    return httpx.AsyncClient(
        limits=httpx.Limits(
            max_connections=http_config.max_connections,
            max_keepalive_connections=http_config.max_keepalive_connections,
            keepalive_expiry=http_config.keepalive_expiry_seconds,
        ),
        http2=http_config.http2,
        timeout=httpx.Timeout(kueue_config.kube_request_timeout_seconds),
        verify=verify,
    )


def kueue_clusterqueues_list_url(
    kueue_config: KueueProviderConfig,
) -> str:
    """Return the Kubernetes API list URL for ClusterQueue objects."""
    base = (kueue_config.kube_api_url or "").rstrip("/")
    return f"{base}{kueue_config.kube_clusterqueue_path}"


def cluster_queue_object_url(kueue_config: KueueProviderConfig, queue_name: str) -> str:
    """Return the get-by-name URL for a ``ClusterQueue`` custom resource."""
    base = (kueue_config.kube_api_url or "").rstrip("/")
    return f"{base}{kueue_config.kube_clusterqueue_path}/{queue_name}"


def cohort_object_url(kueue_config: KueueProviderConfig, cohort_name: str) -> str:
    """Return the get-by-name URL for a Kueue ``Cohort`` custom resource."""
    base = (kueue_config.kube_api_url or "").rstrip("/")
    return f"{base}{kueue_config.kube_cohort_path}/{cohort_name}"


def sum_nominal_quotas_by_resource(doc: dict[str, Any]) -> dict[str, float]:
    """Sum ``nominalQuota`` for every resource across all groups and flavors.

    JSON follows Kueue v1beta2 CRDs: each ``resourceGroups`` entry lists
    ``flavors``, each flavor lists ``resources`` with ``nominalQuota`` quantities
    compatible with Kubernetes resource.Quantity syntax.

    Resource **names** are taken verbatim from the API (for example ``cpu``,
    ``memory``, ``nvidia.com/gpu``) so the platform contract can surface future
    resource types without schema changes. Values are accumulated in internal
    float units: cores for CPU, gibibytes for memory, raw float for unknown names
    (see :func:`metrics.quantity.parse_resource_amount`).

    Args:
        doc: A ``ClusterQueue`` or ``Cohort`` API object (dict with ``spec``).

    Returns:
        Mapping of resource name → aggregated float suitable for formatting back
        to Kubernetes-style quantity strings.

    """
    totals: dict[str, float] = {}
    spec = doc.get("spec") or {}
    for group in spec.get("resourceGroups") or []:
        for flavor in group.get("flavors") or []:
            for resource in flavor.get("resources") or []:
                name = str(resource.get("name", "")).strip()
                if not name:
                    continue
                quota = resource.get("nominalQuota")
                merge_resource_totals(
                    totals,
                    name,
                    parse_resource_amount(name, str(quota) if quota is not None else ""),
                )
    return totals


def _sum_usage_from_status(doc: dict[str, Any]) -> dict[str, float]:
    totals: dict[str, float] = {}
    status = doc.get("status") or {}
    for flavor in status.get("flavorsUsage") or []:
        for resource in flavor.get("resources") or []:
            name = str(resource.get("name", "")).strip()
            if not name:
                continue
            total = resource.get("total")
            merge_resource_totals(
                totals,
                name,
                parse_resource_amount(name, str(total) if total is not None else ""),
            )
    return totals


def _float_maps_to_strings(values: dict[str, float]) -> dict[str, str]:
    return {name: format_resource_amount(name, val) for name, val in sorted(values.items())}


def _align_allocated_with_capacity(
    capacity: dict[str, str],
    allocated: dict[str, str],
) -> dict[str, str]:
    out = dict(allocated)
    for name in capacity:
        if name not in out:
            out[name] = format_resource_amount(name, 0.0)
    return dict(sorted(out.items()))


@dataclass(slots=True)
class _PlatformResourceMaps:
    capacity: dict[str, str]
    allocated: dict[str, str]


class KueueMetrics(ProviderMetrics):
    """Kueue implementation for the platform scope."""

    supported_scopes: frozenset[MetricScope] = frozenset({MetricScope.PLATFORM})

    def __init__(
        self,
        *,
        settings: Settings,
        client: httpx.AsyncClient,
        kueue_config: KueueProviderConfig,
    ) -> None:
        """Build platform metrics for the given Kueue configuration and client.

        Args:
            settings: App settings (e.g. cluster name for the API payload).
            client: Injected client used for parallel Kubernetes GET requests.
            kueue_config: Kueue provider fields (API URL, queues, cohort, token paths).
        """
        self._settings = settings
        self._client = client
        self._kueue_config = kueue_config

    async def platform(self) -> PlatformMetricsData:
        """Load capacity and allocated maps from Kueue ClusterQueue and Cohort data."""
        maps = await self._collect_resource_maps()
        return PlatformMetricsData(
            cluster=self._settings.cluster_name,
            capacity=maps.capacity,
            allocated=maps.allocated,
        )

    async def _collect_resource_maps(self) -> _PlatformResourceMaps:
        kueue_config = self._kueue_config
        if not kueue_config.kube_api_url:
            raise ProviderUnavailableError("Kueue kube_api_url is not configured")
        if not kueue_config.cluster_queues or not kueue_config.cohort:
            raise ProviderUnavailableError(
                "Kueue cluster_queues and cohort must be configured for platform metrics"
            )
        token = resolve_kube_token(kueue_config.kube_api_token, kueue_config.token_file)
        if not token:
            raise ProviderUnavailableError(
                "No Kubernetes API bearer token available for Kueue calls"
            )
        headers = kube_auth_headers(token)

        queue_urls = [
            cluster_queue_object_url(kueue_config, q) for q in kueue_config.cluster_queues
        ]
        cohort_url = cohort_object_url(kueue_config, kueue_config.cohort)

        try:
            docs = await kube_parallel_get_json(
                self._client,
                [*queue_urls, cohort_url],
                headers=headers,
            )
        except httpx.HTTPStatusError as exc:
            raise ProviderExecutionError(
                f"Kubernetes returned HTTP {exc.response.status_code} querying Kueue objects"
            ) from exc
        except httpx.RequestError as exc:
            # Do not embed str(exc) here: httpx may include the request URL, which
            # must not propagate into API error payloads.
            raise ProviderExecutionError(
                "Failed querying Kueue objects (upstream request error)"
            ) from exc

        queue_docs = docs[:-1]
        cohort_doc = docs[-1]
        queue_totals: dict[str, float] = {}
        allocated_totals: dict[str, float] = {}

        for item in queue_docs:
            for res_name, val in sum_nominal_quotas_by_resource(item).items():
                merge_resource_totals(queue_totals, res_name, val)
            for res_name, val in _sum_usage_from_status(item).items():
                merge_resource_totals(allocated_totals, res_name, val)
        for res_name, val in sum_nominal_quotas_by_resource(cohort_doc).items():
            merge_resource_totals(queue_totals, res_name, val)
        if not queue_totals:
            raise ProviderUnavailableError(
                "Kueue ClusterQueue and Cohort specs did not include nominal quota values"
            )
        capacity_str = _float_maps_to_strings(queue_totals)
        allocated_str = _align_allocated_with_capacity(
            capacity_str,
            _float_maps_to_strings(allocated_totals),
        )
        return _PlatformResourceMaps(
            capacity=capacity_str,
            allocated=allocated_str,
        )


class KueueProvider(Provider):
    """Kueue source: startup validation and platform metrics."""

    def __init__(self, settings: Settings, client: httpx.AsyncClient) -> None:
        """Attach settings and a shared client for this provider.

        Args:
            settings: Full app settings; Kueue fields live under ``providers.kueue``.
            client: Async HTTP client used for Kubernetes API traffic.
        """
        self._settings = settings
        self._client = client
        self._kueue_config = settings.providers.kueue
        self._metrics = KueueMetrics(
            settings=settings,
            client=client,
            kueue_config=self._kueue_config,
        )

    @property
    def name(self) -> str:
        """Stable provider key matching configuration."""
        return "kueue"

    def metrics(self) -> KueueMetrics:
        """Return the Kueue :class:`KueueMetrics` implementation."""
        return self._metrics

    def cache_fingerprint(self) -> str:
        """Hash of cohort and cluster queue list for cache key segregation."""
        kueue_config = self._kueue_config
        raw = "|".join(sorted(kueue_config.cluster_queues)) + "|" + kueue_config.cohort
        return hashlib.sha256(raw.encode("utf-8")).hexdigest()[:24]

    async def startup(self) -> None:
        """List ClusterQueues and fetch configured cohort/queues to validate RBAC."""
        kueue_config = self._kueue_config
        if not kueue_config.kube_api_url:
            raise RuntimeStartupError(
                "METRICS_PROVIDERS__KUEUE__KUBE_API_URL is required when platform source is kueue"
            )
        if not kueue_config.cluster_queues:
            raise RuntimeStartupError(
                "METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES must list at least one ClusterQueue"
            )
        if not kueue_config.cohort:
            raise RuntimeStartupError(
                "METRICS_PROVIDERS__KUEUE__COHORT is required for platform metrics"
            )
        token = resolve_kube_token(kueue_config.kube_api_token, kueue_config.token_file)
        if not token:
            raise RuntimeStartupError(
                "No Kubernetes API bearer token: set token_file or "
                "METRICS_PROVIDERS__KUEUE__KUBE_API_TOKEN or mount a service account token"
            )
        headers = kube_auth_headers(token)

        list_url = kueue_clusterqueues_list_url(kueue_config)
        try:
            await kube_get_json(self._client, list_url, headers=headers)
        except httpx.HTTPStatusError as exc:
            if exc.response is not None and exc.response.status_code in (403, 404):
                raise RuntimeStartupError(
                    f"Kueue ClusterQueue API is not reachable or not installed (HTTP {exc.response.status_code})"
                ) from exc
            raise RuntimeStartupError(f"Kubernetes request failed: {exc}") from exc
        except httpx.RequestError as exc:
            raise RuntimeStartupError(
                f"Cannot reach Kubernetes API for Kueue checks: {exc}"
            ) from exc

        cohort_url = cohort_object_url(kueue_config, kueue_config.cohort)
        queue_urls = [
            cluster_queue_object_url(kueue_config, q) for q in kueue_config.cluster_queues
        ]
        try:
            docs = await kube_parallel_get_json(
                self._client, [cohort_url, *queue_urls], headers=headers
            )
        except httpx.HTTPStatusError as exc:
            if exc.response is not None and exc.response.status_code == 404:
                raise RuntimeStartupError(
                    "Configured cohort or a ClusterQueue was not found in the cluster"
                ) from exc
            raise RuntimeStartupError(
                f"Kubernetes request failed during Kueue validation: {exc}"
            ) from exc
        except httpx.RequestError as exc:
            raise RuntimeStartupError(
                f"Cannot reach Kubernetes API for Kueue validation: {exc}"
            ) from exc
        cohort_doc, *cqs = docs
        if not isinstance(cohort_doc, dict):
            raise RuntimeStartupError("Unexpected response when loading Cohort object")
        meta = cohort_doc.get("metadata") or {}
        if meta.get("name") != kueue_config.cohort:
            raise RuntimeStartupError("Cohort response metadata did not match cohort name")
        for cq, qname in zip(cqs, kueue_config.cluster_queues, strict=True):
            if not isinstance(cq, dict):
                raise RuntimeStartupError(
                    f"Unexpected response when loading ClusterQueue {qname!r}"
                )
            spec = cq.get("spec") or {}
            cohort_ref = str(spec.get("cohortName") or spec.get("cohort") or "")
            if cohort_ref != kueue_config.cohort:
                raise RuntimeStartupError(
                    f"ClusterQueue {qname!r} has cohort {cohort_ref!r}, expected {kueue_config.cohort!r}"
                )

    async def shutdown(self) -> None:
        """Kueue does not hold resources beyond the shared client; no-op."""
        return None
