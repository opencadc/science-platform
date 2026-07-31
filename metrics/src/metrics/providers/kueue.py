"""Kueue-backed platform metrics: kr8s reads, quantity handling, startup checks."""

from __future__ import annotations

import asyncio
import hashlib
import json
from collections.abc import Coroutine
from dataclasses import dataclass
from math import isfinite
from typing import Any, TypeVar

import httpx
import kr8s
import kr8s.asyncio
from kr8s.asyncio.objects import new_class
from quantiphy import QuantiPhyError, Quantity

from metrics.core.settings import KueueProviderConfig, Settings
from metrics.errors import (
    ProviderExecutionError,
    ProviderUnavailableError,
    RuntimeStartupError,
)
from metrics.schemas.metrics import PlatformMetricsData

_Result = TypeVar("_Result")

# Upper bound on concurrent GETs within one platform load; single-flight in
# PlatformMetricsService already guarantees at most one load runs per process.
DEFAULT_MAX_PARALLEL_KUBE_GETS = 4

_MAX_QUANTITY = float(2**63)
_GIB = float(2**30)
_STORAGE_RESOURCES = frozenset({"memory", "ephemeral-storage"})
_INVALID_QUANTITY_MESSAGE = "Kueue platform data contained an invalid resource quantity"

_KUBE_REQUEST_ERRORS = (
    kr8s.APITimeoutError,
    kr8s.ConnectionClosedError,
    httpx.HTTPError,
    TimeoutError,
    OSError,
)


async def _gather_cancel_on_error(
    *coroutines: Coroutine[Any, Any, _Result],
) -> list[_Result]:
    """Gather in order, cancelling and awaiting siblings before any error escapes."""
    tasks = [asyncio.create_task(coroutine) for coroutine in coroutines]
    try:
        return list(await asyncio.gather(*tasks))
    except BaseException:
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        raise


# --- Resource quantities (quantiphy-backed, public response units) ---


def parse_resource_amount(resource_name: str, raw: object) -> float:
    """Parse a Kubernetes quantity string into its public response unit.

    Units follow the platform contract: cores for CPU, gibibytes for storage
    resources, and base units for extended resources. Parsing is delegated to
    ``quantiphy`` (SI and binary suffixes, scientific notation); values are
    floats, so totals are accurate to well past the 6 decimal places the API
    formats, not bit-exact.

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
    base_value = value * _GIB if resource_name.lower() in _STORAGE_RESOURCES else value
    if not isfinite(base_value) or base_value < 0 or base_value >= _MAX_QUANTITY:
        raise ProviderExecutionError(_INVALID_QUANTITY_MESSAGE)


def format_resource_amount(resource_name: str, value: float) -> str:
    """Format a resource total for API payloads: ≤6 decimals, no scientific notation."""
    _validate_resource_amount(resource_name, value)
    text = f"{value:.6f}".rstrip("0").rstrip(".")
    if resource_name.lower() in _STORAGE_RESOURCES:
        return f"{text}Gi"
    return text


def merge_resource_totals(target: dict[str, float], name: str, delta: float) -> None:
    """Accumulate a resource total while retaining valid zero values."""
    if not name:
        return
    total = target.get(name, 0.0) + delta
    _validate_resource_amount(name, total)
    target[name] = total


# --- Kubernetes access via kr8s ---


_cluster_queue_classes: dict[str, type] = {}


def _cluster_queue_class(api_version: str) -> type:
    cls = _cluster_queue_classes.get(api_version)
    if cls is None:
        cls = new_class(kind="ClusterQueue", version=api_version, namespaced=False)
        _cluster_queue_classes[api_version] = cls
    return cls


async def create_kube_api(kueue_config: KueueProviderConfig) -> Any:
    """Build a kr8s API handle using its own discovery (in-cluster SA or kubeconfig)."""
    api = await kr8s.asyncio.api()
    api.timeout = kueue_config.kube_request_timeout_seconds
    return api


async def fetch_cluster_queue_docs(
    api: Any,
    api_version: str,
    names: list[str],
    *,
    max_concurrency: int = DEFAULT_MAX_PARALLEL_KUBE_GETS,
) -> list[dict[str, Any]]:
    """Fetch ClusterQueue objects by name with bounded concurrency, in request order.

    Raises:
        kr8s.NotFoundError: When a named ClusterQueue does not exist.
        kr8s.ServerError: For non-404 API server errors.
    """
    if not names:
        return []
    semaphore = asyncio.Semaphore(max(1, max_concurrency))
    queue_class = _cluster_queue_class(api_version)

    async def fetch_one(name: str) -> dict[str, Any]:
        async with semaphore:
            matches = [obj async for obj in api.get(queue_class, name)]
        if not matches:
            raise kr8s.NotFoundError(f"ClusterQueue {name!r} was not found")
        raw = matches[0].raw
        return raw if isinstance(raw, dict) else dict(raw)

    return await _gather_cancel_on_error(*(fetch_one(name) for name in names))


# --- ClusterQueue document aggregation ---


def sum_nominal_quotas_by_resource(doc: dict[str, Any]) -> dict[str, float]:
    """Sum ``nominalQuota`` for every resource across all groups and flavors.

    JSON follows Kueue v1beta2 CRDs: each ``resourceGroups`` entry lists
    ``flavors``, each flavor lists ``resources`` with ``nominalQuota`` quantities
    compatible with Kubernetes resource.Quantity syntax.

    Resource **names** are taken verbatim from the API (for example ``cpu``,
    ``memory``, ``nvidia.com/gpu``) so the platform contract can surface future
    resource types without schema changes. Values use public response units:
    cores for CPU, gibibytes for storage, and base units for extended resources.

    Args:
        doc: A ``ClusterQueue`` API object (dict with ``spec``).

    Returns:
        Mapping of resource name to its aggregated amount.

    """
    totals: dict[str, float] = {}
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


def _sum_usage_from_status(doc: dict[str, Any]) -> dict[str, float]:
    totals: dict[str, float] = {}
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


def _resource_maps_to_strings(values: dict[str, float]) -> dict[str, str]:
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
        if self._api is None:
            try:
                self._api = await create_kube_api(self._kueue_config)
            except Exception as exc:
                # Do not embed str(exc): discovery errors can include paths/URLs.
                raise ProviderUnavailableError(
                    "Could not configure a Kubernetes API client for Kueue calls"
                ) from exc
        return self._api

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
        except kr8s.NotFoundError as exc:
            raise ProviderExecutionError(
                "Kubernetes returned HTTP 404 querying Kueue objects"
            ) from exc
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
            for item in docs:
                for resource_name, value in sum_nominal_quotas_by_resource(item).items():
                    merge_resource_totals(queue_totals, resource_name, value)
                for resource_name, value in _sum_usage_from_status(item).items():
                    merge_resource_totals(allocated_totals, resource_name, value)
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
                "api_version": kueue_config.kueue_api_version,
                "name": self.name,
                "queues": sorted(kueue_config.cluster_queues),
            },
            sort_keys=True,
            separators=(",", ":"),
        )
        return hashlib.sha256(raw.encode("utf-8")).hexdigest()[:24]

    async def startup(self) -> None:
        """Fetch configured ClusterQueues concurrently to validate access."""
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

        async def validate_queue(qname: str) -> None:
            try:
                await fetch_cluster_queue_docs(
                    api,
                    kueue_config.kueue_api_version,
                    [qname],
                )
            except kr8s.NotFoundError as exc:
                raise RuntimeStartupError(
                    f"Configured ClusterQueue {qname!r} was not found in the cluster"
                ) from exc
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

        await _gather_cancel_on_error(
            *(validate_queue(qname) for qname in kueue_config.cluster_queues)
        )

    async def shutdown(self) -> None:
        """Release the API handle; the kr8s session is process-shared and stays open."""
        self._api = None
