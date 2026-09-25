"""Collect current CPU and memory efficiency from Prometheus-compatible APIs."""

from __future__ import annotations

import asyncio
import json
import re
from datetime import UTC, datetime
from decimal import Decimal
from time import perf_counter
from typing import Any, Literal
from urllib.parse import urlsplit, urlunsplit

import httpx

from metrics.core.settings import Settings
from metrics.errors import ProviderExecutionError, ProviderUnavailableError
from metrics.services.models import EfficiencyObservation, bounded_decimal
from metrics.services.resources import MEASURED_RESOURCES
from metrics.telemetry import MetricsRecorder, NoopMetricsRecorder


_CPU_USAGE_METRIC = "container_cpu_usage_seconds_total"
_MEMORY_USAGE_METRIC = "container_memory_working_set_bytes"
_POD_REQUEST_METRIC = "kube_pod_container_resource_requests"
_POD_LABELS_METRIC = "kube_pod_labels"
_POD_PHASE_METRIC = "kube_pod_status_phase"
_JOIN_LABELS = "cluster,namespace,pod"
_USER_LABEL = "label_canfar_net_username"
_COMMUNITY_LABEL = "label_canfar_net_community"
_SESSION_ID_LABEL = "label_canfar_net_id"
_NAMESPACE_LABEL = "namespace"
_PROMQL_SCOPE = Literal["user", "community", "platform", "session"]
_MAX_SESSION_WINDOW_SECONDS = 6 * 60 * 60
_REQUEST_TIMEOUT_SECONDS = 5.0
_MAX_SAMPLE_AGE_SECONDS = 300
_FUTURE_SAMPLE_TOLERANCE_SECONDS = 30
_MAX_RESPONSE_BYTES = 4 * 1024 * 1024


def _validation_now() -> datetime:
    """Return the wall-clock value used to validate one response."""
    return datetime.now(UTC)


def _promql_string(value: str) -> str:
    """Encode one value as a PromQL double-quoted string literal."""
    return json.dumps(value, ensure_ascii=True)


def _namespace_regex(namespaces: list[str]) -> str:
    """Build an anchored regex from configured namespace names."""
    if not namespaces:
        raise ProviderExecutionError("PromQL namespaces are not configured")
    return "^(?:" + "|".join(re.escape(namespace) for namespace in namespaces) + ")$"


def _matcher(name: str, operator: str, value: str) -> str:
    """Render one fixed PromQL label matcher."""
    return f"{name}{operator}{_promql_string(value)}"


def _selector(
    metric: str,
    *,
    cluster: str,
    namespaces: str,
    matchers: tuple[str, ...] = (),
) -> str:
    """Render one fully scoped metric selector."""
    labels = (
        _matcher("cluster", "=", cluster),
        _matcher(_NAMESPACE_LABEL, "=~", namespaces),
        *matchers,
    )
    return f"{metric}{{{','.join(labels)}}}"


def _selected_pods(
    *,
    scope: _PROMQL_SCOPE,
    subject: str | None,
    cluster: str,
    namespaces: str,
) -> str:
    """Select labelled Running Pods for one efficiency scope."""
    if scope == "user":
        if subject is None:
            raise ProviderExecutionError("PromQL user subject is missing")
        matchers = (
            _matcher(_USER_LABEL, "=", subject),
            _matcher(_COMMUNITY_LABEL, "!=", ""),
        )
    elif scope == "community":
        if subject is None:
            raise ProviderExecutionError("PromQL community subject is missing")
        matchers = (
            _matcher(_COMMUNITY_LABEL, "=", subject),
            _matcher(_USER_LABEL, "!=", ""),
        )
    else:
        if subject is not None:
            raise ProviderExecutionError("PromQL platform scope does not accept a subject")
        matchers = (
            _matcher(_USER_LABEL, "!=", ""),
            _matcher(_COMMUNITY_LABEL, "!=", ""),
        )

    labels = _selector(
        _POD_LABELS_METRIC,
        cluster=cluster,
        namespaces=namespaces,
        matchers=matchers,
    )
    running = _selector(
        _POD_PHASE_METRIC,
        cluster=cluster,
        namespaces=namespaces,
        matchers=(_matcher("phase", "=", "Running"),),
    )
    return f"({labels} and on ({_JOIN_LABELS}) ({running} == 1))"


def _selected_metric(metric: str, selected_pods: str) -> str:
    """Join a source metric to the selected Running Pod population."""
    return f"({metric} and on ({_JOIN_LABELS}) {selected_pods})"


def _cpu_usage(selected_pods: str, *, cluster: str, namespaces: str) -> str:
    """Return CPU usage summed after a five-minute counter rate."""
    source = _selector(
        _CPU_USAGE_METRIC,
        cluster=cluster,
        namespaces=namespaces,
        matchers=(
            _matcher("pod", "!=", ""),
            _matcher("container", "!=", ""),
            _matcher("container", "!=", "POD"),
            _matcher("image", "!=", ""),
        ),
    )
    return f"sum({_selected_metric(f'rate({source}[5m])', selected_pods)})"


def _memory_usage(selected_pods: str, *, cluster: str, namespaces: str) -> str:
    """Return working-set bytes summed for selected Running Pods."""
    source = _selector(
        _MEMORY_USAGE_METRIC,
        cluster=cluster,
        namespaces=namespaces,
        matchers=(
            _matcher("pod", "!=", ""),
            _matcher("container", "!=", ""),
            _matcher("container", "!=", "POD"),
        ),
    )
    return f"sum({_selected_metric(source, selected_pods)})"


def _resource_requests(
    resource: str,
    unit: str,
    selected_pods: str,
    *,
    cluster: str,
    namespaces: str,
) -> str:
    """Return resource requests summed for selected Running Pods."""
    source = _selector(
        _POD_REQUEST_METRIC,
        cluster=cluster,
        namespaces=namespaces,
        matchers=(
            _matcher("resource", "=", resource),
            _matcher("unit", "=", unit),
            _matcher("pod", "!=", ""),
            _matcher("container", "!=", ""),
        ),
    )
    return f"sum({_selected_metric(source, selected_pods)})"


def _label_resource(expression: str, resource: str) -> str:
    """Attach the controlled resource label to one ratio vector."""
    return f'label_replace(({expression}), "resource", "{resource}", "__name__", ".*")'


_SUBQUERY_STEP_SECONDS = 60


def _render_window(duration_seconds: int) -> str:
    """Render one bounded PromQL range selector."""
    bounded = min(max(duration_seconds, _SUBQUERY_STEP_SECONDS), _MAX_SESSION_WINDOW_SECONDS)
    return f"{bounded}s"


def _job_pods(job_names: tuple[str, ...]) -> tuple[str, ...]:
    """Match only pods created by the session's Jobs (``<job>-<suffix>``)."""
    if not job_names:
        return ()
    pattern = "^(?:" + "|".join(re.escape(name) for name in sorted(job_names)) + ")-.+$"
    return (_matcher("pod", "=~", pattern),)


def _session_query(
    *,
    session_id: str,
    cluster: str,
    namespaces: list[str],
    duration_seconds: int,
    job_names: tuple[str, ...] = (),
) -> str:
    """Render the server-owned session duration efficiency query.

    CPU efficiency is core-seconds used over the window divided by
    core-seconds requested while each pod was Running. Memory efficiency is
    the mean working set divided by the mean request while Running. Both
    request integrals and the memory numerator sample one fixed subquery
    step, so scrape intervals cancel out and a short-lived pod is charged
    only for the time it ran.
    """
    namespace_pattern = _namespace_regex(namespaces)
    window = _render_window(duration_seconds)
    step = f"{_SUBQUERY_STEP_SECONDS}s"
    pods = _job_pods(job_names)
    containers = (
        _matcher("pod", "!=", ""),
        _matcher("container", "!=", ""),
        _matcher("container", "!=", "POD"),
        _matcher("container", "!=", "pause"),
        *pods,
    )

    def selector(metric: str, *matchers: str) -> str:
        return _selector(metric, cluster=cluster, namespaces=namespace_pattern, matchers=matchers)

    labels = selector(_POD_LABELS_METRIC, _matcher(_SESSION_ID_LABEL, "=", session_id))
    selected = f"max_over_time({labels}[{window}])"
    phase = selector(_POD_PHASE_METRIC, _matcher("phase", "=", "Running"), *pods)
    running = f"(max by ({_JOIN_LABELS}) ({phase}) == 1)"

    def requested(resource: str, unit: str) -> str:
        requests = selector(
            _POD_REQUEST_METRIC,
            _matcher("resource", "=", resource),
            _matcher("unit", "=", unit),
            *containers,
        )
        while_running = f"sum by ({_JOIN_LABELS}) ({requests}) * on ({_JOIN_LABELS}) {running}"
        return (
            f"sum(sum_over_time(({while_running})[{window}:{step}]) "
            f"and on ({_JOIN_LABELS}) {selected})"
        )

    cpu_used = (
        f"sum(increase({selector(_CPU_USAGE_METRIC, *containers, _matcher('image', '!=', ''))}"
        f"[{window}]) and on ({_JOIN_LABELS}) {selected})"
    )
    working_set = (
        f"sum by ({_JOIN_LABELS}) ({selector(_MEMORY_USAGE_METRIC, *containers)}) "
        f"and on ({_JOIN_LABELS}) {running}"
    )
    memory_used = (
        f"sum(sum_over_time(({working_set})[{window}:{step}]) and on ({_JOIN_LABELS}) {selected})"
    )
    cpu_ratio = f"({cpu_used}) / ({requested('cpu', 'core')} * {_SUBQUERY_STEP_SECONDS})"
    memory_ratio = f"({memory_used}) / ({requested('memory', 'byte')})"
    return f"{_label_resource(cpu_ratio, 'cpu')} or {_label_resource(memory_ratio, 'memory')}"


def _query(
    *,
    scope: _PROMQL_SCOPE,
    subject: str | None,
    cluster: str,
    namespaces: list[str],
) -> str:
    """Render the sole server-owned CPU/memory efficiency query."""
    namespace_pattern = _namespace_regex(namespaces)
    selected = _selected_pods(
        scope=scope,
        subject=subject,
        cluster=cluster,
        namespaces=namespace_pattern,
    )
    cpu_ratio = (
        f"{_cpu_usage(selected, cluster=cluster, namespaces=namespace_pattern)}"
        " / "
        f"{_resource_requests('cpu', 'core', selected, cluster=cluster, namespaces=namespace_pattern)}"
    )
    memory_ratio = (
        f"{_memory_usage(selected, cluster=cluster, namespaces=namespace_pattern)}"
        " / "
        f"{_resource_requests('memory', 'byte', selected, cluster=cluster, namespaces=namespace_pattern)}"
    )
    return f"{_label_resource(cpu_ratio, 'cpu')} or {_label_resource(memory_ratio, 'memory')}"


def _result_vector(payload: Any) -> list[Any]:
    """Validate the Prometheus success envelope and return its vector."""
    if not isinstance(payload, dict) or payload.get("status") != "success":
        raise ProviderExecutionError("PromQL API did not return success")
    data = payload.get("data")
    if not isinstance(data, dict) or data.get("resultType") != "vector":
        raise ProviderExecutionError("PromQL API did not return an instant vector")
    result = data.get("result")
    if not isinstance(result, list):
        raise ProviderExecutionError("PromQL API returned an invalid vector")
    if not result:
        raise ProviderExecutionError("PromQL returned no efficiency series")
    return result


def _sample_timestamp(value: Any) -> tuple[Decimal, datetime]:
    """Parse one Prometheus sample timestamp into bounded UTC values."""
    try:
        timestamp = bounded_decimal(value)
    except ValueError as exc:
        raise ProviderExecutionError("PromQL returned an invalid sample timestamp") from exc
    if timestamp < 0:
        raise ProviderExecutionError("PromQL returned a negative sample timestamp")
    try:
        result = datetime.fromtimestamp(float(timestamp), tz=UTC)
    except (OverflowError, OSError, ValueError) as exc:
        raise ProviderExecutionError("PromQL returned an unusable sample timestamp") from exc
    return timestamp, result


def _sample_value(value: Any) -> Decimal:
    """Parse one finite, non-negative efficiency ratio."""
    try:
        return bounded_decimal(value)
    except ValueError as exc:
        raise ProviderExecutionError("PromQL returned an invalid efficiency ratio") from exc


def _validate_response(
    payload: Any,
    *,
    max_sample_age_seconds: int,
    future_sample_tolerance_seconds: int,
    evaluation_time: datetime | None = None,
) -> EfficiencyObservation:
    """Validate and normalize controlled CPU and memory efficiency samples."""
    result = _result_vector(payload)
    if len(result) != 2:
        raise ProviderExecutionError("PromQL efficiency vector must contain cpu and memory")

    validation_now = evaluation_time or _validation_now()
    now_seconds = Decimal(str(validation_now.timestamp()))
    observed_timestamp: Decimal | None = None
    observed_at: datetime | None = None
    efficiencies: dict[str, Decimal] = {}

    for series in result:
        if not isinstance(series, dict):
            raise ProviderExecutionError("PromQL returned an invalid efficiency series")
        labels = series.get("metric")
        sample = series.get("value")
        if (
            not isinstance(labels, dict)
            or set(labels) != {"resource"}
            or not isinstance(labels.get("resource"), str)
            or not isinstance(sample, list)
            or len(sample) != 2
        ):
            raise ProviderExecutionError("PromQL returned an invalid efficiency series")
        resource = labels["resource"]
        if resource not in MEASURED_RESOURCES:
            raise ProviderExecutionError("PromQL returned an unknown efficiency resource")
        if resource in efficiencies:
            raise ProviderExecutionError("PromQL returned duplicate efficiency resources")

        sample_timestamp, sample_at = _sample_timestamp(sample[0])
        if observed_timestamp is None:
            observed_timestamp = sample_timestamp
            observed_at = sample_at
        elif sample_timestamp != observed_timestamp:
            raise ProviderExecutionError("PromQL efficiency samples have different timestamps")

        age = now_seconds - sample_timestamp
        if age > Decimal(max_sample_age_seconds) or age < -Decimal(future_sample_tolerance_seconds):
            raise ProviderExecutionError("PromQL returned a stale or future sample")
        efficiencies[resource] = _sample_value(sample[1])

    if set(efficiencies) != MEASURED_RESOURCES or observed_at is None:
        raise ProviderExecutionError("PromQL efficiency vector is incomplete")
    return EfficiencyObservation(observed_at=observed_at, efficiencies=efficiencies)


async def _bounded_response_body(response: httpx.Response, max_bytes: int) -> bytes:
    """Read a response body without exceeding the configured byte bound."""
    declared_length = response.headers.get("content-length")
    if declared_length is not None:
        try:
            declared = int(declared_length)
        except ValueError as exc:
            raise ProviderExecutionError("PromQL response Content-Length is invalid") from exc
        if declared < 0 or declared > max_bytes:
            raise ProviderExecutionError("PromQL response exceeded the byte limit")

    body = bytearray()
    async for chunk in response.aiter_bytes():
        if len(body) + len(chunk) > max_bytes:
            raise ProviderExecutionError("PromQL response exceeded the byte limit")
        body.extend(chunk)
    return bytes(body)


class PromQLProvider:
    """Read current efficiency through one fixed Prometheus-compatible query."""

    name = "promql"

    def __init__(
        self,
        settings: Settings,
        *,
        client: httpx.AsyncClient | None = None,
        telemetry: MetricsRecorder | None = None,
    ) -> None:
        """Attach validated settings and an optional injected HTTP client."""
        self._cluster = settings.cluster_name
        self._namespaces = list(settings.providers.kueue.namespaces)
        self._config = settings.providers.promql
        base_url = self._config.base_url
        self._endpoint = None
        if base_url is not None:
            parsed = urlsplit(str(base_url))
            base_path = parsed.path.rstrip("/")
            self._endpoint = urlunsplit(
                (parsed.scheme, parsed.netloc, f"{base_path}/api/v1/query", "", "")
            )
        self._tenant = self._config.mimir_tenant_id
        self._headers = {"X-Scope-OrgID": self._tenant} if self._tenant else None
        self._client = client
        self._owns_client = client is None
        self._telemetry = telemetry or NoopMetricsRecorder()

    def cache_fingerprint(self) -> str:
        """Return a stable identity for this fixed query configuration."""
        return json.dumps(
            {
                "base_url": self._config.base_url,
                "tenant": self._tenant,
                "cluster": self._cluster,
                "namespaces": self._namespaces,
            },
            default=str,
            separators=(",", ":"),
            sort_keys=True,
        )

    async def startup(self) -> None:
        """Create the provider-owned HTTP client when an endpoint is configured."""
        if self._endpoint is not None and self._client is None:
            self._client = httpx.AsyncClient(
                headers=self._headers,
                timeout=_REQUEST_TIMEOUT_SECONDS,
            )

    async def shutdown(self) -> None:
        """Close only an HTTP client owned by this provider."""
        client, self._client = self._client, None
        if client is not None and self._owns_client:
            await client.aclose()

    async def read_user(self, username: str) -> EfficiencyObservation:
        """Read current efficiency for one exact user label value."""
        return await self._read("user", username)

    async def read_community(self, community: str) -> EfficiencyObservation:
        """Read current efficiency for one exact community label value."""
        return await self._read("community", community)

    async def read_platform(self) -> EfficiencyObservation:
        """Read current efficiency for all labelled workload Pods in scope."""
        return await self._read("platform", None)

    async def read_session(
        self,
        session_id: str,
        *,
        start_time: datetime,
        window_end: datetime,
        job_names: tuple[str, ...] = (),
    ) -> EfficiencyObservation:
        """Read duration efficiency for one session over its bounded window."""
        if window_end < start_time:
            raise ProviderExecutionError("Session efficiency window end precedes its start")
        if not isinstance(session_id, str) or not session_id:
            raise ProviderExecutionError("PromQL session id must be a non-empty string")
        duration_seconds = int(
            min((window_end - start_time).total_seconds(), _MAX_SESSION_WINDOW_SECONDS)
        )
        query = _session_query(
            session_id=session_id,
            cluster=self._cluster,
            namespaces=self._namespaces,
            duration_seconds=duration_seconds,
            job_names=job_names,
        )
        return await self._execute_efficiency_read(
            scope="session", query=query, evaluation_time=window_end
        )

    async def _execute_efficiency_read(
        self,
        *,
        scope: str,
        query: str,
        evaluation_time: datetime | None = None,
    ) -> EfficiencyObservation:
        """Execute one fixed query and validate its efficiency vector."""
        if self._endpoint is None:
            raise ProviderUnavailableError("PromQL endpoint is not configured")
        started = perf_counter()
        status = "ok"
        try:
            payload = await self._request(query, evaluation_time=evaluation_time)
            return _validate_response(
                payload,
                max_sample_age_seconds=_MAX_SAMPLE_AGE_SECONDS,
                future_sample_tolerance_seconds=_FUTURE_SAMPLE_TOLERANCE_SECONDS,
                evaluation_time=evaluation_time,
            )
        except asyncio.CancelledError:
            status = "cancelled"
            raise
        except Exception:
            status = "error"
            raise
        finally:
            self._telemetry.record_provider_duration(
                provider=self.name,
                scope=scope,
                status=status,
                seconds=perf_counter() - started,
            )

    async def _read(self, scope: _PROMQL_SCOPE, subject: str | None) -> EfficiencyObservation:
        """Execute and validate one fixed instant query."""
        if scope != "platform" and (not isinstance(subject, str) or not subject):
            raise ProviderExecutionError("PromQL subject must be a non-empty string")
        query = _query(
            scope=scope,
            subject=subject,
            cluster=self._cluster,
            namespaces=self._namespaces,
        )
        return await self._execute_efficiency_read(scope=scope, query=query)

    async def _request(
        self,
        query: str,
        *,
        evaluation_time: datetime | None = None,
    ) -> Any:
        """POST the fixed query and decode one bounded Prometheus response."""
        client = self._client
        endpoint = self._endpoint
        if client is None or endpoint is None:
            raise ProviderUnavailableError("PromQL provider has not started")
        data: dict[str, str] = {"query": query}
        if evaluation_time is not None:
            data["time"] = str(evaluation_time.timestamp())
        try:
            async with client.stream(
                "POST",
                endpoint,
                data=data,
                headers=self._headers,
            ) as response:
                response.raise_for_status()
                body = await _bounded_response_body(response, _MAX_RESPONSE_BYTES)
            try:
                return json.loads(body)
            except (json.JSONDecodeError, UnicodeDecodeError) as exc:
                raise ProviderExecutionError("PromQL response was not valid JSON") from exc
        except httpx.HTTPStatusError as exc:
            if exc.response.status_code >= 500:
                raise ProviderUnavailableError("PromQL backend is unavailable") from exc
            raise ProviderExecutionError("PromQL backend rejected the fixed query") from exc
        except httpx.RequestError as exc:
            raise ProviderUnavailableError("PromQL backend is unavailable") from exc
