"""End-to-end smoke tests for the queue-backed API through the real app factory."""

from __future__ import annotations

import asyncio
import logging
from datetime import UTC, datetime
from decimal import Decimal

import pytest
from fastapi.testclient import TestClient

import metrics.core.factory as factory_module
from metrics.cache import (
    FRESHNESS_POLICIES,
    CacheIdentity,
    CacheNotFound,
    CacheResult,
    CacheUnavailable,
)
from metrics.core.runtime import MetricsRuntime
from metrics.core.settings import CacheConfig, KueueProviderConfig, ProviderConfigs, Settings
from metrics.errors import (
    ProviderExecutionError,
    ProviderUnavailableError,
    SubjectNotFoundError,
)
from metrics.services.metrics import MetricsService
from metrics.services.models import (
    CachedSnapshot,
    CommunityObservation,
    EfficiencyObservation,
    MetricsSurface,
    PlatformObservation,
    SessionObservation,
    SessionUsageObservation,
    UserObservation,
)
from metrics.services.snapshots import SnapshotLoader
from tests.test_cache_helpers import FakeCacheCoordinator


def _settings() -> Settings:
    """Build valid in-memory application settings."""
    return Settings(
        cluster_name="cluster-a",
        redis_url="redis://localhost:6379/0",
        cache=CacheConfig(key_secret="test-cache-integrity-key-32-bytes"),
        providers=ProviderConfigs(
            kueue=KueueProviderConfig(cluster_queues=["cq-astronomy"], namespaces=["work-a"])
        ),
    )


class FakeProvider:
    """Return deterministic queue observations and record lifecycle calls."""

    name = "kueue"

    def __init__(
        self,
        *,
        platform_error: BaseException | None = None,
        user_error: BaseException | None = None,
        community_error: BaseException | None = None,
        validate_error: BaseException | None = None,
        probe_error: BaseException | None = None,
        shutdown_error: BaseException | None = None,
    ) -> None:
        self.validations = 0
        self.stopped = 0
        self.platform_error = platform_error
        self.user_error = user_error
        self.community_error = community_error
        self.validate_error = validate_error
        self.probe_error = probe_error
        self.shutdown_error = shutdown_error

    async def validate_platform(self) -> None:
        """Record one Platform source validation."""
        self.validations += 1
        if self.validate_error is not None:
            raise self.validate_error

    async def probe_local_queues(self) -> None:
        """Record the User source probe."""
        if self.probe_error is not None:
            raise self.probe_error

    async def shutdown(self) -> None:
        """Record the shutdown call."""
        self.stopped += 1
        if self.shutdown_error is not None:
            raise self.shutdown_error

    async def read_platform(self) -> PlatformObservation:
        """Return one Platform observation."""
        if self.platform_error is not None:
            raise self.platform_error
        return PlatformObservation(
            cluster="cluster-a",
            capacity={"cpu": "4"},
            allocated={"cpu": "2"},
            reserving_workloads=3,
            observed_at=datetime.now(UTC),
        )

    async def read_user(self, user: str) -> UserObservation:
        """Return one User observation."""
        if self.user_error is not None:
            raise self.user_error
        return UserObservation(
            user=user, requests={"cpu": "1"}, reserving_workloads=1, observed_at=datetime.now(UTC)
        )

    async def read_community(self, community: str) -> CommunityObservation:
        """Return one Community observation."""
        if self.community_error is not None:
            raise self.community_error
        return CommunityObservation(
            community=community,
            requests={"cpu": "2"},
            reserving_workloads=2,
            observed_at=datetime.now(UTC),
        )


class FakeSessionProvider:
    """Return deterministic session observations."""

    name = "session"

    def __init__(
        self,
        *,
        session_error: BaseException | None = None,
        startup_error: BaseException | None = None,
    ) -> None:
        self.started = 0
        self.stopped = 0
        self.session_error = session_error
        self.startup_error = startup_error

    async def startup(self) -> None:
        """Record the Job access probe."""
        self.started += 1
        if self.startup_error is not None:
            raise self.startup_error

    async def shutdown(self) -> None:
        """Record the shutdown call."""
        self.stopped += 1

    async def read_session(self, session_id: str) -> SessionObservation:
        """Return one Session observation."""
        if self.session_error is not None:
            raise self.session_error
        now = datetime.now(UTC)
        return SessionObservation(
            session=session_id,
            requests={"cpu": "1", "memory": "1Gi"},
            reserving_workloads=1,
            observed_at=now,
            start_time=now,
            window_end=now,
            has_running_pods=True,
            running_pods_by_namespace={"work-a": frozenset({"pod"})},
        )


class FakeUsageProvider:
    """Return deterministic session usage."""

    name = "kubemetrics"

    def __init__(self, *, usage_error: BaseException | None = None) -> None:
        self.usage_error = usage_error

    async def shutdown(self) -> None:
        """Satisfy the runtime lifecycle seam."""

    async def read_session_usage(self, observation: SessionObservation) -> SessionUsageObservation:
        """Return one usage observation."""
        del observation
        if self.usage_error is not None:
            raise self.usage_error
        return SessionUsageObservation(
            usage={"cpu": "0.5", "memory": "1Gi"}, observed_at=datetime.now(UTC)
        )


class FakeEfficiency:
    """Return one efficiency ratio for every surface, or fail."""

    def __init__(self, *, error: BaseException | None = None) -> None:
        self.error = error
        self.calls = 0

    async def startup(self) -> None:
        """Satisfy the optional provider lifecycle."""

    async def shutdown(self) -> None:
        """Satisfy the optional provider lifecycle."""

    async def _answer(self) -> EfficiencyObservation:
        self.calls += 1
        if self.error is not None:
            raise self.error
        return EfficiencyObservation(
            observed_at=datetime.now(UTC),
            efficiencies={"cpu": Decimal("0.5"), "memory": Decimal("0.75")},
        )

    async def read_platform(self) -> EfficiencyObservation:
        return await self._answer()

    async def read_user(self, username: str) -> EfficiencyObservation:
        return await self._answer()

    async def read_community(self, community: str) -> EfficiencyObservation:
        return await self._answer()

    async def read_session(self, session_id, *, start_time, window_end) -> EfficiencyObservation:
        return await self._answer()


def _cache(surface: str) -> FakeCacheCoordinator[CachedSnapshot]:
    """Create one deterministic test cache seam."""
    return FakeCacheCoordinator(policy=FRESHNESS_POLICIES[surface])


def _runtime(
    *,
    provider: FakeProvider | None = None,
    session_provider: FakeSessionProvider | None = None,
    usage_provider: FakeUsageProvider | None = None,
    efficiency: FakeEfficiency | None = None,
    caches: dict[MetricsSurface, object] | None = None,
) -> tuple[MetricsRuntime, FakeProvider]:
    """Build a complete injected runtime for route tests."""
    settings = _settings()
    provider = provider or FakeProvider()
    session_provider = session_provider or FakeSessionProvider()
    usage_provider = usage_provider or FakeUsageProvider()
    surfaces: dict[MetricsSurface, object] = {
        surface: _cache(surface) for surface in ("platform", "user", "community", "session")
    }
    surfaces.update(caches or {})
    loader = SnapshotLoader(
        kueue=provider,
        session=session_provider,
        usage=usage_provider,
        efficiency=efficiency,
        usage_timeout_seconds=0.2,
        efficiency_timeout_seconds=0.2,
    )
    service = MetricsService(
        platform_name="canfar",
        caches=surfaces,  # type: ignore[arg-type]
        identity=lambda kind, subject: CacheIdentity(kind, subject, "cluster-a", "kueue", "fake"),
        loader=loader,
    )
    runtime = MetricsRuntime(
        settings,
        kueue=provider,
        session=session_provider,
        usage=usage_provider,
        service=service,
        efficiency=efficiency,
    )
    return runtime, provider


def _client(runtime: MetricsRuntime, **kwargs) -> TestClient:
    return TestClient(factory_module.create_app(settings=_settings(), runtime=runtime), **kwargs)


class _FailingCache(FakeCacheCoordinator):
    """Fail every lookup with one cache outcome."""

    def __init__(self, surface: str, error: Exception) -> None:
        super().__init__(policy=FRESHNESS_POLICIES[surface])
        self.error = error

    async def get_or_fill(self, identity, fill) -> CacheResult:
        raise self.error


def _assert_status(response, *, code: int, reason: str) -> None:
    """Assert the stable sanitized Kubernetes Status envelope."""
    assert response.status_code == code
    body = response.json()
    assert body["apiVersion"] == "v1"
    assert body["kind"] == "Status"
    assert body["status"] == "Failure"
    assert body["reason"] == reason
    assert body["code"] == code
    assert "Traceback" not in response.text


def test_routes_return_queue_state_and_cache_headers() -> None:
    """All surfaces expose reserving workloads, two conditions, and cache headers."""
    runtime, provider = _runtime()
    with _client(runtime) as client:
        response = client.get("/apis/canfar.net/v1alpha1/metrics/platform/canfar")
        assert response.status_code == 200
        payload = response.json()
        assert payload["status"]["reservingWorkloads"] == 3
        assert payload["status"]["resources"] == [
            {"name": "cpu", "capacity": "4", "allocated": "2"}
        ]
        assert [c["type"] for c in payload["status"]["conditions"]] == ["Ready", "Cached"]
        assert response.headers["cache-status"] == "metrics; fwd=uri-miss; ttl=300"
        assert response.headers["age"] == "0"
        assert response.headers["cache-control"] == "no-store"
        assert "date" not in response.headers  # the ASGI server adds the only Date header

        second = client.get("/apis/canfar.net/v1alpha1/metrics/platform/canfar")
        assert second.json()["status"]["conditions"][1]["reason"] == "FreshHit"
        assert second.headers["cache-status"].startswith("metrics; hit; ttl=")

        user = client.get("/apis/canfar.net/v1alpha1/metrics/user/bob")
        assert user.status_code == 200 and user.json()["status"]["reservingWorkloads"] == 1
        community = client.get("/apis/canfar.net/v1alpha1/metrics/community/astronomy")
        assert community.status_code == 200
        assert community.json()["status"]["reservingWorkloads"] == 2

        assert client.get("/apis/canfar.net/v1alpha1/metrics/platform/other").status_code == 404
        assert client.get("/api/v1/metrics/platform").status_code == 404
    assert provider.validations == 1 and provider.stopped == 1


def test_platform_envelope_satisfies_the_skaha_consumer_contract() -> None:
    """The fields Skaha's PlatformMetricsDAO parses keep their names and invariants."""
    runtime, _provider = _runtime()
    with _client(runtime) as client:
        payload = client.get("/apis/canfar.net/v1alpha1/metrics/platform/canfar").json()

    assert payload["apiVersion"] == "canfar.net/v1alpha1" and payload["kind"] == "Metrics"
    assert payload["spec"] == {"platform": "canfar"}
    status = payload["status"]
    observed = datetime.fromisoformat(status["observedAt"].replace("Z", "+00:00"))
    assert status["resources"] and all(
        {"name", "capacity", "allocated"} <= set(resource) for resource in status["resources"]
    )
    conditions = {condition["type"]: condition for condition in status["conditions"]}
    assert set(conditions) == {"Ready", "Cached"}
    for condition in conditions.values():
        changed = datetime.fromisoformat(condition["lastTransitionTime"].replace("Z", "+00:00"))
        assert changed <= observed
    assert (conditions["Ready"]["status"], conditions["Ready"]["reason"]) == ("True", "Available")


def test_efficiency_is_rendered_and_failure_is_cached_as_partial_data() -> None:
    """Attributed efficiency is copied into the response; a failure marks PartialData once."""
    runtime, _provider = _runtime(efficiency=FakeEfficiency())
    with _client(runtime) as client:
        response = client.get("/apis/canfar.net/v1alpha1/metrics/user/bob")
    assert response.json()["status"]["resources"][0]["efficiency"] == "0.5"

    failing = FakeEfficiency(error=ProviderUnavailableError("efficiency backend unavailable"))
    runtime, _provider = _runtime(efficiency=failing)
    with _client(runtime) as client:
        first = client.get("/apis/canfar.net/v1alpha1/metrics/user/bob")
        second = client.get("/apis/canfar.net/v1alpha1/metrics/user/bob")
    for response in (first, second):
        ready = response.json()["status"]["conditions"][0]
        assert (ready["status"], ready["reason"]) == ("False", "PartialData")
    assert failing.calls == 1


def test_http_health_openapi_and_sanitized_boundaries() -> None:
    """Health, OpenAPI, validation, routing, and 404 errors keep stable contracts."""
    runtime, _provider = _runtime()
    with _client(runtime) as client:
        assert client.get("/livez").json() == {"status": "ok"}
        assert client.get("/healthz").json() == {"status": "ok"}
        assert client.get("/readyz").json() == {"status": "ready"}

        openapi = client.get("/openapi.json")
        assert openapi.status_code == 200
        assert all(
            "422" not in operation.get("responses", {})
            for path_item in openapi.json()["paths"].values()
            for operation in path_item.values()
            if isinstance(operation, dict)
        )
        assert "HTTPValidationError" not in openapi.json()["components"]["schemas"]

        for path in (
            "/apis/canfar.net/v1alpha1/metrics/platform/a%2Fb",
            "/apis/canfar.net/v1alpha1/metrics/session/-leading",
            "/apis/canfar.net/v1alpha1/metrics/community/" + "a" * 64,
        ):
            _assert_status(client.get(path), code=400, reason="BadRequest")
        _assert_status(client.get("/does-not-exist"), code=404, reason="NotFound")

        method = client.post("/apis/canfar.net/v1alpha1/metrics/platform/canfar")
        _assert_status(method, code=405, reason="MethodNotAllowed")
        assert "GET" in method.headers["allow"]


def test_routes_do_not_use_last_modified_validation() -> None:
    """Metrics responses do not expose HTTP Last-Modified or 304 semantics."""
    runtime, _provider = _runtime()
    with _client(runtime) as client:
        for path in (
            "/apis/canfar.net/v1alpha1/metrics/platform/canfar",
            "/apis/canfar.net/v1alpha1/metrics/user/Bob",
            "/apis/canfar.net/v1alpha1/metrics/community/Astronomy",
        ):
            conditional = client.get(
                path, headers={"If-Modified-Since": "Wed, 21 Oct 2015 07:28:00 GMT"}
            )
            assert conditional.status_code == 200
            assert "last-modified" not in conditional.headers

        user = client.get("/apis/canfar.net/v1alpha1/metrics/user/Bob")
        assert user.json()["spec"] == {"user": "Bob"}
        assert user.json()["metadata"]["name"].startswith("user-bob-")


@pytest.mark.parametrize(
    ("path", "provider_kwargs"),
    [
        (
            "/apis/canfar.net/v1alpha1/metrics/platform/canfar",
            {"platform_error": ProviderUnavailableError("platform offline")},
        ),
        (
            "/apis/canfar.net/v1alpha1/metrics/user/bob",
            {"user_error": ProviderExecutionError("user response invalid")},
        ),
        (
            "/apis/canfar.net/v1alpha1/metrics/community/astronomy",
            {"community_error": ProviderUnavailableError("community offline")},
        ),
    ],
    ids=["platform-unavailable", "user-execution", "community-unavailable"],
)
def test_provider_failures_are_sanitized_as_service_unavailable(
    path, provider_kwargs, caplog: pytest.LogCaptureFixture
) -> None:
    """Expected provider failures do not expose upstream details or subjects."""
    runtime, _provider = _runtime(provider=FakeProvider(**provider_kwargs))
    with (
        caplog.at_level(logging.WARNING, logger="metrics.core.factory"),
        _client(runtime) as client,
    ):
        response = client.get(path)

    _assert_status(response, code=503, reason="ServiceUnavailable")
    assert "Retry-After" not in response.headers
    assert "request failed status=503" in caplog.text
    assert "route=/apis/canfar.net/v1alpha1/metrics/" in caplog.text
    assert "bob" not in caplog.text and "astronomy" not in caplog.text


def test_cache_failure_is_sanitized_and_advertises_retry() -> None:
    """A required cache outage returns the bounded retry hint."""
    cache = _FailingCache("platform", CacheUnavailable("shared cache unavailable"))
    runtime, _provider = _runtime(caches={"platform": cache})
    with _client(runtime) as client:
        response = client.get("/apis/canfar.net/v1alpha1/metrics/platform/canfar")

    _assert_status(response, code=503, reason="ServiceUnavailable")
    assert response.headers["retry-after"] == "1"


def test_unexpected_provider_failure_is_sanitized_as_internal_error() -> None:
    """Unexpected source failures become a generic 500 response at the API boundary."""
    runtime, _provider = _runtime(
        provider=FakeProvider(platform_error=RuntimeError("secret upstream detail"))
    )
    with _client(runtime, raise_server_exceptions=False) as client:
        response = client.get("/apis/canfar.net/v1alpha1/metrics/platform/canfar")

    _assert_status(response, code=500, reason="InternalError")
    assert "secret upstream detail" not in response.text


@pytest.mark.parametrize(
    ("path", "kwargs"),
    [
        (
            "/apis/canfar.net/v1alpha1/metrics/user/missing",
            {"user_error": SubjectNotFoundError("x")},
        ),
        (
            "/apis/canfar.net/v1alpha1/metrics/community/missing",
            {"community_error": SubjectNotFoundError("x")},
        ),
    ],
    ids=["user-not-found", "community-not-found"],
)
def test_missing_workload_subjects_are_sanitized_as_not_found(path, kwargs) -> None:
    """Valid workload subjects without source data map to a stable 404."""
    runtime, _provider = _runtime(provider=FakeProvider(**kwargs))
    with _client(runtime) as client:
        _assert_status(client.get(path), code=404, reason="NotFound")


@pytest.mark.parametrize(
    ("surface", "path"),
    [
        ("user", "/apis/canfar.net/v1alpha1/metrics/user/bob"),
        ("community", "/apis/canfar.net/v1alpha1/metrics/community/astronomy"),
        ("session", "/apis/canfar.net/v1alpha1/metrics/session/abc"),
    ],
)
def test_cached_subject_miss_maps_to_404(surface: str, path: str) -> None:
    """A shared not-found result remains a sanitized subject 404, never a 503."""
    runtime, _provider = _runtime(caches={surface: _FailingCache(surface, CacheNotFound())})
    with _client(runtime) as client:
        _assert_status(client.get(path), code=404, reason="NotFound")


def test_readiness_waits_for_the_platform_source_then_latches() -> None:
    """A pod is unready until Redis and ClusterQueues pass once, then stays ready."""
    provider = FakeProvider(validate_error=ProviderUnavailableError("clusterqueues forbidden"))
    runtime, _provider = _runtime(provider=provider)
    with _client(runtime) as client:
        assert client.get("/livez").status_code == 200
        assert client.get("/readyz").status_code == 503
        provider.validate_error = None
        assert client.get("/readyz").status_code == 200
        provider.validate_error = ProviderUnavailableError("later outage")
        provider.platform_error = ProviderUnavailableError("later outage")
        _assert_status(
            client.get("/apis/canfar.net/v1alpha1/metrics/platform/canfar"),
            code=503,
            reason="ServiceUnavailable",
        )
        assert client.get("/readyz").status_code == 200
    assert provider.stopped == 1


def test_user_and_session_source_probes_never_gate_readiness(
    caplog: pytest.LogCaptureFixture,
) -> None:
    """LocalQueue and Job probe failures are logged; Platform alone gates readiness."""
    runtime, _provider = _runtime(
        provider=FakeProvider(probe_error=ProviderUnavailableError("localqueues forbidden")),
        session_provider=FakeSessionProvider(startup_error=ProviderUnavailableError("jobs")),
    )
    with caplog.at_level(logging.WARNING), _client(runtime) as client:
        assert client.get("/readyz").status_code == 200
    assert "User LocalQueue access could not be verified" in caplog.text
    assert "Session Job access could not be verified" in caplog.text


@pytest.mark.anyio
@pytest.mark.parametrize("cleanup_fails", [False, True], ids=["clean", "runtime-cleanup-error"])
async def test_lifespan_finishes_runtime_cleanup_before_propagating_cancellation(
    monkeypatch: pytest.MonkeyPatch,
    cleanup_fails: bool,
) -> None:
    """Runtime cleanup completes, in order, without masking caller cancellation."""
    events: list[str] = []
    shutdown_started = asyncio.Event()
    release_shutdown = asyncio.Event()

    class CancellableRuntime:
        async def start(self) -> None:
            events.append("runtime-start")

        async def shutdown(self) -> None:
            events.append("runtime-shutdown-start")
            shutdown_started.set()
            await release_shutdown.wait()
            events.append("runtime-shutdown-complete")
            if cleanup_fails:
                raise RuntimeError("runtime cleanup failed")

    class RecordingTelemetry:
        async def shutdown(self) -> None:
            events.append("telemetry-shutdown")

    telemetry = RecordingTelemetry()
    monkeypatch.setattr(factory_module, "setup_telemetry", lambda _settings: telemetry)
    app = factory_module.create_app(settings=_settings(), runtime=CancellableRuntime())
    lifespan = app.router.lifespan_context(app)
    await lifespan.__aenter__()

    exit_task = asyncio.create_task(lifespan.__aexit__(None, None, None))
    await shutdown_started.wait()
    exit_task.cancel("caller cancellation")
    release_shutdown.set()

    with pytest.raises(asyncio.CancelledError, match="caller cancellation"):
        await asyncio.wait_for(exit_task, timeout=1.0)

    assert events == [
        "runtime-start",
        "runtime-shutdown-start",
        "runtime-shutdown-complete",
        "telemetry-shutdown",
    ]
