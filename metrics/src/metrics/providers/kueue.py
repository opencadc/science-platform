"""Collect Metrics observations from Kueue v1beta2 queue objects."""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from decimal import Decimal
from typing import Any, Literal

from metrics.core.settings import Settings
from metrics.errors import (
    ProviderExecutionError,
    ProviderUnavailableError,
    RuntimeStartupError,
    SubjectNotFoundError,
)
from metrics.providers.kube import (
    MAX_RESULT_OBJECTS,
    KubeReader,
    concurrently,
    fan_out,
    label_selector,
    label_value,
    labels_of,
    mapping,
    name_of,
    observation_time,
    sequence,
)
from metrics.services.models import CommunityObservation, PlatformObservation, UserObservation
from metrics.services.resources import (
    format_resource_amount,
    merge_resource_totals,
    parse_resource_amount,
)


_COMMUNITY_LABEL = "canfar.net/community"
_USERNAME_LABEL = "canfar.net/username"


def _required_label(labels: dict[str, str], name: str, kind: str) -> str:
    """Require one nonempty exact label value."""
    value = labels.get(name)
    if not isinstance(value, str) or not value:
        raise ProviderExecutionError(f"Kueue {kind} was missing a nonempty {name} label")
    return value


def _status(doc: dict[str, Any], kind: str) -> dict[str, Any]:
    """Validate the common Kueue queue status shape."""
    status = mapping(doc.get("status"), f"Kueue {kind} status was missing or invalid")
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
            else sequence(raw_flavors, f"Kueue {kind} {field_name} was invalid")
        )
        for flavor in flavors:
            flavor_map = mapping(flavor, f"Kueue {kind} {field_name} entry was invalid")
            resources = sequence(
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
    entries = sequence(resources, "Kueue resource list was missing or invalid")
    names: set[str] = set()
    for entry in entries:
        resource = mapping(entry, "Kueue resource entry was invalid")
        name = resource.get("name")
        if not isinstance(name, str) or not name or name != name.strip() or name in names:
            raise ProviderExecutionError("Kueue resource entry had a duplicate or invalid name")
        names.add(name)
        merge_resource_totals(totals, name, parse_resource_amount(name, resource.get(value_key)))


def _nominal_resources(doc: dict[str, Any]) -> dict[str, Decimal]:
    """Aggregate one ClusterQueue's complete nominal quota."""
    spec = mapping(doc.get("spec"), "Kueue ClusterQueue spec was invalid")
    groups = sequence(spec.get("resourceGroups"), "Kueue ClusterQueue resourceGroups was invalid")
    if not groups:
        raise ProviderExecutionError("Kueue ClusterQueue resourceGroups was empty")
    totals: dict[str, Decimal] = {}
    for group_value in groups:
        group = mapping(group_value, "Kueue ClusterQueue resource group was invalid")
        flavors = sequence(group.get("flavors"), "Kueue ClusterQueue flavors were invalid")
        if not flavors:
            raise ProviderExecutionError("Kueue ClusterQueue flavors were empty")
        for flavor_value in flavors:
            flavor = mapping(flavor_value, "Kueue ClusterQueue flavor was invalid")
            resources = sequence(
                flavor.get("resources"), "Kueue ClusterQueue resources were invalid"
            )
            if not resources:
                raise ProviderExecutionError("Kueue ClusterQueue resources were empty")
            _merge_resources(totals, resources, "nominalQuota")
    if not totals:
        raise ProviderExecutionError("Kueue ClusterQueue had no nominal resources")
    return totals


def _reserved(status: dict[str, Any], field_name: str) -> dict[str, Decimal]:
    """Sum one status flavor list (``flavorsReservation`` or ``flavorsUsage``)."""
    totals: dict[str, Decimal] = {}
    for flavor in status[field_name]:
        _merge_resources(totals, mapping(flavor, "Kueue flavor was invalid")["resources"], "total")
    return totals


@dataclass(frozen=True, slots=True)
class _ClusterQueue:
    """Hold one configured ClusterQueue parsed once per fill."""

    name: str
    community: str
    capacity: dict[str, Decimal]
    allocated: dict[str, Decimal]
    requests: dict[str, Decimal]
    reserving_workloads: int


def _cluster_queue(name: str, doc: dict[str, Any]) -> _ClusterQueue:
    """Validate one configured ClusterQueue against its request and parse it."""
    metadata = mapping(doc.get("metadata"), "Kueue ClusterQueue metadata was invalid")
    if metadata.get("name") != name:
        raise ProviderExecutionError("Kueue ClusterQueue identity did not match its request")
    community = _required_label(
        labels_of(doc, "Kueue ClusterQueue"), _COMMUNITY_LABEL, "ClusterQueue"
    )
    status = _status(doc, "ClusterQueue")
    capacity = _nominal_resources(doc)
    requests = _reserved(status, "flavorsReservation")
    allocated = _reserved(status, "flavorsUsage")
    if set(requests) - set(capacity):
        raise ProviderExecutionError("Kueue reservation contained a resource absent from capacity")
    if set(allocated) - set(capacity):
        raise ProviderExecutionError("Kueue usage contained a resource absent from capacity")
    return _ClusterQueue(
        name=name,
        community=community,
        capacity=capacity,
        allocated=allocated,
        requests=requests,
        reserving_workloads=status["reservingWorkloads"],
    )


def _resource_maps(values: dict[str, Decimal]) -> dict[str, str]:
    """Format an aggregate resource map deterministically."""
    return {name: format_resource_amount(name, value) for name, value in sorted(values.items())}


def _sum(maps: list[dict[str, Decimal]]) -> dict[str, Decimal]:
    """Merge several public-unit resource maps."""
    totals: dict[str, Decimal] = {}
    for values in maps:
        for name, value in values.items():
            merge_resource_totals(totals, name, value)
    return totals


class KueueProvider:
    """Read User, Community, and Platform observations from Kueue."""

    name = "kueue"

    def __init__(self, settings: Settings, api: Any | None = None) -> None:
        """Attach validated settings and an optional kr8s-compatible API fake."""
        self._settings = settings
        self._config = settings.providers.kueue
        self._kube = KubeReader(timeout=self._config.kube_request_timeout_seconds, api=api)

    async def _cluster_queues(self) -> list[_ClusterQueue]:
        """Read and parse every configured ClusterQueue with named GETs."""
        names = self._config.cluster_queues
        if len(names) > MAX_RESULT_OBJECTS:
            raise ProviderExecutionError("Kueue ClusterQueue result exceeded the result limit")

        async def fetch(name: str) -> _ClusterQueue:
            doc = await self._kube.get(
                version=self._config.kueue_api_version,
                url=f"clusterqueues/{name}",
                kind="Kueue ClusterQueue",
            )
            return _cluster_queue(name, doc)

        return await fan_out(names, fetch)

    async def _local_queues(self, username: str) -> list[tuple[str, dict[str, Any]]]:
        """List one user's LocalQueues in every configured namespace."""
        selector = label_selector(_USERNAME_LABEL, username)

        async def fetch(namespace: str) -> list[dict[str, Any]]:
            return await self._kube.list_all(
                version=self._config.kueue_api_version,
                resource="localqueues",
                namespace=namespace,
                kind="Kueue LocalQueue list",
                selector=selector,
            )

        docs_by_namespace = await fan_out(self._config.namespaces, fetch)
        return [
            (namespace, doc)
            for namespace, docs in zip(self._config.namespaces, docs_by_namespace, strict=True)
            for doc in docs
        ]

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
        queues = await self._cluster_queues()
        capacity = _sum([queue.capacity for queue in queues])
        if not capacity:
            raise ProviderExecutionError("Kueue platform had no configured capacity")
        allocated = _sum([queue.allocated for queue in queues])
        return PlatformObservation(
            cluster=self._settings.cluster_name,
            capacity=_resource_maps(capacity),
            allocated=_resource_maps({name: allocated.get(name, Decimal(0)) for name in capacity}),
            reserving_workloads=sum(queue.reserving_workloads for queue in queues),
            observed_at=observation_time(),
        )

    async def read_community(self, community: str) -> CommunityObservation:
        """Sum reservation and reserving counts for matching ClusterQueues."""
        label_value(community)
        matching = [queue for queue in await self._cluster_queues() if queue.community == community]
        if not matching:
            raise SubjectNotFoundError("Community has no configured ClusterQueue")
        return CommunityObservation(
            community=community,
            requests=_resource_maps(_sum([queue.requests for queue in matching])),
            reserving_workloads=sum(queue.reserving_workloads for queue in matching),
            observed_at=observation_time(),
        )

    async def read_user(self, username: str) -> UserObservation:
        """Aggregate a user's LocalQueues across configured namespaces.

        Every matching LocalQueue must name a configured ClusterQueue whose
        community label equals its own; any other matching queue fails the read.
        """
        cluster_queues, local_queues = await concurrently(
            self._cluster_queues(), self._local_queues(username)
        )
        if not local_queues:
            raise SubjectNotFoundError("User has no matching LocalQueue")
        community_by_queue = {queue.name: queue.community for queue in cluster_queues}

        requests: list[dict[str, Decimal]] = []
        reserving = 0
        seen: set[tuple[str, str]] = set()
        for namespace, doc in local_queues:
            metadata = mapping(doc.get("metadata"), "Kueue LocalQueue metadata was invalid")
            if metadata.get("namespace") != namespace:
                raise ProviderExecutionError(
                    "Kueue LocalQueue was returned from the wrong namespace"
                )
            labels = labels_of(doc, "Kueue LocalQueue")
            if _required_label(labels, _USERNAME_LABEL, "LocalQueue") != username:
                raise ProviderExecutionError(
                    "Kueue LocalQueue username label did not match selector"
                )
            local_community = _required_label(labels, _COMMUNITY_LABEL, "LocalQueue")
            spec = mapping(doc.get("spec"), "Kueue LocalQueue spec was invalid")
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
            identities = {("name", f"{namespace}/{name_of(doc, 'Kueue LocalQueue')}")}
            uid = metadata.get("uid")
            if uid is not None:
                if not isinstance(uid, str) or not uid:
                    raise ProviderExecutionError("Kueue LocalQueue metadata uid was invalid")
                identities.add(("uid", uid))
            if identities & seen:
                raise ProviderExecutionError("Kueue LocalQueue identity was duplicated")
            seen.update(identities)
            status = _status(doc, "LocalQueue")
            requests.append(_reserved(status, "flavorsReservation"))
            reserving += status["reservingWorkloads"]
        return UserObservation(
            user=username,
            requests=_resource_maps(_sum(requests)),
            reserving_workloads=reserving,
            observed_at=observation_time(),
        )

    async def validate_platform(self) -> None:
        """Prove the configured ClusterQueues are readable and well formed."""
        await self._cluster_queues()

    async def probe_local_queues(self) -> None:
        """Prove LocalQueue list access in every configured namespace."""

        async def probe(namespace: str) -> None:
            await self._kube.probe(
                version=self._config.kueue_api_version,
                resource="localqueues",
                namespace=namespace,
                kind="Kueue LocalQueue list",
            )

        await fan_out(self._config.namespaces, probe)

    async def startup(self) -> None:
        """Validate all configured ClusterQueues and namespace list access."""
        try:
            await self.validate_platform()
            await self.probe_local_queues()
        except (ProviderUnavailableError, ProviderExecutionError) as exc:
            raise RuntimeStartupError("Kueue dependency validation failed") from exc

    async def shutdown(self) -> None:
        """Release the provider's API handle reference."""
        self._kube.close()
