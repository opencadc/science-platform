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
_EFFICIENCY_RESOURCES = frozenset({"cpu", "memory"})
_NAMESPACE_LABEL = "namespace"
_PROMQL_SCOPE = Literal["user", "community", "platform", "session"]
_DEFAULT_REQUEST_TIMEOUT_SECONDS = 5.0
_DEFAULT_MAX_SAMPLE_AGE_SECONDS = 300
_DEFAULT_FUTURE_SAMPLE_TOLERANCE_SECONDS = 30
_DEFAULT_MAX_SERIES = 3_000
_DEFAULT_MAX_RESPONSE_BYTES = 4 * 1024 * 1024
_DEFAULT_SCRAPE_INTERVAL_SECONDS = 60
_MAX_SESSION_WINDOW_SECONDS = 6 * 60 * 60


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


_WINDOW_PLACEHOLDER = "__WINDOW__"


def _session_selected_pods(
    *,
    session_id: str,
    cluster: str,
    namespaces: str,
) -> str:
    """Select session Pods through a window-stable label join."""
    labels = _selector(
        _POD_LABELS_METRIC,
        cluster=cluster,
        namespaces=namespaces,
        matchers=(_matcher(_SESSION_ID_LABEL, "=", session_id),),
    )
    return f"max_over_time({labels}[{_WINDOW_PLACEHOLDER}])"


def _render_window(duration_seconds: int) -> str:
    """Render one bounded PromQL range selector."""
    bounded = max(60, min(duration_seconds, _MAX_SESSION_WINDOW_SECONDS))
    return f"{bounded}s"


def _session_cpu_efficiency(
    selected_pods: str,
    *,
    cluster: str,
    namespaces: str,
    window: str,
) -> str:
    """Return CPU duration efficiency for one session window."""
    usage_source = _selector(
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
    request_source = _selector(
        _POD_REQUEST_METRIC,
        cluster=cluster,
        namespaces=namespaces,
        matchers=(
            _matcher("resource", "=", "cpu"),
            _matcher("unit", "=", "core"),
            _matcher("pod", "!=", ""),
            _matcher("container", "!=", ""),
        ),
    )
    usage = (
        f"sum(increase({usage_source}[{window}]) "
        f"and on (cluster,namespace,pod) {selected_pods})"
    )
    requested = (
        f"sum(sum_over_time({request_source}[{window}]) "
        f"and on (cluster,namespace,pod) {selected_pods})"
    )
    return f"({usage}) / ({requested} * {_DEFAULT_SCRAPE_INTERVAL_SECONDS})"


def _session_memory_efficiency(
    selected_pods: str,
    *,
    cluster: str,
    namespaces: str,
    window: str,
) -> str:
    """Return memory duration efficiency for one session window."""
    usage_source = _selector(
        _MEMORY_USAGE_METRIC,
        cluster=cluster,
        namespaces=namespaces,
        matchers=(
            _matcher("pod", "!=", ""),
            _matcher("container", "!=", ""),
            _matcher("container", "!=", "POD"),
        ),
    )
    request_source = _selector(
        _POD_REQUEST_METRIC,
        cluster=cluster,
        namespaces=namespaces,
        matchers=(
            _matcher("resource", "=", "memory"),
            _matcher("unit", "=", "byte"),
            _matcher("pod", "!=", ""),
            _matcher("container", "!=", ""),
        ),
    )
    usage = (
        f"sum(sum_over_time({usage_source}[{window}]) "
        f"and on (cluster,namespace,pod) {selected_pods})"
    )
    requested = (
        f"sum(sum_over_time({request_source}[{window}]) "
        f"and on (cluster,namespace,pod) {selected_pods})"
    )
    return f"({usage}) / ({requested})"


def _session_query(
    *,
    session_id: str,
    cluster: str,
    namespaces: list[str],
    duration_seconds: int,
) -> str:
    """Render the server-owned session duration efficiency query."""
    namespace_pattern = _namespace_regex(namespaces)
    window = _render_window(duration_seconds)
    selected = _session_selected_pods(
        session_id=session_id,
        cluster=cluster,
        namespaces=namespace_pattern,
    ).replace(_WINDOW_PLACEHOLDER, window)
    cpu_ratio = _session_cpu_efficiency(
        selected,
        cluster=cluster,
        namespaces=namespace_pattern,
        window=window,
    )
    memory_ratio = _session_memory_efficiency(
        selected,
        cluster=cluster,
        namespaces=namespace_pattern,
        window=window,
    )
    return f"{_label_resource(cpu_ratio, 'cpu')} or {_label_resource(memory_ratio, 'memory')}"


def _validate_session_response(
    payload: Any,
    *,
    max_series: int,
    max_sample_age_seconds: int,
    future_sample_tolerance_seconds: int,
    cutoff: datetime | None,
) -> EfficiencyObservation:
    """Validate and normalize one or two session efficiency samples."""
    if not isinstance(payload, dict) or payload.get("status") != "success":
        raise ProviderExecutionError("PromQL API did not return success")
    data = payload.get("data")
    if not isinstance(data, dict) or data.get("resultType") != "vector":
        raise ProviderExecutionError("PromQL API did not return an instant vector")
    result = data.get("result")
    if not isinstance(result, list) or len(result) > max_series or not result:
        raise ProviderExecutionError("PromQL session vector was empty or too large")

    validation_now = _validation_now()
    now_seconds = Decimal(str(validation_now.timestamp()))
    cutoff_seconds = Decimal(str(cutoff.timestamp())) if cutoff is not None else None
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
        if resource not in _EFFICIENCY_RESOURCES or resource in efficiencies:
            raise ProviderExecutionError("PromQL returned an unknown efficiency resource")

        sample_timestamp, sample_at = _sample_timestamp(sample[0])
        if observed_timestamp is None:
            observed_timestamp = sample_timestamp
            observed_at = sample_at
        elif sample_timestamp != observed_timestamp:
            raise ProviderExecutionError("PromQL efficiency samples have different timestamps")

        age = now_seconds - sample_timestamp
        if age > Decimal(max_sample_age_seconds) or age < -Decimal(future_sample_tolerance_seconds):
            raise ProviderExecutionError("PromQL returned a stale or future sample")
        if cutoff_seconds is not None and sample_timestamp > cutoff_seconds + Decimal(
            future_sample_tolerance_seconds
        ):
            raise ProviderExecutionError("PromQL returned a sample after the requested cutoff")
        efficiencies[resource] = _sample_value(sample[1])

    if observed_at is None or not efficiencies:
        raise ProviderExecutionError("PromQL session efficiency vector is incomplete")
    return EfficiencyObservation(observed_at=observed_at, efficiencies=efficiencies)


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


def _normalise_cutoff(value: datetime | None) -> datetime | None:
    """Normalize an optional caller observation time to UTC."""
    if value is None:
        return None
    if value.tzinfo is None or value.utcoffset() is None:
        raise ProviderExecutionError("PromQL observation time must be timezone-aware")
    return value.astimezone(UTC)


def _result_vector(payload: Any, max_series: int) -> list[Any]:
    """Validate the Prometheus success envelope and return its vector."""
    if not isinstance(payload, dict) or payload.get("status") != "success":
        raise ProviderExecutionError("PromQL API did not return success")
    data = payload.get("data")
    if not isinstance(data, dict) or data.get("resultType") != "vector":
        raise ProviderExecutionError("PromQL API did not return an instant vector")
    result = data.get("result")
    if not isinstance(result, list) or len(result) > max_series:
        raise ProviderExecutionError("PromQL vector cardinality exceeded the configured limit")
    if len(result) != 2:
        raise ProviderExecutionError("PromQL efficiency vector must contain cpu and memory")
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
    max_series: int,
    max_sample_age_seconds: int,
    future_sample_tolerance_seconds: int,
    cutoff: datetime | None,
) -> EfficiencyObservation:
    """Validate and normalize the two controlled efficiency samples."""
    result = _result_vector(payload, max_series)
    validation_now = _validation_now()
    now_seconds = Decimal(str(validation_now.timestamp()))
    cutoff_seconds = Decimal(str(cutoff.timestamp())) if cutoff is not None else None
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
        if resource not in _EFFICIENCY_RESOURCES:
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
        if cutoff_seconds is not None and sample_timestamp > cutoff_seconds + Decimal(
            future_sample_tolerance_seconds
        ):
            raise ProviderExecutionError("PromQL returned a sample after the requested cutoff")
        efficiencies[resource] = _sample_value(sample[1])

    if set(efficiencies) != _EFFICIENCY_RESOURCES or observed_at is None:
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
        self._request_timeout_seconds = self._config.request_timeout_seconds
        self._max_sample_age_seconds = self._config.max_sample_age_seconds
        self._future_sample_tolerance_seconds = self._config.future_sample_tolerance_seconds
        self._max_series = self._config.max_series
        self._max_response_bytes = self._config.max_response_bytes
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
                timeout=self._request_timeout_seconds,
            )

    async def shutdown(self) -> None:
        """Close only an HTTP client owned by this provider."""
        client, self._client = self._client, None
        if client is not None and self._owns_client:
            await client.aclose()

    async def read_user(
        self, username: str, observed_at: datetime | None = None
    ) -> EfficiencyObservation:
        """Read current efficiency for one exact user label value."""
        return await self._read("user", username, observed_at)

    async def read_community(
        self, community: str, observed_at: datetime | None = None
    ) -> EfficiencyObservation:
        """Read current efficiency for one exact community label value."""
        return await self._read("community", community, observed_at)

    async def read_platform(self, observed_at: datetime | None = None) -> EfficiencyObservation:
        """Read current efficiency for all labelled workload Pods in scope."""
        return await self._read("platform", None, observed_at)

    async def read_session(
        self,
        session_id: str,
        *,
        start_time: datetime,
        window_end: datetime,
        observed_at: datetime | None = None,
    ) -> EfficiencyObservation:
        """Read duration efficiency for one session over its bounded window."""
        if window_end < start_time:
            raise ProviderExecutionError("Session efficiency window end precedes its start")
        duration_seconds = int(min((window_end - start_time).total_seconds(), _MAX_SESSION_WINDOW_SECONDS))
        return await self._read_session(session_id, duration_seconds, observed_at)

    async def _read_session(
        self,
        session_id: str,
        duration_seconds: int,
        observed_at: datetime | None,
    ) -> EfficiencyObservation:
        """Execute and validate one fixed session duration query."""
        if self._endpoint is None:
            raise ProviderUnavailableError("PromQL endpoint is not configured")
        if not isinstance(session_id, str) or not session_id:
            raise ProviderExecutionError("PromQL session id must be a non-empty string")
        cutoff = _normalise_cutoff(observed_at)
        query = _session_query(
            session_id=session_id,
            cluster=self._cluster,
            namespaces=self._namespaces,
            duration_seconds=duration_seconds,
        )
        started = perf_counter()
        status = "ok"
        try:
            payload = await self._request(query)
            return _validate_session_response(
                payload,
                max_series=self._max_series,
                max_sample_age_seconds=self._max_sample_age_seconds,
                future_sample_tolerance_seconds=self._future_sample_tolerance_seconds,
                cutoff=cutoff,
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
                scope="session",
                status=status,
                seconds=perf_counter() - started,
            )

    async def _read(
        self,
        scope: _PROMQL_SCOPE,
        subject: str | None,
        observed_at: datetime | None,
    ) -> EfficiencyObservation:
        """Execute and validate one fixed instant query."""
        if self._endpoint is None:
            raise ProviderUnavailableError("PromQL endpoint is not configured")
        if scope != "platform" and (not isinstance(subject, str) or not subject):
            raise ProviderExecutionError("PromQL subject must be a non-empty string")
        cutoff = _normalise_cutoff(observed_at)
        query = _query(
            scope=scope,
            subject=subject,
            cluster=self._cluster,
            namespaces=self._namespaces,
        )
        started = perf_counter()
        status = "ok"
        try:
            payload = await self._request(query)
            return _validate_response(
                payload,
                max_series=self._max_series,
                max_sample_age_seconds=self._max_sample_age_seconds,
                future_sample_tolerance_seconds=self._future_sample_tolerance_seconds,
                cutoff=cutoff,
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

    async def _request(self, query: str) -> Any:
        """POST the fixed query and decode one bounded Prometheus response."""
        client = self._client
        endpoint = self._endpoint
        if client is None or endpoint is None:
            raise ProviderUnavailableError("PromQL provider has not started")
        try:
            async with client.stream(
                "POST",
                endpoint,
                data={"query": query},
                headers=self._headers,
            ) as response:
                response.raise_for_status()
                body = await _bounded_response_body(response, self._max_response_bytes)
            try:
                return json.loads(body)
            except (json.JSONDecodeError, UnicodeDecodeError) as exc:
                raise ProviderExecutionError("PromQL response was not valid JSON") from exc
        except ProviderExecutionError:
            raise
        except httpx.HTTPStatusError as exc:
            if exc.response.status_code >= 500:
                raise ProviderUnavailableError("PromQL backend is unavailable") from exc
            raise ProviderExecutionError("PromQL backend rejected the fixed query") from exc
        except httpx.RequestError as exc:
            raise ProviderUnavailableError("PromQL backend is unavailable") from exc
