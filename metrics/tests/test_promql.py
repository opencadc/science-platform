from __future__ import annotations

import asyncio
from datetime import UTC, datetime, timedelta
from decimal import Decimal
import json
from urllib.parse import parse_qs

import httpx
import pytest

import metrics.providers.promql as promql_module
from metrics.core.settings import Settings
from metrics.errors import ProviderExecutionError, ProviderUnavailableError
from metrics.providers.promql import PromQLProvider
from metrics.services.models import EfficiencyObservation
from metrics.telemetry import MetricsRecorder


pytestmark = pytest.mark.anyio


class RecordingTelemetry(MetricsRecorder):
    """Capture provider status labels at the public provider seam."""

    def __init__(self) -> None:
        self.statuses: list[str] = []

    def record_provider_duration(
        self,
        *,
        provider: str,
        scope: str,
        status: str,
        seconds: float,
    ) -> None:
        del provider, scope, seconds
        self.statuses.append(status)


def _settings(**promql: object) -> Settings:
    """Build mandatory-Redis settings with two namespaces and one cluster."""
    return Settings.model_validate(
        {
            "cluster_name": "cluster-a",
            "redis_url": "redis://redis.test:6379/0",
            "cache": {"key_secret": "x" * 32},
            "providers": {
                "kueue": {
                    "cluster_queues": ["cq-science", "cq-physics"],
                    "namespaces": ["workloads", "batch"],
                },
                "promql": {
                    "base_url": "http://prometheus.test:9090/prometheus",
                    **promql,
                },
            },
        }
    )


def _timestamp() -> datetime:
    """Return a current UTC timestamp with stable JSON precision."""
    current = datetime.now(UTC)
    return current.replace(microsecond=current.microsecond // 1_000 * 1_000)


def _sample(
    resource: str,
    value: object,
    timestamp: datetime,
    *,
    labels: dict[str, str] | None = None,
) -> dict[str, object]:
    """Build one Prometheus vector sample."""
    metric = {"resource": resource, **(labels or {})}
    return {"metric": metric, "value": [timestamp.timestamp(), value]}


def _payload(*samples: dict[str, object]) -> dict[str, object]:
    """Build a Prometheus success response."""
    return {"status": "success", "data": {"resultType": "vector", "result": list(samples)}}


def _successful_payload(
    timestamp: datetime,
    *,
    cpu: object = "0.25",
    memory: object = "0.5",
) -> dict[str, object]:
    """Build the exact two-resource response expected from the fixed query."""
    return _payload(_sample("cpu", cpu, timestamp), _sample("memory", memory, timestamp))


def _client(
    response: httpx.Response | dict[str, object],
    calls: list[httpx.Request],
) -> httpx.AsyncClient:
    """Build an injected client that records every request."""

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request)
        if isinstance(response, httpx.Response):
            return response
        return httpx.Response(200, json=response)

    return httpx.AsyncClient(transport=httpx.MockTransport(handler))


async def test_successful_cpu_and_memory_parsing_uses_one_form_post() -> None:
    observed = _timestamp()
    calls: list[httpx.Request] = []
    client = _client(_successful_payload(observed), calls)
    try:
        result = await PromQLProvider(_settings(), client=client).read_user("ada")
    finally:
        await client.aclose()

    assert isinstance(result, EfficiencyObservation)
    assert result.efficiencies == {"cpu": Decimal("0.25"), "memory": Decimal("0.5")}
    assert abs((result.observed_at - observed).total_seconds()) < 0.01
    assert len(calls) == 1
    request = calls[0]
    assert request.method == "POST"
    assert request.url.path == "/prometheus/api/v1/query"
    assert set(parse_qs(request.content.decode())) == {"query"}
    assert request.headers["content-type"].startswith("application/x-www-form-urlencoded")


async def test_promql_telemetry_records_ok_after_response_validation() -> None:
    telemetry = RecordingTelemetry()
    client = _client(_successful_payload(_timestamp()), [])
    try:
        await PromQLProvider(_settings(), client=client, telemetry=telemetry).read_user("ada")
    finally:
        await client.aclose()

    assert telemetry.statuses == ["ok"]


@pytest.mark.parametrize(
    ("method", "subject", "expected_matcher"),
    [
        (
            "read_user",
            "ada",
            'label_canfar_net_username="ada"',
        ),
        (
            "read_community",
            "astronomy",
            'label_canfar_net_community="astronomy"',
        ),
    ],
)
async def test_user_and_community_use_exact_attribution_matchers(
    method: str,
    subject: str,
    expected_matcher: str,
) -> None:
    calls: list[httpx.Request] = []
    client = _client(_successful_payload(_timestamp()), calls)
    try:
        await getattr(PromQLProvider(_settings(), client=client), method)(subject)
    finally:
        await client.aclose()

    query = parse_qs(calls[0].content.decode())["query"][0]
    assert expected_matcher in query
    assert 'cluster="cluster-a"' in query
    assert 'namespace=~"^(?:workloads|batch)$"' in query


async def test_platform_uses_labelled_workloads_without_platform_or_cohort_labels() -> None:
    calls: list[httpx.Request] = []
    client = _client(_successful_payload(_timestamp()), calls)
    try:
        await PromQLProvider(_settings(), client=client).read_platform()
    finally:
        await client.aclose()

    query = parse_qs(calls[0].content.decode())["query"][0]
    assert 'label_canfar_net_username!=""' in query
    assert 'label_canfar_net_community!=""' in query
    assert "canfar.net/platform" not in query
    assert "cohort" not in query.lower()


def test_namespace_regex_escapes_regex_metacharacters() -> None:
    assert promql_module._namespace_regex(["tenant.+", "literal"]) == ("^(?:tenant\\.\\+|literal)$")


async def test_subject_string_is_escaped_and_never_inserted_raw() -> None:
    subject = 'ada"\\line\n'
    calls: list[httpx.Request] = []
    client = _client(_successful_payload(_timestamp()), calls)
    try:
        await PromQLProvider(_settings(), client=client).read_user(subject)
    finally:
        await client.aclose()

    query = parse_qs(calls[0].content.decode())["query"][0]
    assert f"label_canfar_net_username={json.dumps(subject, ensure_ascii=True)}" in query
    assert subject not in query


async def test_query_joins_running_pods_and_scopes_every_metric() -> None:
    calls: list[httpx.Request] = []
    client = _client(_successful_payload(_timestamp()), calls)
    try:
        await PromQLProvider(_settings(), client=client).read_user("ada")
    finally:
        await client.aclose()

    query = parse_qs(calls[0].content.decode())["query"][0]
    assert "container_cpu_usage_seconds_total" in query
    assert "container_memory_working_set_bytes" in query
    assert "kube_pod_container_resource_requests" in query
    assert "kube_pod_labels" in query
    assert "kube_pod_status_phase{" in query
    assert 'phase="Running"' in query
    assert "rate(" in query and "[5m]" in query
    assert "and on (cluster,namespace,pod)" in query
    assert 'resource="cpu"' in query and 'unit="core"' in query
    assert 'resource="memory"' in query and 'unit="byte"' in query
    assert query.count('cluster="cluster-a"') >= 8
    assert 'cluster="other-cluster"' not in query


async def test_mimir_tenant_and_base_path_are_preserved() -> None:
    calls: list[httpx.Request] = []
    client = _client(_successful_payload(_timestamp()), calls)
    try:
        await PromQLProvider(
            _settings(
                base_url="https://mimir.test:9009/prom/api",
                mimir_tenant_id="canfar",
            ),
            client=client,
        ).read_community("astronomy")
    finally:
        await client.aclose()

    assert calls[0].url == "https://mimir.test:9009/prom/api/api/v1/query"
    assert calls[0].headers["x-scope-orgid"] == "canfar"


@pytest.mark.parametrize(
    "payload",
    [
        {"status": "error", "errorType": "bad_data"},
        {"status": "success", "data": {"resultType": "matrix", "result": []}},
        {"status": "success", "data": {"resultType": "vector", "result": []}},
        {"status": "success", "data": {"resultType": "vector", "result": [{"metric": {}}]}},
        _payload(
            _sample("cpu", "0.1", _timestamp()),
            _sample("memory", "0.2", _timestamp(), labels={"extra": "x"}),
        ),
    ],
)
async def test_malformed_success_vector_is_execution_error(payload: dict[str, object]) -> None:
    calls: list[httpx.Request] = []
    client = _client(payload, calls)
    try:
        with pytest.raises(ProviderExecutionError):
            await PromQLProvider(_settings(), client=client).read_user("ada")
    finally:
        await client.aclose()


async def test_promql_telemetry_records_vector_validation_as_error() -> None:
    telemetry = RecordingTelemetry()
    client = _client(
        {"status": "success", "data": {"resultType": "matrix", "result": []}},
        [],
    )
    try:
        with pytest.raises(ProviderExecutionError):
            await PromQLProvider(_settings(), client=client, telemetry=telemetry).read_user("ada")
    finally:
        await client.aclose()

    assert telemetry.statuses == ["error"]


async def test_promql_telemetry_records_cancellation_and_reraises() -> None:
    telemetry = RecordingTelemetry()

    async def handler(_request: httpx.Request) -> httpx.Response:
        raise asyncio.CancelledError()

    client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    try:
        with pytest.raises(asyncio.CancelledError):
            await PromQLProvider(_settings(), client=client, telemetry=telemetry).read_user("ada")
    finally:
        await client.aclose()

    assert telemetry.statuses == ["cancelled"]


@pytest.mark.parametrize(
    "samples",
    [
        lambda now: (_sample("cpu", "0.1", now), _sample("cpu", "0.2", now)),
        lambda now: (_sample("gpu", "0.1", now), _sample("memory", "0.2", now)),
    ],
)
async def test_duplicate_or_unknown_resources_fail_closed(samples) -> None:
    now = _timestamp()
    calls: list[httpx.Request] = []
    client = _client(_payload(*samples(now)), calls)
    try:
        with pytest.raises(ProviderExecutionError):
            await PromQLProvider(_settings(), client=client).read_platform()
    finally:
        await client.aclose()


@pytest.mark.parametrize("value", ["NaN", "+Inf", "-Inf", "-0.1", True])
async def test_nonfinite_or_negative_efficiency_fails_closed(value: object) -> None:
    now = _timestamp()
    calls: list[httpx.Request] = []
    client = _client(_successful_payload(now, cpu=value), calls)
    try:
        with pytest.raises(ProviderExecutionError):
            await PromQLProvider(_settings(), client=client).read_user("ada")
    finally:
        await client.aclose()


async def test_zero_denominator_infinite_result_and_empty_vector_fail() -> None:
    now = _timestamp()
    for payload in (
        _payload(_sample("cpu", "+Inf", now), _sample("memory", "0.2", now)),
        _payload(),
    ):
        calls: list[httpx.Request] = []
        client = _client(payload, calls)
        try:
            with pytest.raises(ProviderExecutionError):
                await PromQLProvider(_settings(), client=client).read_user("ada")
        finally:
            await client.aclose()


@pytest.mark.parametrize("offset", [timedelta(minutes=-11), timedelta(seconds=6)])
async def test_stale_and_future_timestamps_follow_configured_bounds(
    monkeypatch: pytest.MonkeyPatch,
    offset: timedelta,
) -> None:
    validation_now = datetime(2025, 1, 1, tzinfo=UTC)
    monkeypatch.setattr(promql_module, "_validation_now", lambda: validation_now)
    timestamp = validation_now + offset
    calls: list[httpx.Request] = []
    client = _client(_successful_payload(timestamp), calls)
    try:
        with pytest.raises(ProviderExecutionError, match="stale or future"):
            await PromQLProvider(
                _settings(max_sample_age_seconds=600, future_sample_tolerance_seconds=5),
                client=client,
            ).read_user("ada")
    finally:
        await client.aclose()


async def test_efficiency_samples_must_share_one_timestamp() -> None:
    now = _timestamp()
    calls: list[httpx.Request] = []
    client = _client(
        _payload(
            _sample("cpu", "0.1", now),
            _sample("memory", "0.2", now + timedelta(seconds=1)),
        ),
        calls,
    )
    try:
        with pytest.raises(ProviderExecutionError, match="different timestamps"):
            await PromQLProvider(_settings(), client=client).read_platform()
    finally:
        await client.aclose()


@pytest.mark.parametrize(
    "failure",
    [
        httpx.ReadTimeout("timeout"),
        httpx.ConnectError("connection failed"),
    ],
)
async def test_endpoint_transport_failures_are_unavailable(failure: Exception) -> None:
    calls: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request)
        raise failure

    client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    try:
        with pytest.raises(ProviderUnavailableError):
            await PromQLProvider(_settings(), client=client).read_user("ada")
    finally:
        await client.aclose()


@pytest.mark.parametrize(
    ("status_code", "error_type"),
    [(500, ProviderUnavailableError), (400, ProviderExecutionError)],
)
async def test_http_status_failures_are_classified(
    status_code: int,
    error_type: type[Exception],
) -> None:
    calls: list[httpx.Request] = []
    client = _client(httpx.Response(status_code), calls)
    try:
        with pytest.raises(error_type):
            await PromQLProvider(_settings(), client=client).read_community("astronomy")
    finally:
        await client.aclose()


async def test_invalid_json_and_response_size_fail_as_execution_errors() -> None:
    calls: list[httpx.Request] = []
    client = _client(httpx.Response(200, content=b"not-json"), calls)
    try:
        with pytest.raises(ProviderExecutionError, match="valid JSON"):
            await PromQLProvider(_settings(), client=client).read_user("ada")
    finally:
        await client.aclose()

    body = json.dumps(_successful_payload(_timestamp())).encode()
    calls = []
    oversized = httpx.Response(
        200,
        content=body,
        headers={"Content-Length": str(len(body))},
    )
    client = _client(oversized, calls)
    try:
        with pytest.raises(ProviderExecutionError, match="byte limit"):
            await PromQLProvider(
                _settings(max_response_bytes=len(body) - 1),
                client=client,
            ).read_user("ada")
    finally:
        await client.aclose()


async def test_naive_cutoff_is_rejected_before_http_request() -> None:
    calls: list[httpx.Request] = []
    client = _client(_successful_payload(_timestamp()), calls)
    try:
        with pytest.raises(ProviderExecutionError, match="timezone-aware"):
            await PromQLProvider(_settings(), client=client).read_user("ada", datetime(2025, 1, 1))
    finally:
        await client.aclose()
    assert calls == []


async def test_missing_endpoint_is_not_an_active_provider() -> None:
    calls: list[httpx.Request] = []
    client = _client(_successful_payload(_timestamp()), calls)
    provider = PromQLProvider(_settings(), client=client)
    provider._endpoint = None
    try:
        with pytest.raises(ProviderUnavailableError, match="endpoint"):
            await provider.read_platform()
    finally:
        await client.aclose()


async def test_read_session_posts_window_end_as_query_time() -> None:
    """Session efficiency evaluates PromQL at the session window end."""
    window_end = datetime(2026, 1, 1, 13, 0, tzinfo=UTC)
    start_time = datetime(2026, 1, 1, 12, 0, tzinfo=UTC)
    calls: list[httpx.Request] = []
    client = _client(_successful_payload(window_end), calls)
    try:
        await PromQLProvider(_settings(), client=client).read_session(
            "sess-1",
            start_time=start_time,
            window_end=window_end,
        )
    finally:
        await client.aclose()

    body = parse_qs(calls[0].content.decode())
    assert body["time"] == [str(window_end.timestamp())]


def test_session_cpu_efficiency_uses_avg_over_time_and_duration_seconds() -> None:
    """Session CPU efficiency scales requests by the bounded window duration."""
    query = promql_module._session_query(
        session_id="sess-1",
        cluster="cluster-a",
        namespaces=["workloads"],
        duration_seconds=1800,
    )
    assert "avg_over_time(" in query
    assert "sum_over_time(" not in query.split("or")[0]
    assert "* 1800" in query
    assert "* 60" not in query


def test_session_sample_age_is_validated_against_evaluation_time() -> None:
    """Session efficiency rejects samples stale relative to the query time."""
    evaluation_time = datetime(2026, 1, 1, 13, 0, tzinfo=UTC)
    stale_sample_time = evaluation_time - timedelta(minutes=11)
    payload = _payload(
        _sample("cpu", "0.4", stale_sample_time),
        _sample("memory", "0.3", stale_sample_time),
    )
    with pytest.raises(ProviderExecutionError, match="stale or future"):
        promql_module._validate_response(
            payload,
            max_series=10,
            max_sample_age_seconds=600,
            future_sample_tolerance_seconds=5,
            cutoff=None,
            evaluation_time=evaluation_time,
        )
