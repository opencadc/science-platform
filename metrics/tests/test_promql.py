from __future__ import annotations

import asyncio
from contextlib import contextmanager
from datetime import UTC, datetime, timedelta
from decimal import Decimal
from urllib.parse import parse_qs

import httpx
import pytest

from metrics.cache import FRESHNESS_POLICIES, CacheIdentity, InMemoryCoordinator
from metrics.core.settings import Settings
from metrics.errors import ProviderExecutionError, ProviderUnavailableError
from metrics.providers.promql import (
    COMPLETE_METRIC,
    REQUESTED_METRIC,
    SOURCE_REVISION,
    USAGE_METRIC,
    PromQLProvider,
)
from metrics.services.accounting import AccountingService
from metrics.services.models import AccountingSnapshot, ActiveWorkloadLifetime
from metrics.telemetry import NoopMetricsRecorder

pytestmark = pytest.mark.anyio


def _settings(**promql: object) -> Settings:
    return Settings.model_validate(
        {
            "cluster_name": "fixture",
            "cache": {"backend": "memory"},
            "providers": {
                "promql": {
                    "enabled": True,
                    "base_url": "http://prometheus.test:9090",
                    **promql,
                }
            },
        }
    )


def _series(
    name: str,
    value: str,
    *,
    timestamp: datetime | None = None,
    reason: str | None = None,
    username: str = "ada",
    community: str = "science",
    pod_uid: str = "pod-1",
) -> dict[str, object]:
    labels = {
        "__name__": name,
        "cluster": "fixture",
        "namespace": "workloads",
        "pod_uid": pod_uid,
        "resource": "cpu",
        "canfar_username": username,
        "canfar_community": community,
        "source_revision": SOURCE_REVISION,
        "unit": "boolean" if name == COMPLETE_METRIC else "core-hours",
    }
    if reason is not None:
        labels["reason"] = reason
    return {
        "metric": labels,
        "value": [(timestamp or datetime.now(UTC)).timestamp(), value],
    }


def _payload(*series: dict[str, object]) -> dict[str, object]:
    return {"status": "success", "data": {"resultType": "vector", "result": list(series)}}


def _complete_payload(*, timestamp: datetime | None = None) -> dict[str, object]:
    observed = timestamp or datetime.now(UTC)
    return _payload(
        _series(USAGE_METRIC, "1.5", timestamp=observed),
        _series(REQUESTED_METRIC, "4", timestamp=observed),
        _series(COMPLETE_METRIC, "1", timestamp=observed, reason="complete"),
    )


async def test_named_template_uses_only_form_post_and_optional_mimir_tenant() -> None:
    request: httpx.Request | None = None

    async def respond(current: httpx.Request) -> httpx.Response:
        nonlocal request
        request = current
        return httpx.Response(200, json=_complete_payload())

    client = httpx.AsyncClient(transport=httpx.MockTransport(respond))
    provider = PromQLProvider(
        _settings(mimir_tenant_id="tenant-a"),
        client=client,
    )
    assert (
        provider.cache_fingerprint()
        != PromQLProvider(
            _settings(mimir_tenant_id="tenant-b"),
            client=client,
        ).cache_fingerprint()
    )
    assert "tenant-a" not in provider.cache_fingerprint()
    result = await provider.read_user("ada")

    assert result.ready
    assert result.resources["cpu"].usage == Decimal("1.5")
    assert result.pod_uids == frozenset({"pod-1"})
    assert result.coverage["cpu"] == frozenset({"pod-1"})
    assert request is not None
    assert request.method == "POST"
    assert request.url.path == "/api/v1/query"
    assert request.url.query == b""
    assert request.headers["X-Scope-OrgID"] == "tenant-a"
    form = parse_qs(request.content.decode())
    assert set(form) == {"query"}
    assert 'canfar_username="ada"' in form["query"][0]
    assert 'kube_pod_status_phase{phase="Running"}' in form["query"][0]
    assert "and on (namespace,pod_uid)" in form["query"][0]
    assert "query_range" not in form["query"][0]
    await client.aclose()


async def test_community_template_aggregates_direct_series_and_rejects_cross_community() -> None:
    observed = datetime.now(UTC)
    payload = _payload(
        *(
            _series(
                metric, value, timestamp=observed, reason=reason, username=username, pod_uid=pod
            )
            for username, pod, values in (
                ("ada", "pod-1", ("1", "2")),
                ("grace", "pod-2", ("9", "10")),
            )
            for metric, value, reason in (
                (USAGE_METRIC, values[0], None),
                (REQUESTED_METRIC, values[1], None),
                (COMPLETE_METRIC, "1", "complete"),
            )
        )
    )
    request: httpx.Request | None = None

    def respond(current: httpx.Request) -> httpx.Response:
        nonlocal request
        request = current
        return httpx.Response(200, json=payload)

    client = httpx.AsyncClient(transport=httpx.MockTransport(respond))
    provider = PromQLProvider(_settings(), client=client)
    result = await provider.read_community("science")

    assert result.resources["cpu"].usage == Decimal("10")
    assert result.resources["cpu"].requested == Decimal("12")
    assert request is not None
    query = parse_qs(request.content.decode())["query"][0]
    assert 'canfar_community="science"' in query
    assert "canfar_username=" not in query

    wrong = _payload(
        _series(USAGE_METRIC, "1", community="physics"),
        _series(REQUESTED_METRIC, "2", community="physics"),
        _series(COMPLETE_METRIC, "1", community="physics", reason="complete"),
    )
    wrong_client = httpx.AsyncClient(
        transport=httpx.MockTransport(lambda _request: httpx.Response(200, json=wrong))
    )
    with pytest.raises(ProviderExecutionError, match="selected population"):
        await PromQLProvider(_settings(), client=wrong_client).read_community("science")
    await client.aclose()
    await wrong_client.aclose()


async def test_telemetry_names_template_but_never_query_or_subject() -> None:
    class Recorder(NoopMetricsRecorder):
        def __init__(self) -> None:
            self.attributes: list[dict[str, str]] = []

        @contextmanager
        def span(self, _name: str, attributes=None):
            self.attributes.append(dict(attributes or {}))
            yield None

    recorder = Recorder()
    client = httpx.AsyncClient(
        transport=httpx.MockTransport(
            lambda _request: httpx.Response(200, json=_complete_payload())
        )
    )
    provider = PromQLProvider(_settings(), client=client, telemetry=recorder)
    await provider.read_user("ada")

    encoded = repr(recorder.attributes)
    assert recorder.attributes[0]["promql.template"] == "user-active-lifetime"
    assert "ada" not in encoded
    assert "canfar_active_workload" not in encoded
    await client.aclose()


async def test_timeout_and_upstream_errors_are_bounded() -> None:
    async def timeout(request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("late", request=request)

    timeout_client = httpx.AsyncClient(transport=httpx.MockTransport(timeout))
    timeout_provider = PromQLProvider(_settings(), client=timeout_client)
    with pytest.raises(ProviderUnavailableError, match="unavailable"):
        await timeout_provider.read_user("ada")
    await timeout_client.aclose()

    error_client = httpx.AsyncClient(
        transport=httpx.MockTransport(lambda _request: httpx.Response(500))
    )
    error_provider = PromQLProvider(_settings(), client=error_client)
    with pytest.raises(ProviderUnavailableError, match="unavailable"):
        await error_provider.read_user("ada")
    await error_client.aclose()


async def test_stale_and_invalid_series_fail_before_publication() -> None:
    stale = datetime.now(UTC) - timedelta(minutes=10)
    payloads = [
        _complete_payload(timestamp=stale),
        {"status": "success", "data": {"resultType": "matrix", "result": []}},
        _payload(_series(USAGE_METRIC, "1")),
        _payload(
            _series(USAGE_METRIC, "1"),
            _series(REQUESTED_METRIC, "2"),
            _series(COMPLETE_METRIC, "2", reason="complete"),
        ),
    ]
    for payload in payloads:
        client = httpx.AsyncClient(
            transport=httpx.MockTransport(
                lambda _request, current=payload: httpx.Response(200, json=current)
            )
        )
        provider = PromQLProvider(_settings(), client=client)
        with pytest.raises(ProviderExecutionError):
            await provider.read_user("ada")
        await client.aclose()


async def test_incomplete_population_omits_resource_with_reason() -> None:
    observed = datetime.now(UTC)
    client = httpx.AsyncClient(
        transport=httpx.MockTransport(
            lambda _request: httpx.Response(
                200,
                json=_payload(
                    _series(USAGE_METRIC, "1", timestamp=observed),
                    _series(REQUESTED_METRIC, "2", timestamp=observed),
                    _series(
                        COMPLETE_METRIC,
                        "0",
                        timestamp=observed,
                        reason="sampling-gap",
                    ),
                ),
            )
        )
    )
    result = await PromQLProvider(_settings(), client=client).read_user("ada")
    assert result.resources == {}
    assert {issue.value for issue in result.incomplete["cpu"]} == {"sampling-gap"}
    await client.aclose()


async def test_accounting_cache_reuses_one_validated_snapshot() -> None:
    calls = 0

    async def load(_subject: str) -> ActiveWorkloadLifetime:
        nonlocal calls
        calls += 1
        await asyncio.sleep(0)
        return ActiveWorkloadLifetime(resources={}, incomplete={})

    def cache() -> InMemoryCoordinator[AccountingSnapshot]:
        return InMemoryCoordinator(
            policy=FRESHNESS_POLICIES["user"],
            created=lambda snapshot: snapshot.created,
        )

    service = AccountingService(
        user=load,
        community=load,
        user_cache=cache(),
        community_cache=cache(),
        user_identity=lambda username: CacheIdentity("user", username, "c", "promql"),
        community_identity=lambda community: CacheIdentity("community", community, "c", "promql"),
    )
    first = await service.get_user("ada")
    second = await service.get_user("ada")
    assert calls == 1
    assert first.value is second.value

    first.value.created = datetime.now(UTC) - timedelta(minutes=3)
    stale = await service.get_user("ada")
    assert stale.cached and stale.stale
    assert calls == 1
