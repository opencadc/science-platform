"""Collect Session observations from batch/v1 Jobs and pod state."""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from datetime import datetime
from decimal import Decimal
from typing import Any

from metrics.core.settings import KueueProviderConfig, Settings
from metrics.errors import (
    ProviderExecutionError,
    ProviderUnavailableError,
    SubjectNotFoundError,
)
from metrics.providers.kube import (
    KubeApi,
    KubeReader,
    concurrently,
    fan_out,
    label_selector,
    labels_of,
    mapping,
    name_of,
    observation_time,
    parse_timestamp,
    sequence,
)
from metrics.services.models import SessionObservation
from metrics.services.resources import (
    format_resource_amount,
    merge_resource_totals,
    parse_resource_amount,
)


_JOB_API_VERSION = "batch/v1"
_POD_API_VERSION = "v1"
_SESSION_LABEL = "canfar.net/id"
_TERMINAL_CONDITIONS = frozenset({"Complete", "Failed"})


def _container_requests(container: dict[str, Any]) -> dict[str, Decimal]:
    """Return one container's resource requests in public units."""
    resources = container.get("resources")
    requests = resources.get("requests") if isinstance(resources, dict) else None
    if not isinstance(requests, dict):
        return {}
    totals: dict[str, Decimal] = {}
    for resource_name, raw in requests.items():
        if not isinstance(resource_name, str) or not resource_name:
            raise ProviderExecutionError("Job container request had an invalid resource name")
        merge_resource_totals(totals, resource_name, parse_resource_amount(resource_name, raw))
    return totals


def _add(left: dict[str, Decimal], right: dict[str, Decimal]) -> dict[str, Decimal]:
    """Return the per-resource sum of two maps."""
    totals = dict(left)
    for name, value in right.items():
        merge_resource_totals(totals, name, value)
    return totals


def _peak(left: dict[str, Decimal], right: dict[str, Decimal]) -> dict[str, Decimal]:
    """Return the per-resource maximum of two maps."""
    return {
        name: max(left.get(name, Decimal(0)), right.get(name, Decimal(0)))
        for name in {*left, *right}
    }


def _pod_requests(doc: dict[str, Any]) -> dict[str, Decimal]:
    """Return a Job pod template's effective request, as Kubernetes and Kueue compute it.

    Per resource: the larger of the peak init-container request (each regular
    init container plus the sidecars started before it) and the sum of the
    app containers plus every sidecar, plus any pod overhead.
    """
    spec = mapping(doc.get("spec"), "Job spec was invalid")
    template = mapping(spec.get("template"), "Job pod template was invalid")
    pod_spec = mapping(template.get("spec"), "Job pod spec was invalid")
    sidecars: dict[str, Decimal] = {}
    init_peak: dict[str, Decimal] = {}
    for value in sequence(pod_spec.get("initContainers") or [], "Job initContainers were invalid"):
        container = mapping(value, "Job init container was invalid")
        requests = _container_requests(container)
        if container.get("restartPolicy") == "Always":
            sidecars = _add(sidecars, requests)
            init_peak = _peak(init_peak, sidecars)
        else:
            init_peak = _peak(init_peak, _add(sidecars, requests))
    running: dict[str, Decimal] = dict(sidecars)
    for value in sequence(pod_spec.get("containers") or [], "Job containers were invalid"):
        running = _add(running, _container_requests(mapping(value, "Job container was invalid")))
    effective = _peak(init_peak, running)
    overhead = pod_spec.get("overhead")
    if overhead is not None:
        overhead_map = mapping(overhead, "Job pod overhead was invalid")
        effective = _add(
            effective,
            {name: parse_resource_amount(name, raw) for name, raw in overhead_map.items()},
        )
    return effective


@dataclass(frozen=True, slots=True)
class _Job:
    """Hold the parts of one session Job that Metrics reports."""

    requests: dict[str, Decimal]
    reserving: bool
    start_time: datetime | None
    end_time: datetime | None


def _job(doc: dict[str, Any], session_id: str) -> _Job:
    """Validate one matching Job and derive its reservation and timing.

    A Job reserves quota while it is neither finished (a true ``Complete`` or
    ``Failed`` condition) nor suspended (Kueue has not admitted it). A finished
    Job ends at ``completionTime``, or else at its terminal condition's
    transition time.
    """
    if labels_of(doc, "Job").get(_SESSION_LABEL) != session_id:
        raise ProviderExecutionError("Job session label did not match selector")
    spec = mapping(doc.get("spec"), "Job spec was invalid")
    status = doc.get("status")
    status = status if isinstance(status, dict) else {}
    terminal: datetime | None = None
    finished = False
    conditions = status.get("conditions")
    for condition in conditions if isinstance(conditions, list) else []:
        if (
            isinstance(condition, dict)
            and condition.get("type") in _TERMINAL_CONDITIONS
            and condition.get("status") == "True"
        ):
            finished = True
            raw_time = status.get("completionTime") or condition.get("lastTransitionTime")
            try:
                terminal = parse_timestamp(raw_time, "Job terminal time was invalid")
            except ProviderExecutionError:
                terminal = None  # an unparseable end leaves the window open
    raw_start = status.get("startTime")
    start = None if raw_start is None else parse_timestamp(raw_start, "Job startTime was invalid")
    return _Job(
        requests=_pod_requests(doc),
        reserving=not finished and spec.get("suspend") is not True,
        start_time=start,
        end_time=terminal if finished else None,
    )


def _pod_phase(doc: dict[str, Any]) -> str:
    """Return one Pod's phase."""
    phase = mapping(doc.get("status"), "Pod status was invalid").get("phase")
    if not isinstance(phase, str) or not phase:
        raise ProviderExecutionError("Pod phase was missing or invalid")
    return phase


def _running_pods_by_namespace(
    pods: list[tuple[str, dict[str, Any]]],
) -> dict[str, frozenset[str]]:
    """Index Running pod names by namespace from one labelled Pod list."""
    running: dict[str, set[str]] = {}
    for namespace, doc in pods:
        if _pod_phase(doc) == "Running":
            running.setdefault(namespace, set()).add(name_of(doc, "Pod"))
    return {namespace: frozenset(names) for namespace, names in running.items()}


class SessionProvider:
    """Read Session observations from labelled Jobs and Pods."""

    name = "session"

    def __init__(self, settings: Settings, api: KubeApi | None = None) -> None:
        """Attach validated settings and an optional kr8s-compatible API fake."""
        self._config: KueueProviderConfig = settings.providers.kueue
        self._kube = KubeReader(timeout=self._config.kube_request_timeout_seconds, api=api)

    async def _list(
        self,
        version: str,
        resource: str,
        kind: str,
        session_id: str,
        *,
        consistent: bool = False,
    ) -> list[tuple[str, dict[str, Any]]]:
        """List one session's objects of a kind across configured namespaces."""
        selector = label_selector(_SESSION_LABEL, session_id)

        async def fetch(namespace: str) -> list[dict[str, Any]]:
            return await self._kube.list_all(
                version=version,
                resource=resource,
                namespace=namespace,
                kind=kind,
                selector=selector,
                consistent=consistent,
            )

        docs_by_namespace = await fan_out(self._config.namespaces, fetch)
        return [
            (namespace, doc)
            for namespace, docs in zip(self._config.namespaces, docs_by_namespace, strict=True)
            for doc in docs
        ]

    async def _pods(self, session_id: str) -> list[tuple[str, dict[str, Any]]] | None:
        """List the session's Pods, or return ``None`` when pod state is unavailable."""
        try:
            return await self._list(_POD_API_VERSION, "pods", "Pod list", session_id)
        except (ProviderUnavailableError, ProviderExecutionError):
            return None

    def cache_fingerprint(self) -> str:
        """Return a stable cache revision for the configured Job population."""
        raw = json.dumps(
            {"api_version": _JOB_API_VERSION, "namespaces": self._config.namespaces},
            separators=(",", ":"),
            sort_keys=True,
        )
        return hashlib.sha256(raw.encode()).hexdigest()[:24]

    async def read_session(self, session_id: str) -> SessionObservation:
        """Aggregate one session's active Jobs and derive its timing window."""
        job_docs, pods = await concurrently(
            self._list(_JOB_API_VERSION, "jobs", "Job list", session_id),
            self._pods(session_id),
        )
        if not job_docs:
            # A cached list can trail a just-created Job; confirm before a 404.
            job_docs = await self._list(
                _JOB_API_VERSION, "jobs", "Job list", session_id, consistent=True
            )
        if not job_docs:
            raise SubjectNotFoundError("Session has no matching Job")
        seen: set[tuple[str, str]] = set()
        jobs: list[_Job] = []
        for namespace, doc in job_docs:
            identity = (namespace, name_of(doc, "Job"))
            if identity in seen:
                raise ProviderExecutionError("Job identity was duplicated")
            seen.add(identity)
            jobs.append(_job(doc, session_id))

        requests: dict[str, Decimal] = {}
        for job in jobs:
            if job.reserving:
                requests = _add(requests, job.requests)
        now = observation_time()
        starts = [job.start_time for job in jobs if job.start_time is not None]
        start_time = min(starts) if starts else None
        ends = [job.end_time for job in jobs if job.end_time is not None]
        window_end = now if len(ends) < len(jobs) else max(ends)
        if start_time is not None and window_end < start_time:
            window_end = start_time
        running = _running_pods_by_namespace(pods) if pods is not None else {}
        return SessionObservation(
            session=session_id,
            requests={
                name: format_resource_amount(name, value)
                for name, value in sorted(requests.items())
            },
            reserving_workloads=sum(job.reserving for job in jobs),
            observed_at=now,
            start_time=start_time,
            window_end=window_end,
            has_running_pods=bool(running),
            pods_reachable=pods is not None,
            running_pods_by_namespace=running,
            job_names=tuple(sorted({name for _namespace, name in seen})),
        )

    async def startup(self) -> None:
        """Validate Job list access once per configured namespace."""

        async def probe(namespace: str) -> None:
            await self._kube.probe(
                version=_JOB_API_VERSION, resource="jobs", namespace=namespace, kind="Job list"
            )

        await fan_out(self._config.namespaces, probe)

    async def shutdown(self) -> None:
        """Release the provider's API handle reference."""
        self._kube.close()
