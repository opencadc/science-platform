"""Collect Metrics observations from Kueue v1beta2 queue objects."""

from __future__ import annotations

import asyncio
import hashlib
import json
import re
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from datetime import UTC, datetime
from decimal import Decimal
from typing import Any, Literal, TypeVar

import httpx
import kr8s
import kr8s.asyncio

from metrics.core.settings import KueueProviderConfig, Settings
from metrics.errors import (
    ProviderExecutionError,
    ProviderUnavailableError,
    RuntimeStartupError,
    SubjectNotFoundError,
)
from metrics.services.models import CommunityObservation, PlatformObservation, UserObservation
from metrics.services.resources import (
    format_resource_amount,
    merge_resource_totals,
    parse_resource_amount,
)


_KUEUE_API_VERSION = "kueue.x-k8s.io/v1beta2"
_COMMUNITY_LABEL = "canfar.net/community"
_USERNAME_LABEL = "canfar.net/username"
_LABEL_VALUE = re.compile(r"^[A-Za-z0-9](?:[-A-Za-z0-9_.]{0,61}[A-Za-z0-9])?$")
_MAX_LIST_PAGES = 1_000
_MAX_CONTINUE_TOKEN_LENGTH = 4_096
_MAX_RESULT_OBJECTS = 3_000
_LIST_PAGE_LIMIT = 100
_READ_CONCURRENCY = 4
_REQUEST_ERRORS = (
    kr8s.APITimeoutError,
    kr8s.ConnectionClosedError,
    httpx.HTTPError,
)

_Input = TypeVar("_Input")
_Output = TypeVar("_Output")


def _observation_time() -> datetime:
    """Return a UTC timestamp with millisecond precision."""
    now = datetime.now(UTC)
    return now.replace(microsecond=now.microsecond // 1_000 * 1_000)


def _validate_subject(value: str) -> str:
    """Validate a subject before interpolating it into a label selector."""
    if not isinstance(value, str) or _LABEL_VALUE.fullmatch(value) is None:
        raise ProviderExecutionError("Kueue subject value is not a valid label value")
    return value


async def create_kube_api(config: KueueProviderConfig) -> Any:
    """Build a kr8s API handle from in-cluster credentials or kubeconfig."""
    api = await kr8s.asyncio.api()
    api.timeout = config.kube_request_timeout_seconds
    return api


def _json_response(response: Any, kind: str) -> dict[str, Any]:
    """Decode one Kubernetes JSON object without exposing upstream details."""
    try:
        payload = response.json()
    except (json.JSONDecodeError, UnicodeDecodeError, TypeError, ValueError) as exc:
        raise ProviderExecutionError(f"Kueue {kind} payload was not valid JSON") from exc
    if not isinstance(payload, dict):
        raise ProviderExecutionError(f"Kueue {kind} payload was not an object")
    return payload


async def _bounded_map(
    items: list[_Input],
    operation: Callable[[_Input], Awaitable[_Output]],
) -> list[_Output]:
    """Run independent operations with bounded workers and ordered results."""
    next_index = 0
    results: dict[int, _Output] = {}

    async def worker() -> None:
        nonlocal next_index
        while next_index < len(items):
            index = next_index
            next_index += 1
            results[index] = await operation(items[index])

    tasks = [asyncio.create_task(worker()) for _ in range(min(_READ_CONCURRENCY, len(items)))]
    try:
        await asyncio.gather(*tasks)
    except BaseException:
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        raise
    return [results[index] for index in range(len(items))]


async def _fetch_cluster_queue_doc(
    api: Any,
    api_version: str,
    name: str,
) -> dict[str, Any]:
    """Fetch and identity-check one configured ClusterQueue."""
    async with api.call_api(
        method="GET",
        version=api_version,
        url=f"clusterqueues/{name}",
    ) as response:
        doc = _json_response(response, "ClusterQueue")
    metadata = doc.get("metadata")
    if metadata is not None:
        if not isinstance(metadata, dict) or metadata.get("name") != name:
            raise ProviderExecutionError("Kueue ClusterQueue identity did not match its request")
    return doc


async def fetch_cluster_queue_docs(
    api: Any,
    api_version: str,
    names: list[str],
) -> list[dict[str, Any]]:
    """Fetch configured ClusterQueues with named GET requests."""
    if len(names) > _MAX_RESULT_OBJECTS:
        raise ProviderExecutionError("Kueue ClusterQueue result exceeded the result limit")

    async def fetch(name: str) -> dict[str, Any]:
        return await _fetch_cluster_queue_doc(api, api_version, name)

    return await _bounded_map(names, fetch)


async def fetch_local_queue_docs(
    api: Any,
    api_version: str,
    namespace: str,
    *,
    label_selector: str | None = None,
) -> list[dict[str, Any]]:
    """List all LocalQueues in one configured namespace."""
    items: list[dict[str, Any]] = []
    continue_token: str | None = None
    seen_tokens: set[str] = set()
    for _page in range(_MAX_LIST_PAGES):
        params = {"limit": str(_LIST_PAGE_LIMIT)}
        if label_selector:
            params["labelSelector"] = label_selector
        if continue_token is not None:
            params["continue"] = continue_token
        async with api.call_api(
            method="GET",
            version=api_version,
            namespace=namespace,
            url="localqueues",
            params=params,
        ) as response:
            payload = _json_response(response, "LocalQueue list")
        page_items = payload.get("items")
        if not isinstance(page_items, list) or not all(
            isinstance(item, dict) for item in page_items
        ):
            raise ProviderExecutionError("Kueue LocalQueue list contained an invalid object shape")
        if len(items) + len(page_items) > _MAX_RESULT_OBJECTS:
            raise ProviderExecutionError("Kueue LocalQueue result exceeded the result limit")
        items.extend(page_items)

        metadata = payload.get("metadata")
        if metadata is None:
            return items
        if not isinstance(metadata, dict):
            raise ProviderExecutionError("Kueue LocalQueue list metadata was invalid")
        next_token = metadata.get("continue")
        if next_token in (None, ""):
            return items
        if (
            not isinstance(next_token, str)
            or len(next_token) > _MAX_CONTINUE_TOKEN_LENGTH
            or next_token in seen_tokens
        ):
            raise ProviderExecutionError("Kueue LocalQueue pagination token was invalid")
        seen_tokens.add(next_token)
        continue_token = next_token
    raise ProviderExecutionError("Kueue LocalQueue list exceeded the pagination limit")


async def probe_local_queue_access(api: Any, api_version: str, namespace: str) -> None:
    """Probe namespaced LocalQueue list access without following pagination."""
    async with api.call_api(
        method="GET",
        version=api_version,
        namespace=namespace,
        url="localqueues",
        params={"limit": "1"},
    ) as response:
        payload = _json_response(response, "LocalQueue access probe")
    items = payload.get("items")
    if not isinstance(items, list) or not all(isinstance(item, dict) for item in items):
        raise ProviderExecutionError("Kueue LocalQueue access probe returned an invalid list")
    metadata = payload.get("metadata")
    if metadata is not None and not isinstance(metadata, dict):
        raise ProviderExecutionError("Kueue LocalQueue access probe metadata was invalid")


def _mapping(value: object, message: str) -> dict[str, Any]:
    """Require one decoded Kubernetes object member to be a mapping."""
    if not isinstance(value, dict):
        raise ProviderExecutionError(message)
    return value


def _list(value: object, message: str) -> list[Any]:
    """Require one decoded Kubernetes object member to be a list."""
    if not isinstance(value, list):
        raise ProviderExecutionError(message)
    return value


def _labels(doc: dict[str, Any], kind: str) -> dict[str, str]:
    """Return a validated Kubernetes label map."""
    metadata = _mapping(doc.get("metadata"), f"Kueue {kind} metadata was invalid")
    labels = _mapping(metadata.get("labels"), f"Kueue {kind} labels were missing or invalid")
    if not all(isinstance(name, str) and isinstance(value, str) for name, value in labels.items()):
        raise ProviderExecutionError(f"Kueue {kind} labels were invalid")
    return labels


def _required_label(labels: dict[str, str], name: str, kind: str) -> str:
    """Require one nonempty exact label value."""
    value = labels.get(name)
    if not isinstance(value, str) or not value:
        raise ProviderExecutionError(f"Kueue {kind} was missing a nonempty {name} label")
    return value


def _cluster_queue_community(doc: dict[str, Any]) -> str:
    """Validate and return a configured ClusterQueue's community label."""
    return _required_label(_labels(doc, "ClusterQueue"), _COMMUNITY_LABEL, "ClusterQueue")


def _metadata_namespace(doc: dict[str, Any], namespace: str) -> None:
    """Require a LocalQueue list item to belong to the namespace queried."""
    metadata = _mapping(doc.get("metadata"), "Kueue LocalQueue metadata was invalid")
    if metadata.get("namespace") != namespace:
        raise ProviderExecutionError("Kueue LocalQueue was returned from the wrong namespace")


def _local_queue_identities(doc: dict[str, Any], namespace: str) -> tuple[tuple[str, str], ...]:
    """Return Kubernetes object identities for one LocalQueue list item."""
    metadata = _mapping(doc.get("metadata"), "Kueue LocalQueue metadata was invalid")
    name = metadata.get("name")
    if not isinstance(name, str) or not name:
        raise ProviderExecutionError("Kueue LocalQueue metadata name was missing or invalid")
    identities = [("name", f"{namespace}/{name}")]
    uid = metadata.get("uid")
    if uid is not None:
        if not isinstance(uid, str) or not uid:
            raise ProviderExecutionError("Kueue LocalQueue metadata uid was invalid")
        identities.append(("uid", uid))
    return tuple(identities)


def _status(doc: dict[str, Any], kind: str) -> dict[str, Any]:
    """Validate the common Kueue queue status shape."""
    status = _mapping(doc.get("status"), f"Kueue {kind} status was missing or invalid")
    reserving = status.get("reservingWorkloads")
    if reserving is None:
        reserving = 0
    elif isinstance(reserving, bool) or not isinstance(reserving, int) or reserving < 0:
        raise ProviderExecutionError(f"Kueue {kind} reservingWorkloads was invalid")
    normalized = dict(status)
    normalized["reservingWorkloads"] = reserving
    for field_name in ("flavorsReservation", "flavorsUsage"):
        raw_flavors = status.get(field_name)
        flavors = (
            []
            if raw_flavors is None
            else _list(raw_flavors, f"Kueue {kind} {field_name} was invalid")
        )
        for flavor in flavors:
            flavor_map = _mapping(flavor, f"Kueue {kind} {field_name} entry was invalid")
            resources = _list(
                flavor_map.get("resources"),
                f"Kueue {kind} {field_name} resources were missing or invalid",
            )
            if not resources:
                raise ProviderExecutionError(f"Kueue {kind} {field_name} resources were empty")
        normalized[field_name] = flavors
    return normalized


def _merge_resources(
    totals: dict[str, Decimal],
    resources: object,
    value_key: Literal["nominalQuota", "total"],
) -> None:
    """Add one Kueue resource list to a public-unit aggregate."""
    entries = _list(resources, "Kueue resource list was missing or invalid")
    names: set[str] = set()
    for entry in entries:
        resource = _mapping(entry, "Kueue resource entry was invalid")
        name = resource.get("name")
        if not isinstance(name, str) or not name or name != name.strip() or name in names:
            raise ProviderExecutionError("Kueue resource entry had a duplicate or invalid name")
        names.add(name)
        merge_resource_totals(totals, name, parse_resource_amount(name, resource.get(value_key)))


def _nominal_resources(doc: dict[str, Any]) -> dict[str, Decimal]:
    """Aggregate one ClusterQueue's complete nominal quota."""
    spec = _mapping(doc.get("spec"), "Kueue ClusterQueue spec was invalid")
    groups = _list(spec.get("resourceGroups"), "Kueue ClusterQueue resourceGroups was invalid")
    if not groups:
        raise ProviderExecutionError("Kueue ClusterQueue resourceGroups was empty")
    totals: dict[str, Decimal] = {}
    for group_value in groups:
        group = _mapping(group_value, "Kueue ClusterQueue resource group was invalid")
        flavors = _list(group.get("flavors"), "Kueue ClusterQueue flavors were invalid")
        if not flavors:
            raise ProviderExecutionError("Kueue ClusterQueue flavors were empty")
        for flavor_value in flavors:
            flavor = _mapping(flavor_value, "Kueue ClusterQueue flavor was invalid")
            resources = _list(flavor.get("resources"), "Kueue ClusterQueue resources were invalid")
            if not resources:
                raise ProviderExecutionError("Kueue ClusterQueue resources were empty")
            _merge_resources(totals, resources, "nominalQuota")
    if not totals:
        raise ProviderExecutionError("Kueue ClusterQueue had no nominal resources")
    return totals


@dataclass(frozen=True, slots=True)
class _QueueValues:
    """Hold reservation, usage, capacity, and queue-count values."""

    capacity: dict[str, Decimal]
    allocated: dict[str, Decimal]
    requests: dict[str, Decimal]
    reserving_workloads: int


def _queue_values(doc: dict[str, Any], *, include_capacity: bool) -> _QueueValues:
    """Validate and aggregate one ClusterQueue or LocalQueue."""
    status = _status(doc, "queue")
    capacity = _nominal_resources(doc) if include_capacity else {}
    reservation: dict[str, Decimal] = {}
    usage: dict[str, Decimal] = {}
    for flavor in status["flavorsReservation"]:
        _merge_resources(
            reservation, _mapping(flavor, "Kueue reservation was invalid")["resources"], "total"
        )
    for flavor in status["flavorsUsage"]:
        _merge_resources(usage, _mapping(flavor, "Kueue usage was invalid")["resources"], "total")
    if set(reservation) - set(capacity) and include_capacity:
        raise ProviderExecutionError("Kueue reservation contained a resource absent from capacity")
    if set(usage) - set(capacity) and include_capacity:
        raise ProviderExecutionError("Kueue usage contained a resource absent from capacity")
    return _QueueValues(
        capacity=capacity,
        allocated=usage,
        requests=reservation,
        reserving_workloads=status["reservingWorkloads"],
    )


def _resource_maps(values: dict[str, Decimal]) -> dict[str, str]:
    """Format an aggregate resource map deterministically."""
    return {name: format_resource_amount(name, value) for name, value in sorted(values.items())}


def _platform_values(docs: list[dict[str, Any]]) -> _QueueValues:
    """Aggregate configured ClusterQueues into Platform values."""
    capacity: dict[str, Decimal] = {}
    allocated: dict[str, Decimal] = {}
    requests: dict[str, Decimal] = {}
    reserving = 0
    for doc in docs:
        values = _queue_values(doc, include_capacity=True)
        for name, value in values.capacity.items():
            merge_resource_totals(capacity, name, value)
        for name, value in values.allocated.items():
            merge_resource_totals(allocated, name, value)
        for name, value in values.requests.items():
            merge_resource_totals(requests, name, value)
        reserving += values.reserving_workloads
    if not capacity:
        raise ProviderExecutionError("Kueue platform had no configured capacity")
    allocated = {name: allocated.get(name, Decimal(0)) for name in capacity}
    return _QueueValues(capacity, allocated, requests, reserving)


class KueueProvider:
    """Read User, Community, and Platform observations from Kueue."""

    def __init__(self, settings: Settings, api: Any | None = None) -> None:
        """Attach validated settings and an optional kr8s-compatible API fake."""
        self._settings = settings
        self._config = settings.providers.kueue
        self._api = api

    @property
    def name(self) -> str:
        """Return the stable provider name used in telemetry."""
        return "kueue"

    async def _ensure_api(self) -> Any:
        """Create and retain the Kubernetes API handle on first use."""
        if self._api is None:
            try:
                self._api = await create_kube_api(self._config)
            except Exception as exc:
                raise ProviderUnavailableError("Could not configure Kubernetes API access") from exc
        return self._api

    async def _configured_cluster_queues(self) -> list[dict[str, Any]]:
        """Read and validate every configured ClusterQueue atomically."""
        api = await self._ensure_api()
        try:
            docs = await fetch_cluster_queue_docs(
                api,
                self._config.kueue_api_version,
                self._config.cluster_queues,
            )
        except kr8s.ServerError as exc:
            raise ProviderUnavailableError("Configured Kueue ClusterQueue access failed") from exc
        except _REQUEST_ERRORS as exc:
            raise ProviderUnavailableError("Kueue ClusterQueue access failed") from exc
        for doc in docs:
            _cluster_queue_community(doc)
            _queue_values(doc, include_capacity=True)
        return docs

    async def _local_queues(self, subject: str | None) -> list[tuple[str, dict[str, Any]]]:
        """Read every configured namespace, preserving all-or-nothing semantics."""
        api = await self._ensure_api()
        selector = None if subject is None else f"{_USERNAME_LABEL}={_validate_subject(subject)}"

        async def fetch(namespace: str) -> list[dict[str, Any]]:
            return await fetch_local_queue_docs(
                api,
                self._config.kueue_api_version,
                namespace,
                label_selector=selector,
            )

        try:
            docs_by_namespace = await _bounded_map(self._config.namespaces, fetch)
        except kr8s.ServerError as exc:
            raise ProviderUnavailableError("Configured Kueue namespace access failed") from exc
        except _REQUEST_ERRORS as exc:
            raise ProviderUnavailableError("Kueue namespace access failed") from exc
        return [
            (namespace, doc)
            for namespace, docs in zip(
                self._config.namespaces,
                docs_by_namespace,
                strict=True,
            )
            for doc in docs
        ]

    async def _probe_local_queue_access(self) -> None:
        """Probe LocalQueue list access once per configured namespace."""
        api = await self._ensure_api()

        async def probe(namespace: str) -> None:
            await probe_local_queue_access(
                api,
                self._config.kueue_api_version,
                namespace,
            )

        try:
            await _bounded_map(self._config.namespaces, probe)
        except kr8s.ServerError as exc:
            raise ProviderUnavailableError("Configured Kueue namespace access failed") from exc
        except _REQUEST_ERRORS as exc:
            raise ProviderUnavailableError("Kueue namespace access failed") from exc

    def _cluster_queue_labels(self, docs: list[dict[str, Any]]) -> dict[str, str]:
        """Return the configured ClusterQueue-to-community mapping."""
        labels: dict[str, str] = {}
        for configured_name, doc in zip(self._config.cluster_queues, docs, strict=True):
            metadata = _mapping(doc.get("metadata"), "Kueue ClusterQueue metadata was invalid")
            returned_name = metadata.get("name")
            if returned_name is not None and returned_name != configured_name:
                raise ProviderExecutionError(
                    "Kueue ClusterQueue identity did not match configuration"
                )
            labels[configured_name] = _cluster_queue_community(doc)
        return labels

    def cache_fingerprint(self) -> str:
        """Return a stable cache revision for the configured Kueue population."""
        raw = json.dumps(
            {
                "api_version": self._config.kueue_api_version,
                "cluster_queues": self._config.cluster_queues,
                "namespaces": self._config.namespaces,
            },
            separators=(",", ":"),
            sort_keys=True,
        )
        return hashlib.sha256(raw.encode()).hexdigest()[:24]

    async def read_platform(self) -> PlatformObservation:
        """Sum capacity, allocation, and reserving workloads across ClusterQueues."""
        docs = await self._configured_cluster_queues()
        values = _platform_values(docs)
        return PlatformObservation(
            cluster=self._settings.cluster_name,
            capacity=_resource_maps(values.capacity),
            allocated=_resource_maps(values.allocated),
            reserving_workloads=values.reserving_workloads,
            observed_at=_observation_time(),
        )

    async def read_community(self, community: str) -> CommunityObservation:
        """Sum reservation and reserving counts for matching ClusterQueues."""
        community = _validate_subject(community)
        docs = await self._configured_cluster_queues()
        matching = [doc for doc in docs if _cluster_queue_community(doc) == community]
        if not matching:
            raise SubjectNotFoundError("Community has no configured ClusterQueue")
        requests: dict[str, Decimal] = {}
        reserving = 0
        for doc in matching:
            queue = _queue_values(doc, include_capacity=True)
            for name, value in queue.requests.items():
                merge_resource_totals(requests, name, value)
            reserving += queue.reserving_workloads
        return CommunityObservation(
            community=community,
            requests=_resource_maps(requests),
            reserving_workloads=reserving,
            observed_at=_observation_time(),
        )

    async def read_user(self, username: str) -> UserObservation:
        """Aggregate valid LocalQueues for one user across configured namespaces."""
        username = _validate_subject(username)
        cluster_docs = await self._configured_cluster_queues()
        community_by_queue = self._cluster_queue_labels(cluster_docs)
        local_queues = await self._local_queues(username)
        if not local_queues:
            raise SubjectNotFoundError("User has no matching LocalQueue")

        requests: dict[str, Decimal] = {}
        reserving = 0
        seen_local_queues: set[tuple[str, str]] = set()
        for namespace, doc in local_queues:
            _metadata_namespace(doc, namespace)
            labels = _labels(doc, "LocalQueue")
            if _required_label(labels, _USERNAME_LABEL, "LocalQueue") != username:
                raise ProviderExecutionError(
                    "Kueue LocalQueue username label did not match selector"
                )
            local_community = _required_label(labels, _COMMUNITY_LABEL, "LocalQueue")
            spec = _mapping(doc.get("spec"), "Kueue LocalQueue spec was invalid")
            cluster_queue = spec.get("clusterQueue")
            if not isinstance(cluster_queue, str) or not cluster_queue:
                raise ProviderExecutionError("Kueue LocalQueue clusterQueue was missing")
            expected_community = community_by_queue.get(cluster_queue)
            if expected_community is None:
                raise ProviderExecutionError(
                    "Kueue LocalQueue referenced an out-of-scope ClusterQueue"
                )
            if local_community != expected_community:
                raise ProviderExecutionError(
                    "Kueue LocalQueue community did not match its ClusterQueue"
                )
            identities = _local_queue_identities(doc, namespace)
            if any(identity in seen_local_queues for identity in identities):
                raise ProviderExecutionError("Kueue LocalQueue identity was duplicated")
            seen_local_queues.update(identities)
            queue = _queue_values(doc, include_capacity=False)
            for name, value in queue.requests.items():
                merge_resource_totals(requests, name, value)
            reserving += queue.reserving_workloads
        return UserObservation(
            user=username,
            requests=_resource_maps(requests),
            reserving_workloads=reserving,
            observed_at=_observation_time(),
        )

    async def startup(self) -> None:
        """Validate all configured ClusterQueues and namespace list access."""
        try:
            await self._configured_cluster_queues()
            await self._probe_local_queue_access()
        except (
            ProviderUnavailableError,
            ProviderExecutionError,
            kr8s.ServerError,
            *_REQUEST_ERRORS,
        ) as exc:
            raise RuntimeStartupError("Kueue dependency validation failed") from exc

    async def shutdown(self) -> None:
        """Release the provider's API handle reference."""
        self._api = None
