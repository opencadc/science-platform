"""Collect Session observations from batch/v1 Jobs and pod state."""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from datetime import UTC, datetime
from decimal import Decimal
from typing import Any

import kr8s
import kr8s.asyncio

from metrics.core.settings import KueueProviderConfig, Settings
from metrics.errors import (
    ProviderExecutionError,
    ProviderUnavailableError,
    SubjectNotFoundError,
)
from metrics.providers.kueue import (
    _REQUEST_ERRORS,
    _bounded_map,
    _json_response,
    _list,
    _mapping,
    _observation_time,
    _validate_subject,
    create_kube_api,
)
from metrics.services.models import SessionObservation
from metrics.services.resources import format_resource_amount, merge_resource_totals, parse_resource_amount


_JOB_API_VERSION = "batch/v1"
_POD_API_VERSION = "v1"
_SESSION_LABEL = "canfar.net/id"
_PAUSE_CONTAINERS = frozenset({"pause", "POD"})
_MAX_LIST_PAGES = 1_000
_MAX_CONTINUE_TOKEN_LENGTH = 4_096
_MAX_RESULT_OBJECTS = 3_000
_LIST_PAGE_LIMIT = 100


def _parse_timestamp(value: object, message: str) -> datetime:
    """Parse one RFC3339 Kubernetes timestamp into UTC."""
    if not isinstance(value, str) or not value:
        raise ProviderExecutionError(message)
    normalized = value.replace("Z", "+00:00")
    try:
        parsed = datetime.fromisoformat(normalized)
    except ValueError as exc:
        raise ProviderExecutionError(message) from exc
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        raise ProviderExecutionError(message)
    return parsed.astimezone(UTC)


async def _fetch_paged_docs(
    api: Any,
    *,
    api_version: str,
    resource: str,
    namespace: str,
    label_selector: str | None = None,
    kind: str,
) -> list[dict[str, Any]]:
    """List all objects in one namespace with bounded pagination."""
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
            url=resource,
            params=params,
        ) as response:
            payload = _json_response(response, kind)
        page_items = payload.get("items")
        if not isinstance(page_items, list) or not all(
            isinstance(item, dict) for item in page_items
        ):
            raise ProviderExecutionError(f"{kind} list contained an invalid object shape")
        if len(items) + len(page_items) > _MAX_RESULT_OBJECTS:
            raise ProviderExecutionError(f"{kind} result exceeded the result limit")
        items.extend(page_items)

        metadata = payload.get("metadata")
        if metadata is None:
            return items
        if not isinstance(metadata, dict):
            raise ProviderExecutionError(f"{kind} list metadata was invalid")
        next_token = metadata.get("continue")
        if next_token in (None, ""):
            return items
        if (
            not isinstance(next_token, str)
            or len(next_token) > _MAX_CONTINUE_TOKEN_LENGTH
            or next_token in seen_tokens
        ):
            raise ProviderExecutionError(f"{kind} pagination token was invalid")
        seen_tokens.add(next_token)
        continue_token = next_token
    raise ProviderExecutionError(f"{kind} list exceeded the pagination limit")


async def fetch_job_docs(
    api: Any,
    api_version: str,
    namespace: str,
    *,
    label_selector: str | None = None,
) -> list[dict[str, Any]]:
    """List all Jobs in one configured namespace."""
    return await _fetch_paged_docs(
        api,
        api_version=api_version,
        resource="jobs",
        namespace=namespace,
        label_selector=label_selector,
        kind="Job",
    )


async def fetch_pod_docs(
    api: Any,
    api_version: str,
    namespace: str,
    *,
    label_selector: str | None = None,
) -> list[dict[str, Any]]:
    """List all Pods in one configured namespace."""
    return await _fetch_paged_docs(
        api,
        api_version=api_version,
        resource="pods",
        namespace=namespace,
        label_selector=label_selector,
        kind="Pod",
    )


async def probe_job_access(api: Any, api_version: str, namespace: str) -> None:
    """Probe namespaced Job list access without following pagination."""
    async with api.call_api(
        method="GET",
        version=api_version,
        namespace=namespace,
        url="jobs",
        params={"limit": "1"},
    ) as response:
        payload = _json_response(response, "Job access probe")
    items = payload.get("items")
    if not isinstance(items, list) or not all(isinstance(item, dict) for item in items):
        raise ProviderExecutionError("Job access probe returned an invalid list")


def _required_label(labels: dict[str, str], name: str) -> str:
    """Require one nonempty exact label value."""
    value = labels.get(name)
    if not isinstance(value, str) or not value:
        raise ProviderExecutionError(f"Job was missing a nonempty {name} label")
    return value


def _job_labels(doc: dict[str, Any]) -> dict[str, str]:
    """Return a validated Job label map."""
    metadata = _mapping(doc.get("metadata"), "Job metadata was invalid")
    labels = _mapping(metadata.get("labels"), "Job labels were missing or invalid")
    if not all(isinstance(name, str) and isinstance(value, str) for name, value in labels.items()):
        raise ProviderExecutionError("Job labels were invalid")
    return labels


def _container_requests(container: dict[str, Any]) -> dict[str, Decimal]:
    """Sum one container's resource requests."""
    name = container.get("name")
    if not isinstance(name, str) or name in _PAUSE_CONTAINERS:
        return {}
    resources = container.get("resources")
    if not isinstance(resources, dict):
        return {}
    requests = resources.get("requests")
    if not isinstance(requests, dict):
        return {}
    totals: dict[str, Decimal] = {}
    for resource_name, raw in requests.items():
        if not isinstance(resource_name, str) or not resource_name:
            raise ProviderExecutionError("Job container request had an invalid resource name")
        merge_resource_totals(totals, resource_name, parse_resource_amount(resource_name, raw))
    return totals


def _pod_template_requests(doc: dict[str, Any]) -> dict[str, Decimal]:
    """Aggregate requests from all non-pause containers in one Job template."""
    spec = _mapping(doc.get("spec"), "Job spec was invalid")
    template = _mapping(spec.get("template"), "Job pod template was invalid")
    pod_spec = _mapping(template.get("spec"), "Job pod spec was invalid")
    totals: dict[str, Decimal] = {}
    for field in ("initContainers", "containers"):
        containers = pod_spec.get(field)
        if containers is None:
            continue
        for container_value in _list(containers, f"Job {field} were invalid"):
            container = _mapping(container_value, "Job container was invalid")
            for name, value in _container_requests(container).items():
                merge_resource_totals(totals, name, value)
    return totals


def _running_pods_by_namespace(
    pods: list[tuple[str, dict[str, Any]]],
) -> dict[str, frozenset[str]]:
    """Index Running pod names by namespace from one labelled Pod list."""
    running: dict[str, set[str]] = {}
    for namespace, doc in pods:
        if _pod_phase(doc) != "Running":
            continue
        metadata = _mapping(doc.get("metadata"), "Pod metadata was invalid")
        name = metadata.get("name")
        if not isinstance(name, str):
            raise ProviderExecutionError("Pod metadata name was missing or invalid")
        running.setdefault(namespace, set()).add(name)
    return {namespace: frozenset(names) for namespace, names in running.items()}


def _pod_phase(doc: dict[str, Any]) -> str:
    """Return one Pod's phase."""
    status = _mapping(doc.get("status"), "Pod status was invalid")
    phase = status.get("phase")
    if not isinstance(phase, str) or not phase:
        raise ProviderExecutionError("Pod phase was missing or invalid")
    return phase


@dataclass(frozen=True, slots=True)
class _SessionWindow:
    """Hold session timing inputs for optional efficiency."""

    start_time: datetime | None
    window_end: datetime
    has_running_pods: bool


class SessionProvider:
    """Read Session observations from labelled Jobs and Pods."""

    def __init__(self, settings: Settings, api: Any | None = None) -> None:
        """Attach validated settings and an optional kr8s-compatible API fake."""
        self._config: KueueProviderConfig = settings.providers.kueue
        self._api = api

    @property
    def name(self) -> str:
        """Return the stable provider name used in telemetry."""
        return "session"

    async def _ensure_api(self) -> Any:
        """Create and retain the Kubernetes API handle on first use."""
        if self._api is None:
            try:
                self._api = await create_kube_api(self._config)
            except Exception as exc:
                raise ProviderUnavailableError("Could not configure Kubernetes API access") from exc
        return self._api

    async def _jobs(self, session_id: str) -> list[tuple[str, dict[str, Any]]]:
        """Read every matching Job across configured namespaces."""
        api = await self._ensure_api()
        selector = f"{_SESSION_LABEL}={_validate_subject(session_id)}"

        async def fetch(namespace: str) -> list[dict[str, Any]]:
            return await fetch_job_docs(
                api,
                _JOB_API_VERSION,
                namespace,
                label_selector=selector,
            )

        try:
            docs_by_namespace = await _bounded_map(self._config.namespaces, fetch)
        except kr8s.ServerError as exc:
            raise ProviderUnavailableError("Configured Job namespace access failed") from exc
        except _REQUEST_ERRORS as exc:
            raise ProviderUnavailableError("Job namespace access failed") from exc
        return [
            (namespace, doc)
            for namespace, docs in zip(self._config.namespaces, docs_by_namespace, strict=True)
            for doc in docs
        ]

    async def _pods(self, session_id: str) -> list[tuple[str, dict[str, Any]]]:
        """Read every matching Pod across configured namespaces."""
        api = await self._ensure_api()
        selector = f"{_SESSION_LABEL}={_validate_subject(session_id)}"

        async def fetch(namespace: str) -> list[dict[str, Any]]:
            return await fetch_pod_docs(
                api,
                _POD_API_VERSION,
                namespace,
                label_selector=selector,
            )

        try:
            docs_by_namespace = await _bounded_map(self._config.namespaces, fetch)
        except kr8s.ServerError as exc:
            raise ProviderUnavailableError("Configured Pod namespace access failed") from exc
        except _REQUEST_ERRORS as exc:
            raise ProviderUnavailableError("Pod namespace access failed") from exc
        return [
            (namespace, doc)
            for namespace, docs in zip(self._config.namespaces, docs_by_namespace, strict=True)
            for doc in docs
        ]

    @staticmethod
    def _session_window(
        jobs: list[tuple[str, dict[str, Any]]],
        pods: list[tuple[str, dict[str, Any]]],
    ) -> _SessionWindow:
        """Derive session timing from Job and Pod status."""
        start_time: datetime | None = None
        completion_time: datetime | None = None
        has_running_pods = False
        for _namespace, doc in pods:
            if _pod_phase(doc) == "Running":
                has_running_pods = True
        for _namespace, doc in jobs:
            status = doc.get("status")
            if not isinstance(status, dict):
                continue
            raw_start = status.get("startTime")
            if raw_start is not None:
                parsed_start = _parse_timestamp(raw_start, "Job startTime was invalid")
                start_time = (
                    parsed_start if start_time is None else min(start_time, parsed_start)
                )
            raw_completion = status.get("completionTime")
            if raw_completion is not None:
                parsed_completion = _parse_timestamp(raw_completion, "Job completionTime was invalid")
                completion_time = (
                    parsed_completion
                    if completion_time is None
                    else max(completion_time, parsed_completion)
                )
        now = _observation_time()
        window_end = now if has_running_pods or completion_time is None else completion_time
        return _SessionWindow(start_time, window_end, has_running_pods)

    def cache_fingerprint(self) -> str:
        """Return a stable cache revision for the configured Job population."""
        raw = json.dumps(
            {
                "api_version": _JOB_API_VERSION,
                "namespaces": self._config.namespaces,
            },
            separators=(",", ":"),
            sort_keys=True,
        )
        return hashlib.sha256(raw.encode()).hexdigest()[:24]

    async def read_session(self, session_id: str) -> SessionObservation:
        """Aggregate matching Jobs and derive session timing for one id."""
        session_id = _validate_subject(session_id)
        jobs = await self._jobs(session_id)
        if not jobs:
            raise SubjectNotFoundError("Session has no matching Job")
        pods_reachable = True
        try:
            pods = await self._pods(session_id)
        except (ProviderUnavailableError, ProviderExecutionError):
            pods = []
            pods_reachable = False
        requests: dict[str, Decimal] = {}
        seen_jobs: set[tuple[str, str]] = set()
        for namespace, doc in jobs:
            metadata = _mapping(doc.get("metadata"), "Job metadata was invalid")
            name = metadata.get("name")
            if not isinstance(name, str) or not name:
                raise ProviderExecutionError("Job metadata name was missing or invalid")
            identity = (namespace, name)
            if identity in seen_jobs:
                raise ProviderExecutionError("Job identity was duplicated")
            seen_jobs.add(identity)
            labels = _job_labels(doc)
            if _required_label(labels, _SESSION_LABEL) != session_id:
                raise ProviderExecutionError("Job session label did not match selector")
            for resource_name, value in _pod_template_requests(doc).items():
                merge_resource_totals(requests, resource_name, value)
        window = self._session_window(jobs, pods)
        return SessionObservation(
            session=session_id,
            requests={name: format_resource_amount(name, value) for name, value in sorted(requests.items())},
            reserving_workloads=len(jobs),
            observed_at=_observation_time(),
            start_time=window.start_time,
            window_end=window.window_end,
            has_running_pods=window.has_running_pods,
            pods_reachable=pods_reachable,
            running_pods_by_namespace=_running_pods_by_namespace(pods) if pods_reachable else {},
        )

    async def startup(self) -> None:
        """Validate Job list access once per configured namespace."""
        api = await self._ensure_api()

        async def probe(namespace: str) -> None:
            await probe_job_access(api, _JOB_API_VERSION, namespace)

        try:
            await _bounded_map(self._config.namespaces, probe)
        except kr8s.ServerError as exc:
            raise ProviderUnavailableError("Configured Job namespace access failed") from exc
        except _REQUEST_ERRORS as exc:
            raise ProviderUnavailableError("Job namespace access failed") from exc

    async def shutdown(self) -> None:
        """Release the provider's API handle reference."""
        self._api = None
