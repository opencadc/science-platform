"""Kueue-backed platform metrics, startup checks, URL building, and spec parsing."""

from __future__ import annotations

import asyncio
import hashlib
import json
import os
from dataclasses import dataclass
from decimal import Decimal
from pathlib import Path
from typing import Any

import httpx

from metrics.core.settings import KueueProviderConfig, Settings
from metrics.errors import (
    ProviderExecutionError,
    ProviderUnavailableError,
    RuntimeStartupError,
)
from metrics.quantity import (
    InvalidQuantityError,
    format_resource_amount,
    merge_resource_totals,
    parse_resource_amount,
)
from metrics.schemas.metrics import PlatformMetricsData


def resolve_kube_token(
    explicit: str | None,
    token_file: str | None = None,
) -> str | None:
    """Resolve bearer token: explicit value, then token file, then in-cluster file."""
    if explicit:
        return explicit
    if token_file:
        token_path = Path(token_file)
        if token_path.is_file():
            return token_path.read_text(encoding="utf-8").strip()
    path = Path(
        os.environ.get(
            "METRICS_KUBE_SA_TOKEN_PATH",
            "/var/run/secrets/kubernetes.io/serviceaccount/token",
        )
    )
    if path.is_file():
        return path.read_text(encoding="utf-8").strip()
    return None


def resolve_kube_verify(
    verify_tls: bool,
    *,
    ca_file: str | None = None,
) -> bool | str:
    """Return the TLS verification value for the Kueue HTTP client."""
    if not verify_tls:
        return False
    if ca_file:
        ca_path = Path(ca_file)
        if ca_path.is_file():
            return str(ca_path)
    ca = Path(
        os.environ.get(
            "METRICS_KUBE_SA_CA_PATH",
            "/var/run/secrets/kubernetes.io/serviceaccount/ca.crt",
        )
    )
    if ca.is_file():
        return str(ca)
    return True


def kube_auth_headers(token: str | None) -> dict[str, str]:
    """Build Kubernetes bearer-token headers when a token exists."""
    return {"Authorization": f"Bearer {token}"} if token else {}


async def kube_parallel_get_json(
    client: httpx.AsyncClient,
    urls: list[str],
    *,
    headers: dict[str, str],
) -> list[dict[str, Any]]:
    """GET Kueue URLs concurrently and return parsed JSON in request order."""
    if not urls:
        return []

    async def fetch_url(target: str) -> dict[str, Any]:
        response = await client.get(target, headers=headers)
        response.raise_for_status()
        try:
            document = response.json()
        except ValueError as exc:
            raise ProviderExecutionError(
                "Kubernetes returned invalid JSON querying Kueue objects"
            ) from exc
        if not isinstance(document, dict):
            raise ProviderExecutionError(
                "Kubernetes returned an invalid object querying Kueue objects"
            )
        return document

    return list(await asyncio.gather(*(fetch_url(url) for url in urls)))


async def kube_get_json(
    client: httpx.AsyncClient,
    url: str,
    *,
    headers: dict[str, str],
) -> dict[str, Any]:
    """GET one Kueue URL and return its parsed JSON body."""
    docs = await kube_parallel_get_json(client, [url], headers=headers)
    return docs[0]


def kueue_http_client(kueue_config: KueueProviderConfig) -> httpx.AsyncClient:
    """Build a shared HTTP/1.1 client for Kueue and Kubernetes GET calls.

    Args:
        kueue_config: Kueue provider settings (timeouts, TLS, and pool sizes).

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
        timeout=httpx.Timeout(kueue_config.kube_request_timeout_seconds),
        verify=verify,
    )


def cluster_queue_object_url(kueue_config: KueueProviderConfig, queue_name: str) -> str:
    """Return the get-by-name URL for a ``ClusterQueue`` custom resource."""
    base = (kueue_config.kube_api_url or "").rstrip("/")
    return f"{base}{kueue_config.kube_clusterqueue_path}/{queue_name}"


def sum_nominal_quotas_by_resource(doc: dict[str, Any]) -> dict[str, Decimal]:
    """Sum ``nominalQuota`` for every resource across all groups and flavors.

    JSON follows Kueue v1beta2 CRDs: each ``resourceGroups`` entry lists
    ``flavors``, each flavor lists ``resources`` with ``nominalQuota`` quantities
    compatible with Kubernetes resource.Quantity syntax.

    Resource **names** are taken verbatim from the API (for example ``cpu``,
    ``memory``, ``nvidia.com/gpu``) so the platform contract can surface future
    resource types without schema changes. Values are exact decimals in public
    response units: cores for CPU, gibibytes for storage, and base units for
    extended resources.

    Args:
        doc: A ``ClusterQueue`` or ``Cohort`` API object (dict with ``spec``).

    Returns:
        Mapping of resource name to an exact aggregated amount.

    """
    totals: dict[str, Decimal] = {}
    spec = doc.get("spec") or {}
    for group in spec.get("resourceGroups") or []:
        for flavor in group.get("flavors") or []:
            for resource in flavor.get("resources") or []:
                name = str(resource.get("name", "")).strip()
                if not name:
                    continue
                merge_resource_totals(
                    totals,
                    name,
                    parse_resource_amount(name, resource.get("nominalQuota")),
                )
    return totals


def _sum_usage_from_status(doc: dict[str, Any]) -> dict[str, Decimal]:
    totals: dict[str, Decimal] = {}
    status = doc.get("status") or {}
    for flavor in status.get("flavorsUsage") or []:
        for resource in flavor.get("resources") or []:
            name = str(resource.get("name", "")).strip()
            if not name:
                continue
            merge_resource_totals(
                totals,
                name,
                parse_resource_amount(name, resource.get("total")),
            )
    return totals


def _resource_maps_to_strings(values: dict[str, Decimal]) -> dict[str, str]:
    return {name: format_resource_amount(name, val) for name, val in sorted(values.items())}


def _align_allocated_with_capacity(
    capacity: dict[str, str],
    allocated: dict[str, str],
) -> dict[str, str]:
    out = dict(allocated)
    for name in capacity:
        if name not in out:
            out[name] = format_resource_amount(name, Decimal(0))
    return dict(sorted(out.items()))


@dataclass(slots=True)
class _PlatformResourceMaps:
    capacity: dict[str, str]
    allocated: dict[str, str]


class KueueProvider:
    """Kueue source: startup validation and platform metrics."""

    def __init__(self, settings: Settings, client: httpx.AsyncClient) -> None:
        """Attach settings and the client owned by this provider.

        Args:
            settings: Full app settings; Kueue fields live under ``providers.kueue``.
            client: Async HTTP client used for Kubernetes API traffic.
        """
        self._settings = settings
        self._client = client
        self._kueue_config = settings.providers.kueue
        self._client_closed = False

    @property
    def name(self) -> str:
        """Stable provider key matching configuration."""
        return "kueue"

    async def platform(self) -> PlatformMetricsData:
        """Load capacity and allocated maps from Kueue ClusterQueue data."""
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
        if not kueue_config.cluster_queues:
            raise ProviderUnavailableError(
                "Kueue cluster_queues must be configured for platform metrics"
            )
        token = resolve_kube_token(kueue_config.kube_api_token, kueue_config.token_file)
        if not token:
            raise ProviderUnavailableError(
                "No Kubernetes API bearer token available for Kueue calls"
            )
        headers = kube_auth_headers(token)

        queue_urls = [
            cluster_queue_object_url(kueue_config, queue_name)
            for queue_name in kueue_config.cluster_queues
        ]

        try:
            docs = await kube_parallel_get_json(
                self._client,
                queue_urls,
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

        queue_totals: dict[str, Decimal] = {}
        allocated_totals: dict[str, Decimal] = {}

        try:
            for item in docs:
                for resource_name, value in sum_nominal_quotas_by_resource(item).items():
                    merge_resource_totals(queue_totals, resource_name, value)
                for resource_name, value in _sum_usage_from_status(item).items():
                    merge_resource_totals(allocated_totals, resource_name, value)
        except InvalidQuantityError as exc:
            raise ProviderExecutionError(
                "Kueue platform data contained an invalid resource quantity"
            ) from exc
        except (AttributeError, TypeError) as exc:
            raise ProviderExecutionError(
                "Kueue platform data contained an invalid object shape"
            ) from exc
        if not queue_totals:
            raise ProviderUnavailableError(
                "Kueue ClusterQueue specs did not include nominal quota values"
            )
        capacity = _resource_maps_to_strings(queue_totals)
        allocated = _align_allocated_with_capacity(
            capacity,
            _resource_maps_to_strings(allocated_totals),
        )
        return _PlatformResourceMaps(capacity=capacity, allocated=allocated)

    def cache_fingerprint(self) -> str:
        """Hash non-secret provider identity for cache key segregation."""
        kueue_config = self._kueue_config
        raw = json.dumps(
            {
                "endpoint": (kueue_config.kube_api_url or "").rstrip("/"),
                "name": self.name,
                "path": kueue_config.kube_clusterqueue_path,
                "queues": sorted(kueue_config.cluster_queues),
            },
            sort_keys=True,
            separators=(",", ":"),
        )
        return hashlib.sha256(raw.encode("utf-8")).hexdigest()[:24]

    async def startup(self) -> None:
        """Fetch configured ClusterQueues concurrently to validate access."""
        kueue_config = self._kueue_config
        if not kueue_config.kube_api_url:
            raise RuntimeStartupError(
                "METRICS_PROVIDERS__KUEUE__KUBE_API_URL is required when platform source is kueue"
            )
        if not kueue_config.cluster_queues:
            raise RuntimeStartupError(
                "METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES must list at least one ClusterQueue"
            )
        token = resolve_kube_token(kueue_config.kube_api_token, kueue_config.token_file)
        if not token:
            raise RuntimeStartupError(
                "No Kubernetes API bearer token: set token_file or "
                "METRICS_PROVIDERS__KUEUE__KUBE_API_TOKEN or mount a service account token"
            )
        headers = kube_auth_headers(token)

        async def validate_queue(qname: str) -> None:
            queue_url = cluster_queue_object_url(kueue_config, qname)
            try:
                await kube_get_json(self._client, queue_url, headers=headers)
            except httpx.HTTPStatusError as exc:
                status_code = exc.response.status_code
                if status_code == 404:
                    raise RuntimeStartupError(
                        f"Configured ClusterQueue {qname!r} was not found in the cluster"
                    ) from exc
                if status_code == 403:
                    raise RuntimeStartupError(
                        f"Configured ClusterQueue {qname!r} is forbidden (HTTP 403)"
                    ) from exc
                raise RuntimeStartupError(
                    f"Failed loading ClusterQueue {qname!r} (HTTP {status_code})"
                ) from exc
            except httpx.RequestError as exc:
                raise RuntimeStartupError(
                    "Cannot reach Kubernetes API for Kueue startup checks"
                ) from exc

        await asyncio.gather(*(validate_queue(qname) for qname in kueue_config.cluster_queues))

    async def shutdown(self) -> None:
        """Close this provider's injected HTTP client exactly once."""
        if self._client_closed:
            return
        await self._client.aclose()
        self._client_closed = True
