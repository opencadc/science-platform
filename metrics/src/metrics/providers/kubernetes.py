"""Collect scheduler-effective requests from namespaced Running Pods.

The provider selects only Skaha-managed Pods for an exact user or community,
then reproduces Kubernetes whole-Pod request accounting across regular,
restartable init, ordinary init, and overhead resources.
"""

from __future__ import annotations

import asyncio
import hashlib
import json
from datetime import UTC, datetime
from typing import Any

import httpx
import kr8s
import kr8s.asyncio

from metrics.core.settings import KubernetesProviderConfig, Settings
from metrics.errors import ProviderExecutionError, ProviderUnavailableError, RuntimeStartupError
from metrics.providers.kueue import (
    format_resource_amount,
    merge_resource_totals,
    parse_resource_amount,
)
from metrics.services.models import CommunityObservation, UserObservation

_MANAGED_BY = "app.kubernetes.io/managed-by=skaha"
_PART_OF = "app.kubernetes.io/part-of=canfar"
_COMMUNITY_LABEL = "canfar.net/community"
_USERNAME_LABEL = "canfar.net/username"
_REQUEST_ERRORS = (
    kr8s.APITimeoutError,
    kr8s.ConnectionClosedError,
    httpx.HTTPError,
)


def _observation_time() -> datetime:
    """Return a Prometheus-compatible millisecond observation cutoff."""
    now = datetime.now(UTC)
    return now.replace(microsecond=now.microsecond // 1000 * 1000)


async def create_kube_api(config: KubernetesProviderConfig) -> Any:
    """Build a kr8s API handle using in-cluster credentials or kubeconfig."""
    api = await kr8s.asyncio.api()
    api.timeout = config.kube_request_timeout_seconds
    return api


def _requests(container: dict[str, Any]) -> dict[str, float]:
    """Parse one container's resource requests into public numeric units.

    Args:
        container: Kubernetes container document.

    Returns:
        Parsed requests keyed by resource name.

    Raises:
        ProviderExecutionError: If the requests object is malformed.
    """
    values = (container.get("resources") or {}).get("requests") or {}
    if not isinstance(values, dict):
        raise ProviderExecutionError("Kubernetes Pod data contained invalid resource requests")
    return {name: parse_resource_amount(name, value) for name, value in values.items()}


def _add(target: dict[str, float], values: dict[str, float]) -> None:
    """Add resource values into validated running totals.

    Args:
        target: Totals mutated in place.
        values: Resource amounts to add.
    """
    for name, value in values.items():
        merge_resource_totals(target, name, value)


def scheduler_requests(pod: dict[str, Any]) -> dict[str, float]:
    """Calculate scheduler-effective whole-Pod requests for every resource.

    The calculation follows Kubernetes scheduling semantics: regular and
    restartable-init requests form the steady sum, ordinary init containers
    contribute a peak, and Pod overhead is added afterward.

    Args:
        pod: Kubernetes Pod document.

    Returns:
        Effective requests in public numeric units.

    Raises:
        ProviderExecutionError: If the Pod shape or a quantity is invalid.
    """
    try:
        spec = pod["spec"]
        steady: dict[str, float] = {}
        for container in spec.get("containers") or []:
            _add(steady, _requests(container))

        restartable: dict[str, float] = {}
        init_peak: dict[str, float] = {}
        for container in spec.get("initContainers") or []:
            current = _requests(container)
            if container.get("restartPolicy") == "Always":
                _add(restartable, current)
                candidate = restartable
            else:
                candidate = dict(restartable)
                _add(candidate, current)
            for name, value in candidate.items():
                init_peak[name] = max(init_peak.get(name, 0.0), value)

        _add(steady, restartable)
        effective = {
            name: max(steady.get(name, 0.0), init_peak.get(name, 0.0))
            for name in steady.keys() | init_peak.keys()
        }
        overhead = spec.get("overhead") or {}
        if not isinstance(overhead, dict):
            raise TypeError
        _add(
            effective,
            {name: parse_resource_amount(name, value) for name, value in overhead.items()},
        )
        return effective
    except (AttributeError, KeyError, TypeError) as exc:
        raise ProviderExecutionError(
            "Kubernetes Pod data contained an invalid object shape"
        ) from exc


def _pod_uids(pods: list[dict[str, Any]]) -> frozenset[str]:
    """Extract unique immutable Pod identities from a selected population.

    Args:
        pods: Kubernetes Pod documents.

    Returns:
        Unique non-empty Pod UIDs.

    Raises:
        ProviderExecutionError: If a UID is missing, invalid, or duplicated.
    """
    try:
        values = [pod["metadata"]["uid"] for pod in pods]
    except (KeyError, TypeError) as exc:
        raise ProviderExecutionError("Kubernetes Pod data omitted a Pod UID") from exc
    if any(not isinstance(value, str) or not value for value in values):
        raise ProviderExecutionError("Kubernetes Pod data contained an invalid Pod UID")
    if len(values) != len(set(values)):
        raise ProviderExecutionError("Kubernetes Pod data contained duplicate Pod UIDs")
    return frozenset(values)


async def fetch_running_pods(
    api: Any,
    namespaces: list[str],
    label: str,
    value: str,
) -> list[dict[str, Any]]:
    """List exact-subject Running Pods from every configured namespace.

    Args:
        api: kr8s-compatible API handle.
        namespaces: Namespaces queried concurrently.
        label: Canonical subject label key.
        value: Exact canonical subject value.

    Returns:
        Flattened Pod documents from all namespaces.

    Raises:
        ProviderExecutionError: If a Pod list response has an invalid shape.
    """
    selector = ",".join((_MANAGED_BY, _PART_OF, f"{label}={value}"))

    async def fetch(namespace: str) -> list[dict[str, Any]]:
        """Fetch and validate one namespace's Running Pod list."""
        async with api.call_api(
            method="GET",
            version="v1",
            namespace=namespace,
            url="pods",
            params={"labelSelector": selector, "fieldSelector": "status.phase=Running"},
        ) as response:
            payload = response.json()
        items = payload.get("items")
        if not isinstance(items, list):
            raise ProviderExecutionError("Kubernetes Pod list contained an invalid object shape")
        return items

    results = await asyncio.gather(*(fetch(namespace) for namespace in namespaces))
    return [pod for namespace_pods in results for pod in namespace_pods]


class KubernetesProvider:
    """Aggregate workload requests from configured Kubernetes namespaces."""

    def __init__(self, settings: Settings, api: Any | None = None) -> None:
        """Attach settings and an optional pre-built Kubernetes API handle.

        Args:
            settings: Validated process and Kubernetes provider settings.
            api: Optional kr8s-compatible handle, usually injected by tests.
        """
        self._settings = settings
        self._config = settings.providers.kubernetes
        self._api = api

    @property
    def name(self) -> str:
        """Return the stable provider key."""
        return "kubernetes"

    async def _ensure_api(self) -> Any:
        """Create and retain the Kubernetes API handle on first use."""
        if self._api is None:
            try:
                self._api = await create_kube_api(self._config)
            except Exception as exc:
                raise ProviderUnavailableError(
                    "Could not configure a Kubernetes API client for workload calls"
                ) from exc
        return self._api

    async def read_user(self, username: str) -> UserObservation:
        """Aggregate scheduler-effective requests for one exact username."""
        observed_at = _observation_time()
        pods, requests = await self._read_subject(_USERNAME_LABEL, username)
        return UserObservation(
            user=username,
            running_pods=len(pods),
            requests=requests,
            observed_at=observed_at,
            pod_uids=_pod_uids(pods),
        )

    async def read_community(self, community: str) -> CommunityObservation:
        """Aggregate scheduler-effective requests for one exact community."""
        observed_at = _observation_time()
        pods, requests = await self._read_subject(_COMMUNITY_LABEL, community)
        return CommunityObservation(
            community=community,
            running_pods=len(pods),
            requests=requests,
            observed_at=observed_at,
            pod_uids=_pod_uids(pods),
        )

    async def _read_subject(
        self,
        label: str,
        value: str,
    ) -> tuple[list[dict[str, Any]], dict[str, str]]:
        """Read and aggregate one exact canonical subject label.

        Args:
            label: Canonical user or community label key.
            value: Exact canonical label value.

        Returns:
            Selected Pods and formatted aggregate requests.

        Raises:
            ProviderExecutionError: If Kubernetes access or Pod data fails.
        """
        api = await self._ensure_api()
        try:
            pods = await fetch_running_pods(
                api,
                self._config.workload_namespaces,
                label,
                value,
            )
            totals: dict[str, float] = {}
            for pod in pods:
                _add(totals, scheduler_requests(pod))
        except kr8s.ServerError as exc:
            status_code = getattr(exc.response, "status_code", None)
            detail = f"HTTP {status_code}" if status_code else "a server error"
            raise ProviderExecutionError(
                f"Kubernetes returned {detail} querying Running Pods"
            ) from exc
        except _REQUEST_ERRORS as exc:
            raise ProviderExecutionError("Failed querying Running Pods") from exc
        return (
            pods,
            {name: format_resource_amount(name, value) for name, value in sorted(totals.items())},
        )

    def cache_fingerprint(self) -> str:
        """Hash the provider and namespace set to segregate cached populations."""
        raw = json.dumps(
            {"name": self.name, "namespaces": sorted(self._config.workload_namespaces)},
            separators=(",", ":"),
            sort_keys=True,
        )
        return hashlib.sha256(raw.encode()).hexdigest()[:24]

    async def startup(self) -> None:
        """Validate configured namespaces and namespaced Pod LIST access."""
        if not self._config.workload_namespaces:
            raise RuntimeStartupError(
                "METRICS_PROVIDERS__KUBERNETES__WORKLOAD_NAMESPACES must list at least one namespace"
            )
        try:
            api = await self._ensure_api()
            await fetch_running_pods(
                api,
                self._config.workload_namespaces,
                _USERNAME_LABEL,
                "metrics-startup-check",
            )
        except (
            ProviderUnavailableError,
            ProviderExecutionError,
            kr8s.ServerError,
            *_REQUEST_ERRORS,
        ) as exc:
            raise RuntimeStartupError(
                "Cannot list Running Pods in every configured workload namespace"
            ) from exc

    async def shutdown(self) -> None:
        """Release the process-local API reference."""
        self._api = None
