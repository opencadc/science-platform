"""Collect platform capacity and admitted allocation from Kueue ClusterQueues.

The provider performs named GETs to preserve get-only RBAC, validates
Kubernetes quantities, and emits comparable public units for capacity and
allocation.
"""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from math import isfinite
from typing import Any

import httpx
import kr8s
import kr8s.asyncio
from quantiphy import QuantiPhyError, Quantity

from metrics.core.settings import KueueProviderConfig, Settings
from metrics.errors import (
    ProviderExecutionError,
    ProviderUnavailableError,
    RuntimeStartupError,
)
from metrics.services.models import PlatformObservation

_MAX_QUANTITY = float(2**63)
_GIB = float(2**30)
_STORAGE_RESOURCES = frozenset({"memory", "ephemeral-storage"})
_INVALID_QUANTITY_MESSAGE = "Kueue platform data contained an invalid resource quantity"

_KUBE_REQUEST_ERRORS = (
    kr8s.APITimeoutError,
    kr8s.ConnectionClosedError,
    httpx.HTTPError,
)


# --- Resource quantities (quantiphy-backed, public response units) ---


def parse_resource_amount(resource_name: str, raw: object) -> float:
    """Parse a Kubernetes quantity string into its public response unit.

    Units follow the platform contract: cores for CPU, gibibytes for storage
    resources, and base units for extended resources. Parsing is delegated to
    ``quantiphy`` (SI and binary suffixes, scientific notation); values are
    floats, so totals are accurate to well past the 6 decimal places the API
    formats, not bit-exact.

    Args:
        resource_name: Kubernetes resource name that determines public units.
        raw: Kubernetes quantity text.

    Returns:
        Cores for CPU, GiB for storage resources, or base units otherwise.

    Raises:
        ProviderExecutionError: For non-strings, malformed syntax, surrounding
            whitespace, negatives, non-finite values, or values at or beyond
            2**63 in base units.
    """
    if not isinstance(raw, str) or not raw or raw != raw.strip() or len(raw) > 100:
        raise ProviderExecutionError(_INVALID_QUANTITY_MESSAGE)
    try:
        value = float(Quantity(raw, binary=True))
    except (QuantiPhyError, ValueError) as exc:
        raise ProviderExecutionError(_INVALID_QUANTITY_MESSAGE) from exc
    if not isfinite(value) or value < 0 or value >= _MAX_QUANTITY:
        raise ProviderExecutionError(_INVALID_QUANTITY_MESSAGE)
    if resource_name.lower() in _STORAGE_RESOURCES:
        return value / _GIB
    return value


def _validate_resource_amount(resource_name: str, value: float) -> None:
    """Require a finite non-negative total within the supported quantity range.

    Args:
        resource_name: Kubernetes resource name that determines base units.
        value: Numeric amount in public response units.
    """
    base_value = value * _GIB if resource_name.lower() in _STORAGE_RESOURCES else value
    if not isfinite(base_value) or base_value < 0 or base_value >= _MAX_QUANTITY:
        raise ProviderExecutionError(_INVALID_QUANTITY_MESSAGE)


def format_resource_amount(resource_name: str, value: float) -> str:
    """Format a validated resource total for API payloads.

    Args:
        resource_name: Kubernetes resource name that determines unit suffix.
        value: Numeric amount in public response units.

    Returns:
        At most six decimals without scientific notation, with ``Gi`` for
        storage resources.
    """
    _validate_resource_amount(resource_name, value)
    text = f"{value:.6f}".rstrip("0").rstrip(".")
    if resource_name.lower() in _STORAGE_RESOURCES:
        return f"{text}Gi"
    return text


def merge_resource_totals(target: dict[str, float], name: str, delta: float) -> None:
    """Accumulate and validate a resource total while retaining zero values.

    Args:
        target: Totals mutated in place.
        name: Kubernetes resource name.
        delta: Amount in the resource's public response unit.
    """
    total = target.get(name, 0.0) + delta
    _validate_resource_amount(name, total)
    target[name] = total


# --- Kubernetes access via kr8s ---


async def create_kube_api(kueue_config: KueueProviderConfig) -> Any:
    """Build a kr8s handle through in-cluster or kubeconfig discovery.

    Args:
        kueue_config: Provider settings supplying the request timeout.

    Returns:
        Configured asynchronous kr8s API handle.
    """
    api = await kr8s.asyncio.api()
    api.timeout = kueue_config.kube_request_timeout_seconds
    return api


async def fetch_cluster_queue_docs(
    api: Any,
    api_version: str,
    names: list[str],
) -> list[dict[str, Any]]:
    """Fetch ClusterQueue objects by name sequentially, in request order.

    Uses named GETs (``.../clusterqueues/{name}``) via ``call_api`` so the
    get-only RBAC contract holds: kr8s's object helpers resolve names with a
    LIST plus field selector, which would demand the ``list`` verb.

    Platform loads are already serialized by the single-flight service, and the
    configured queue list is small, so there is no parallel fan-out here.

    Args:
        api: kr8s-compatible API handle.
        api_version: Configured Kueue API group and version.
        names: Exact ClusterQueue names in desired response order.

    Returns:
        Decoded ClusterQueue documents in request order.

    Raises:
        kr8s.ServerError: For API server errors, including HTTP 404 when a
            named ClusterQueue does not exist.
    """
    docs: list[dict[str, Any]] = []
    for name in names:
        async with api.call_api(
            method="GET",
            version=api_version,
            url=f"clusterqueues/{name}",
        ) as response:
            docs.append(response.json())
    return docs


# --- ClusterQueue document aggregation ---


def _merge_resource_entries(
    totals: dict[str, float],
    resources: Any,
    value_key: str,
) -> None:
    """Accumulate ``resources[].{value_key}`` quantities into ``totals`` by name.

    Resource **names** are taken verbatim from the API (for example ``cpu``,
    ``memory``, ``nvidia.com/gpu``) so the platform contract can surface future
    resource types without schema changes; values use public response units.

    Args:
        totals: Resource totals mutated in place.
        resources: Kueue resource entry sequence.
        value_key: Quantity field to read from each entry.
    """
    for resource in resources or []:
        name = str(resource.get("name", "")).strip()
        if not name:
            continue
        merge_resource_totals(
            totals,
            name,
            parse_resource_amount(name, resource.get(value_key)),
        )


def _resource_maps_to_strings(values: dict[str, float]) -> dict[str, str]:
    """Format a numeric resource map in deterministic name order."""
    return {name: format_resource_amount(name, val) for name, val in sorted(values.items())}


@dataclass(slots=True)
class _PlatformResourceMaps:
    """Pair comparable formatted capacity and allocation maps."""

    capacity: dict[str, str]
    allocated: dict[str, str]


class KueueProvider:
    """Kueue source: startup validation and platform metrics."""

    def __init__(self, settings: Settings, api: Any | None = None) -> None:
        """Attach settings and an optional pre-built Kubernetes API handle.

        Args:
            settings: Full app settings; Kueue fields live under ``providers.kueue``.
            api: kr8s-compatible API object. Production leaves this ``None`` and the
                provider builds one lazily on first use; tests inject fakes.
        """
        self._settings = settings
        self._kueue_config = settings.providers.kueue
        self._api = api

    @property
    def name(self) -> str:
        """Stable provider key matching configuration."""
        return "kueue"

    async def _ensure_api(self) -> Any:
        """Create and retain the Kubernetes API handle on first use."""
        if self._api is None:
            try:
                self._api = await create_kube_api(self._kueue_config)
            except Exception as exc:
                # Do not embed str(exc): discovery errors can include paths/URLs.
                raise ProviderUnavailableError(
                    "Could not configure a Kubernetes API client for Kueue calls"
                ) from exc
        return self._api

    async def read_platform(self) -> PlatformObservation:
        """Load capacity and allocated maps from Kueue ClusterQueue data."""
        maps = await self._collect_resource_maps()
        return PlatformObservation(
            cluster=self._settings.cluster_name,
            capacity=maps.capacity,
            allocated=maps.allocated,
        )

    async def _collect_resource_maps(self) -> _PlatformResourceMaps:
        """Fetch configured queues and aggregate comparable resource maps."""
        kueue_config = self._kueue_config
        if not kueue_config.cluster_queues:
            raise ProviderUnavailableError(
                "Kueue cluster_queues must be configured for platform metrics"
            )
        api = await self._ensure_api()

        try:
            docs = await fetch_cluster_queue_docs(
                api,
                kueue_config.kueue_api_version,
                kueue_config.cluster_queues,
            )
        except kr8s.ServerError as exc:
            status_code = getattr(exc.response, "status_code", None)
            detail = f"HTTP {status_code}" if status_code else "a server error"
            raise ProviderExecutionError(
                f"Kubernetes returned {detail} querying Kueue objects"
            ) from exc
        except _KUBE_REQUEST_ERRORS as exc:
            # Do not embed str(exc) here: transports may include the request URL,
            # which must not propagate into API error payloads.
            raise ProviderExecutionError(
                "Failed querying Kueue objects (upstream request error)"
            ) from exc

        queue_totals: dict[str, float] = {}
        allocated_totals: dict[str, float] = {}

        try:
            for doc in docs:
                for group in (doc.get("spec") or {}).get("resourceGroups") or []:
                    for flavor in group.get("flavors") or []:
                        _merge_resource_entries(
                            queue_totals, flavor.get("resources"), "nominalQuota"
                        )
                for flavor in (doc.get("status") or {}).get("flavorsUsage") or []:
                    _merge_resource_entries(allocated_totals, flavor.get("resources"), "total")
        except (AttributeError, TypeError) as exc:
            raise ProviderExecutionError(
                "Kueue platform data contained an invalid object shape"
            ) from exc
        if not queue_totals:
            raise ProviderUnavailableError(
                "Kueue ClusterQueue specs did not include nominal quota values"
            )
        capacity = _resource_maps_to_strings(queue_totals)
        # Allocated keys align with capacity: absent usage renders as explicit zero.
        zeros = {name: format_resource_amount(name, 0.0) for name in queue_totals}
        allocated = dict(sorted((zeros | _resource_maps_to_strings(allocated_totals)).items()))
        return _PlatformResourceMaps(capacity=capacity, allocated=allocated)

    def cache_fingerprint(self) -> str:
        """Hash non-secret provider identity for cache key segregation."""
        kueue_config = self._kueue_config
        raw = json.dumps(
            {
                "api_version": kueue_config.kueue_api_version,
                "name": self.name,
                "queues": sorted(kueue_config.cluster_queues),
            },
            sort_keys=True,
            separators=(",", ":"),
        )
        return hashlib.sha256(raw.encode("utf-8")).hexdigest()[:24]

    async def startup(self) -> None:
        """Fetch each configured ClusterQueue to validate access, failing fast."""
        kueue_config = self._kueue_config
        if not kueue_config.cluster_queues:
            raise RuntimeStartupError(
                "METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES must list at least one ClusterQueue"
            )
        try:
            api = await self._ensure_api()
        except ProviderUnavailableError as exc:
            raise RuntimeStartupError(
                "Cannot configure Kubernetes API access for Kueue startup checks"
            ) from exc

        for qname in kueue_config.cluster_queues:
            try:
                await fetch_cluster_queue_docs(api, kueue_config.kueue_api_version, [qname])
            except kr8s.ServerError as exc:
                status_code = getattr(exc.response, "status_code", None)
                if status_code == 404:
                    raise RuntimeStartupError(
                        f"Configured ClusterQueue {qname!r} was not found in the cluster"
                    ) from exc
                if status_code == 403:
                    raise RuntimeStartupError(
                        f"Configured ClusterQueue {qname!r} is forbidden (HTTP 403)"
                    ) from exc
                detail = f"HTTP {status_code}" if status_code else "a server error"
                raise RuntimeStartupError(
                    f"Failed loading ClusterQueue {qname!r} ({detail})"
                ) from exc
            except _KUBE_REQUEST_ERRORS as exc:
                raise RuntimeStartupError(
                    "Cannot reach Kubernetes API for Kueue startup checks"
                ) from exc

    async def shutdown(self) -> None:
        """Release the API handle; the kr8s session is process-shared and stays open."""
        self._api = None
