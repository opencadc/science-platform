"""Focused tests for the Session Metrics surface."""

from __future__ import annotations

import asyncio
import contextlib
from datetime import UTC, datetime
from decimal import Decimal
from typing import Any

import httpx
import kr8s
import pytest
from fastapi.testclient import TestClient

import metrics.core.factory as factory_module
from metrics.cache import CacheIdentity, CacheNotFound, FRESHNESS_POLICIES
from metrics.core.runtime import MetricsRuntime
from metrics.errors import AppError, ProviderUnavailableError, SubjectNotFoundError
from metrics.providers.kubemetrics import KubeMetricsProvider
from metrics.providers.session import SessionProvider
from metrics.services.metrics import MetricsService
from metrics.services.models import CachedSnapshot, EfficiencyObservation, MetricsSubject, SessionObservation, SessionUsageObservation
from tests.test_app_smoke import FakeProvider, FakeSessionProvider, FakeUsageProvider, _cache, _runtime, _settings
from tests.test_cache_helpers import FakeCacheCoordinator


pytestmark = pytest.mark.anyio


def _job(
    name: str,
    namespace: str,
    session_id: str,
    *,
    cpu: str = "500m",
    memory: str = "512Mi",
    gpu: str | None = None,
    start_time: str | None = "2026-01-01T12:00:00Z",
    completion_time: str | None = None,
    init_cpu: str | None = None,
) -> dict[str, Any]:
    """Build one labelled Job fixture."""
    requests: dict[str, str] = {"cpu": cpu, "memory": memory}
    if gpu is not None:
        requests["nvidia.com/gpu"] = gpu
    containers = [
        {
            "name": "busy",
            "resources": {"requests": dict(requests)},
        }
    ]
    init_containers: list[dict[str, Any]] = []
    if init_cpu is not None:
        init_containers.append(
            {
                "name": "initialize",
                "resources": {"requests": {"cpu": init_cpu, "memory": "128Mi"}},
            }
        )
    init_containers.append({"name": "pause", "resources": {"requests": {"cpu": "10m"}}})
    status: dict[str, Any] = {}
    if start_time is not None:
        status["startTime"] = start_time
    if completion_time is not None:
        status["completionTime"] = completion_time
    return {
        "metadata": {
            "name": name,
            "namespace": namespace,
            "labels": {"canfar.net/id": session_id},
        },
        "spec": {
            "template": {
                "spec": {
                    "initContainers": init_containers,
                    "containers": containers,
                }
            }
        },
        "status": status,
    }


def _pod(name: str, namespace: str, session_id: str, *, phase: str = "Running") -> dict[str, Any]:
    """Build one labelled Pod fixture."""
    return {
        "metadata": {
            "name": name,
            "namespace": namespace,
            "labels": {"canfar.net/id": session_id},
        },
        "status": {"phase": phase},
    }


def _pod_metrics(
    name: str,
    *,
    cpu: str = "250m",
    memory: str = "256Mi",
    timestamp: str = "2026-01-01T12:30:00Z",
) -> dict[str, Any]:
    """Build one PodMetrics item."""
    return {
        "metadata": {"name": name},
        "timestamp": timestamp,
        "containers": [
            {"name": "busy", "usage": {"cpu": cpu, "memory": memory}},
            {"name": "pause", "usage": {"cpu": "0", "memory": "0"}},
        ],
    }


class FakeKubernetesApi:
    """Return Job, Pod, and PodMetrics LIST responses."""

    def __init__(
        self,
        *,
        jobs: dict[str, list[dict[str, Any]]] | None = None,
        pods: dict[str, list[dict[str, Any]]] | None = None,
        pod_metrics: dict[str, list[dict[str, Any]]] | None = None,
    ) -> None:
        self.jobs = jobs or {}
        self.pods = pods or {}
        self.pod_metrics = pod_metrics or {}

    @contextlib.asynccontextmanager
    async def call_api(
        self,
        *,
        method: str,
        version: str,
        url: str,
        namespace: str | None = None,
        params: dict[str, str] | None = None,
    ):
        """Implement the small kr8s call_api surface used by Session providers."""
        del method, params
        if version == "batch/v1" and url == "jobs" and namespace is not None:
            payload = {"items": self.jobs.get(namespace, []), "metadata": {}}
            yield httpx.Response(200, json=payload)
            return
        if version == "v1" and url == "pods" and namespace is not None:
            payload = {"items": self.pods.get(namespace, []), "metadata": {}}
            yield httpx.Response(200, json=payload)
            return
        if version == "metrics.k8s.io/v1beta1" and url == "pods" and namespace is not None:
            payload = {"items": self.pod_metrics.get(namespace, [])}
            yield httpx.Response(200, json=payload)
            return
        raise kr8s.ServerError("unexpected request", response=httpx.Response(404))


class _TerminalNotFoundCache:
    """Expose the typed Redis terminal miss at the service boundary."""

    backend_name = "redis"
    policy = FRESHNESS_POLICIES["session"]
    available = True

    async def get_or_fill(self, _identity, _fill):
        """Raise the authenticated cache terminal outcome."""
        raise CacheNotFound()

    async def shutdown(self) -> None:
        """Satisfy the runtime cache lifecycle seam."""


def _session_service(
    *,
    session_provider: SessionProvider,
    usage_loader,
    session_efficiency=None,
    session_cache: FakeCacheCoordinator[CachedSnapshot] | None = None,
) -> MetricsService:
    """Build one Metrics service with Session wiring."""
    session_cache = session_cache or _cache("session")
    return MetricsService(
        platform=FakeProvider().read_platform,
        cache=_cache("platform"),
        identity=lambda: CacheIdentity("platform", "canfar", "cluster-a", "kueue", "fake"),
        user=FakeProvider().read_user,
        user_cache=_cache("user"),
        user_identity=lambda user: CacheIdentity("user", user, "cluster-a", "kueue", "fake"),
        community=FakeProvider().read_community,
        community_cache=_cache("community"),
        community_identity=lambda community: CacheIdentity(
            "community", community, "cluster-a", "kueue", "fake"
        ),
        session=session_provider.read_session,
        session_cache=session_cache,
        session_identity=lambda session_id: CacheIdentity(
            "session", session_id, "cluster-a", "session", "fake"
        ),
        session_usage=usage_loader,
        session_efficiency=session_efficiency,
        efficiency_timeout_seconds=0.2,
    )


async def test_session_provider_aggregates_jobs_and_window_times() -> None:
    """Session requests sum non-pause containers and derive timing inputs."""
    api = FakeKubernetesApi(
        jobs={
            "work-a": [
                _job("desktop", "work-a", "sess-1", cpu="1", init_cpu="100m"),
                _job("app", "work-a", "sess-1", cpu="500m"),
            ]
        },
        pods={"work-a": [_pod("desktop-pod", "work-a", "sess-1", phase="Running")]},
    )
    provider = SessionProvider(_settings(), api=api)
    observation = await provider.read_session("sess-1")

    assert observation.session == "sess-1"
    assert observation.reserving_workloads == 2
    assert observation.requests["cpu"] == "1.6"
    assert observation.requests["memory"] == "1.125Gi"
    assert observation.start_time == datetime(2026, 1, 1, 12, 0, tzinfo=UTC)
    assert observation.has_running_pods is True


async def test_session_provider_missing_job_is_not_found() -> None:
    """An unknown session id maps to a subject miss."""
    provider = SessionProvider(_settings(), api=FakeKubernetesApi())
    with pytest.raises(SubjectNotFoundError):
        await provider.read_session("missing")


async def test_kubemetrics_sums_running_pod_usage() -> None:
    """Usage excludes pause containers, non-Running pods, and formats public units."""
    api = FakeKubernetesApi(
        pods={
            "work-a": [
                _pod("pod-a", "work-a", "sess-1", phase="Running"),
                _pod("pod-b", "work-a", "sess-1", phase="Succeeded"),
            ]
        },
        pod_metrics={
            "work-a": [
                _pod_metrics("pod-a", cpu="500m", memory="1Gi"),
                _pod_metrics("pod-b", cpu="250m", memory="512Mi"),
            ]
        },
    )
    provider = KubeMetricsProvider(_settings(), api=api)
    observation = await provider.read_session_usage("sess-1")

    assert observation.usage == {"cpu": "0.5", "memory": "1Gi"}
    assert observation.observed_at == datetime(2026, 1, 1, 12, 30, tzinfo=UTC)


async def test_session_service_returns_usage_and_efficiency() -> None:
    """A complete Session fill exposes requests, usage, and efficiency."""
    now = datetime(2026, 1, 1, 12, 30, tzinfo=UTC)
    api = FakeKubernetesApi(
        jobs={"work-a": [_job("desktop", "work-a", "sess-1", start_time="2026-01-01T12:00:00Z")]},
        pods={"work-a": [_pod("desktop-pod", "work-a", "sess-1")]},
        pod_metrics={"work-a": [_pod_metrics("desktop-pod")]},
    )
    session_provider = SessionProvider(_settings(), api=api)
    usage_provider = KubeMetricsProvider(_settings(), api=api)

    async def efficiency(observation: SessionObservation) -> EfficiencyObservation:
        assert observation.session == "sess-1"
        return EfficiencyObservation(now, {"cpu": Decimal("0.4")})

    result = await _session_service(
        session_provider=session_provider,
        usage_loader=usage_provider.read_session_usage,
        session_efficiency=efficiency,
    ).get(MetricsSubject("session", "sess-1"))

    assert result.ready
    assert isinstance(result.observation, SessionObservation)
    assert result.usage == {"cpu": "0.25", "memory": "0.25Gi"}
    assert result.efficiency is not None
    assert result.efficiency.efficiencies["cpu"] == Decimal("0.4")


async def test_session_service_marks_partial_when_running_usage_fails() -> None:
    """A kube-metrics failure with Running pods keeps Job data but marks PartialData."""

    async def failing_usage(_session_id: str) -> dict[str, str]:
        raise ProviderUnavailableError("metrics.k8s.io unavailable")

    api = FakeKubernetesApi(
        jobs={"work-a": [_job("desktop", "work-a", "sess-1")]},
        pods={"work-a": [_pod("desktop-pod", "work-a", "sess-1")]},
    )
    result = await _session_service(
        session_provider=SessionProvider(_settings(), api=api),
        usage_loader=failing_usage,
    ).get(MetricsSubject("session", "sess-1"))

    assert result.ready is False
    assert result.ready_reason == "PartialData"
    assert result.usage is None


def test_session_route_returns_envelope_and_cache_headers() -> None:
    """The HTTP route exposes spec.session, usage, and cache metadata."""
    settings = _settings()
    session_provider = FakeSessionProvider()
    usage_provider = FakeUsageProvider()
    session_cache = _cache("session")
    service = MetricsService(
        platform=FakeProvider().read_platform,
        cache=_cache("platform"),
        identity=lambda: CacheIdentity("platform", "canfar", "cluster-a", "kueue", "fake"),
        user=FakeProvider().read_user,
        user_cache=_cache("user"),
        user_identity=lambda user: CacheIdentity("user", user, "cluster-a", "kueue", "fake"),
        community=FakeProvider().read_community,
        community_cache=_cache("community"),
        community_identity=lambda community: CacheIdentity(
            "community", community, "cluster-a", "kueue", "fake"
        ),
        session=session_provider.read_session,
        session_cache=session_cache,
        session_identity=lambda session_id: CacheIdentity(
            "session", session_id, "cluster-a", "session", "fake"
        ),
        session_usage=usage_provider.read_session_usage,
    )
    runtime = MetricsRuntime(
        settings,
        provider=FakeProvider(),
        session_provider=session_provider,
        usage_provider=usage_provider,
        metrics_service=service,
        cache=_cache("platform"),
        user_cache=_cache("user"),
        community_cache=_cache("community"),
        session_cache=session_cache,
    )
    with TestClient(factory_module.create_app(settings=settings, runtime=runtime)) as client:
        response = client.get("/apis/canfar.net/v1alpha1/metrics/session/sess-1")

    assert response.status_code == 200
    payload = response.json()
    assert payload["spec"] == {"session": "sess-1"}
    assert payload["status"]["reservingWorkloads"] == 1
    assert payload["status"]["resources"] == [
        {"name": "cpu", "requests": "1", "usage": "0.5"},
        {"name": "memory", "requests": "1Gi", "usage": "1Gi"},
    ]
    assert response.headers["cache-status"].startswith("metrics; fwd=uri-miss")


def test_session_not_found_is_sanitized() -> None:
    """Missing Jobs map to a stable 404 response."""
    runtime, _provider = _runtime(
        session_provider=FakeSessionProvider(
            session_error=SubjectNotFoundError("Session has no matching Job")
        )
    )
    with TestClient(factory_module.create_app(settings=_settings(), runtime=runtime)) as client:
        response = client.get("/apis/canfar.net/v1alpha1/metrics/session/missing")

    assert response.status_code == 404
    assert response.json()["reason"] == "NotFound"


def test_session_bad_id_returns_bad_request() -> None:
    """Malformed session ids map to a stable 400 response."""
    runtime, _provider = _runtime()
    with TestClient(factory_module.create_app(settings=_settings(), runtime=runtime)) as client:
        response = client.get("/apis/canfar.net/v1alpha1/metrics/session/a%2Fb")

    assert response.status_code == 400
    assert response.json()["reason"] == "BadRequest"


async def test_session_service_omits_efficiency_without_start_time() -> None:
    """Pending sessions without Job startTime skip efficiency loading."""
    api = FakeKubernetesApi(
        jobs={
            "work-a": [
                _job(
                    "desktop",
                    "work-a",
                    "sess-1",
                    start_time=None,
                    completion_time=None,
                )
            ]
        },
        pods={"work-a": [_pod("desktop-pod", "work-a", "sess-1", phase="Pending")]},
    )
    efficiency_called = False

    async def efficiency(_observation: SessionObservation) -> EfficiencyObservation:
        nonlocal efficiency_called
        efficiency_called = True
        return EfficiencyObservation(datetime.now(UTC), {"cpu": Decimal("0.5")})

    result = await _session_service(
        session_provider=SessionProvider(_settings(), api=api),
        usage_loader=KubeMetricsProvider(_settings(), api=api).read_session_usage,
        session_efficiency=efficiency,
    ).get(MetricsSubject("session", "sess-1"))

    assert result.ready
    assert result.efficiency is None
    assert efficiency_called is False


async def test_session_service_marks_partial_when_efficiency_fails() -> None:
    """A PromQL failure keeps Job data but marks PartialData."""

    async def failing_efficiency(_observation: SessionObservation) -> EfficiencyObservation:
        raise ProviderUnavailableError("PromQL unavailable")

    api = FakeKubernetesApi(
        jobs={"work-a": [_job("desktop", "work-a", "sess-1", start_time="2026-01-01T12:00:00Z")]},
        pods={"work-a": [_pod("desktop-pod", "work-a", "sess-1")]},
    )
    result = await _session_service(
        session_provider=SessionProvider(_settings(), api=api),
        usage_loader=KubeMetricsProvider(_settings(), api=api).read_session_usage,
        session_efficiency=failing_efficiency,
    ).get(MetricsSubject("session", "sess-1"))

    assert result.ready is False
    assert result.ready_reason == "PartialData"
    assert result.efficiency is None


def test_session_provider_failure_is_service_unavailable() -> None:
    """Primary Job source failures fail closed when no cache exists."""
    runtime, _provider = _runtime(
        session_provider=FakeSessionProvider(
            session_error=ProviderUnavailableError("Job access failed")
        )
    )
    with TestClient(factory_module.create_app(settings=_settings(), runtime=runtime)) as client:
        response = client.get("/apis/canfar.net/v1alpha1/metrics/session/sess-1")

    assert response.status_code == 503
    assert response.json()["reason"] == "ServiceUnavailable"


def test_session_schema_allows_usage_without_efficiency() -> None:
    """Session workload rows may carry usage without efficiency."""
    from metrics.schemas.metrics import Metrics

    observed = datetime(2026, 1, 1, tzinfo=UTC)
    report = Metrics.model_validate(
        {
            "apiVersion": "canfar.net/v1alpha1",
            "kind": "Metrics",
            "metadata": {"name": "session-test"},
            "spec": {"session": "sess-1"},
            "status": {
                "observedAt": observed,
                "reservingWorkloads": 1,
                "resources": [
                    {"name": "cpu", "requests": "1", "usage": "0.5"},
                    {"name": "memory", "requests": "1Gi", "usage": "0.25Gi"},
                    {"name": "nvidia.com/gpu", "requests": "1"},
                ],
                "conditions": [
                    {
                        "type": "Ready",
                        "status": "True",
                        "reason": "Available",
                        "lastTransitionTime": observed,
                    },
                    {
                        "type": "Cached",
                        "status": "False",
                        "reason": "Refreshed",
                        "lastTransitionTime": observed,
                    },
                ],
            },
        }
    )
    payload = report.model_dump(mode="json", by_alias=True, exclude_none=True)
    assert payload["status"]["resources"][2] == {"name": "nvidia.com/gpu", "requests": "1"}


async def test_session_provider_soft_fails_when_pod_list_unavailable() -> None:
    """Pod list failures keep Job data but mark pod state unreachable."""

    class PodFailingApi(FakeKubernetesApi):
        @contextlib.asynccontextmanager
        async def call_api(
            self,
            *,
            method: str,
            version: str,
            url: str,
            namespace: str | None = None,
            params: dict[str, str] | None = None,
        ):
            if version == "v1" and url == "pods":
                raise kr8s.ServerError("pod list failed", response=httpx.Response(503))
            async with super().call_api(
                method=method,
                version=version,
                url=url,
                namespace=namespace,
                params=params,
            ) as response:
                yield response

    api = PodFailingApi(
        jobs={"work-a": [_job("desktop", "work-a", "sess-1")]},
    )
    observation = await SessionProvider(_settings(), api=api).read_session("sess-1")

    assert observation.reserving_workloads == 1
    assert observation.pods_reachable is False
    assert observation.has_running_pods is False


async def test_session_service_marks_partial_when_pods_unreachable() -> None:
    """Unreachable pod state marks PartialData while Job data remains available."""
    now = datetime(2026, 1, 1, 12, 30, tzinfo=UTC)
    observation = SessionObservation(
        session="sess-1",
        requests={"cpu": "1"},
        reserving_workloads=1,
        observed_at=now,
        start_time=now,
        window_end=now,
        has_running_pods=False,
        pods_reachable=False,
    )

    class StaticSessionProvider:
        async def read_session(self, session_id: str) -> SessionObservation:
            assert session_id == "sess-1"
            return observation

    result = await _session_service(
        session_provider=StaticSessionProvider(),  # type: ignore[arg-type]
        usage_loader=KubeMetricsProvider(_settings(), api=FakeKubernetesApi()).read_session_usage,
    ).get(MetricsSubject("session", "sess-1"))

    assert result.ready is False
    assert result.ready_reason == "PartialData"


async def test_session_service_marks_partial_when_usage_times_out() -> None:
    """A bounded usage timeout counts as a usage failure for Running pods."""

    async def slow_usage(_session_id: str) -> SessionUsageObservation:
        await asyncio.sleep(1)
        return SessionUsageObservation(usage={"cpu": "1"}, observed_at=datetime.now(UTC))

    api = FakeKubernetesApi(
        jobs={"work-a": [_job("desktop", "work-a", "sess-1")]},
        pods={"work-a": [_pod("desktop-pod", "work-a", "sess-1")]},
    )
    result = await _session_service(
        session_provider=SessionProvider(_settings(), api=api),
        usage_loader=slow_usage,
    ).get(MetricsSubject("session", "sess-1"))

    assert result.ready is False
    assert result.ready_reason == "PartialData"
    assert result.usage is None


async def test_session_cache_terminal_miss_maps_to_not_found() -> None:
    """A Redis terminal miss remains a sanitized subject 404."""
    with pytest.raises(AppError) as exc:
        await _session_service(
            session_provider=SessionProvider(_settings(), api=FakeKubernetesApi()),
            usage_loader=KubeMetricsProvider(_settings(), api=FakeKubernetesApi()).read_session_usage,
            session_cache=_TerminalNotFoundCache(),
        ).get(MetricsSubject("session", "missing"))
    assert exc.value.status_code == 404
