"""End-to-end smoke tests for the simplified queue-backed API."""

from __future__ import annotations

import asyncio
from datetime import UTC, datetime
from decimal import Decimal

import pytest
from fastapi.testclient import TestClient

import metrics.core.factory as factory_module
from metrics.cache import (
    CacheIdentity,
    CacheNotFound,
    CacheUnavailable,
    FRESHNESS_POLICIES,
)
from metrics.core.runtime import MetricsRuntime
from metrics.core.settings import CacheConfig, KueueProviderConfig, ProviderConfigs, Settings
from metrics.errors import (
    ProviderExecutionError,
    ProviderUnavailableError,
    RuntimeStartupError,
    SubjectNotFoundError,
)
from metrics.services.metrics import MetricsService
from metrics.services.models import (
    CachedSnapshot,
    CommunityObservation,
    EfficiencyObservation,
    PlatformObservation,
    UserObservation,
)
from tests.test_cache_helpers import FakeCacheCoordinator


def _settings() -> Settings:
    """Build valid in-memory application settings."""
    return Settings(
        cluster_name="cluster-a",
        redis_url="redis://localhost:6379/0",
        cache=CacheConfig(key_secret="x" * 32),
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
        startup_error: BaseException | None = None,
        shutdown_error: BaseException | None = None,
    ) -> None:
        self.started = 0
        self.stopped = 0
        self.platform_error = platform_error
        self.user_error = user_error
        self.community_error = community_error
        self.startup_error = startup_error
        self.shutdown_error = shutdown_error

    async def startup(self) -> None:
        """Record the startup validation call."""
        self.started += 1
        if self.startup_error is not None:
            raise self.startup_error

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
            user=user,
            requests={"cpu": "1"},
            reserving_workloads=1,
            observed_at=datetime.now(UTC),
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

    def cache_fingerprint(self) -> str:
        """Return a stable fake provider fingerprint."""
        return "fake"


def _cache(surface: str) -> FakeCacheCoordinator[CachedSnapshot]:
    """Create one deterministic test cache seam."""
    return FakeCacheCoordinator(
        policy=FRESHNESS_POLICIES[surface],
        created=lambda snapshot: snapshot.created,
    )


def _runtime(
    *,
    user_efficiency=None,
    provider: FakeProvider | None = None,
    platform_cache=None,
    user_cache=None,
    community_cache=None,
) -> tuple[MetricsRuntime, FakeProvider]:
    """Build a complete injected runtime for route tests."""
    settings = _settings()
    provider = provider or FakeProvider()
    platform_cache = platform_cache or _cache("platform")
    user_cache = user_cache or _cache("user")
    community_cache = community_cache or _cache("community")
    service = MetricsService(
        platform=provider.read_platform,
        cache=platform_cache,
        identity=lambda: CacheIdentity("platform", "canfar", "cluster-a", "kueue", "fake"),
        platform_name="canfar",
        user=provider.read_user,
        user_cache=user_cache,
        user_identity=lambda user: CacheIdentity("user", user, "cluster-a", "kueue", "fake"),
        user_efficiency=user_efficiency,
        community=provider.read_community,
        community_cache=community_cache,
        community_identity=lambda community: CacheIdentity(
            "community", community, "cluster-a", "kueue", "fake"
        ),
    )
    return (
        MetricsRuntime(
            settings,
            provider=provider,
            metrics_service=service,
            cache=platform_cache,
            user_cache=user_cache,
            community_cache=community_cache,
        ),
        provider,
    )


def test_routes_return_queue_state_and_cache_headers() -> None:
    """All three surfaces expose reserving workloads and only two conditions."""
    runtime, provider = _runtime()
    with TestClient(factory_module.create_app(settings=_settings(), runtime=runtime)) as client:
        response = client.get("/apis/canfar.net/v1alpha1/metrics/platform/canfar")
        assert response.status_code == 200
        payload = response.json()
        assert payload["status"]["reservingWorkloads"] == 3
        assert payload["status"]["resources"] == [
            {"name": "cpu", "capacity": "4", "allocated": "2"}
        ]
        assert {condition["type"] for condition in payload["status"]["conditions"]} == {
            "Ready",
            "Cached",
        }
        assert response.headers["cache-status"].startswith("metrics; fwd=uri-miss")

        second = client.get("/apis/canfar.net/v1alpha1/metrics/platform/canfar")
        assert second.status_code == 200
        assert second.json()["status"]["conditions"][1]["reason"] == "FreshHit"
        assert runtime._readiness._surfaces["platform"].source_reachable  # noqa: SLF001

        user = client.get("/apis/canfar.net/v1alpha1/metrics/user/bob")
        assert user.status_code == 200
        assert user.json()["status"]["reservingWorkloads"] == 1

        community = client.get("/apis/canfar.net/v1alpha1/metrics/community/astronomy")
        assert community.status_code == 200
        assert community.json()["status"]["reservingWorkloads"] == 2

        assert client.get("/apis/canfar.net/v1alpha1/metrics/platform/other").status_code == 404
        assert client.get("/api/v1/metrics/platform").status_code == 404
    assert provider.started == 1
    assert provider.stopped == 1


def test_enabled_efficiency_failure_is_cached_as_partial_data() -> None:
    """Optional adapter failure keeps queue data but marks the report partial."""
    calls = 0

    async def unavailable(_user: str) -> EfficiencyObservation:
        nonlocal calls
        calls += 1
        raise ProviderUnavailableError("efficiency backend unavailable")

    runtime, _provider = _runtime(user_efficiency=unavailable)
    with TestClient(factory_module.create_app(settings=_settings(), runtime=runtime)) as client:
        first = client.get("/apis/canfar.net/v1alpha1/metrics/user/bob")
        second = client.get("/apis/canfar.net/v1alpha1/metrics/user/bob")

    assert first.status_code == 200
    assert first.json()["status"]["conditions"][0]["reason"] == "PartialData"
    assert second.json()["status"]["conditions"][0]["reason"] == "PartialData"
    assert calls == 1


def test_efficiency_observation_is_rendered_without_query_logic() -> None:
    """Already-attributed CPU data is copied into the public response."""
    settings = _settings()
    provider = FakeProvider()
    platform_cache = _cache("platform")
    user_cache = _cache("user")
    community_cache = _cache("community")
    service = MetricsService(
        platform=provider.read_platform,
        cache=platform_cache,
        identity=lambda: CacheIdentity("platform", "canfar", "cluster-a", "kueue", "fake"),
        user=provider.read_user,
        user_cache=user_cache,
        user_identity=lambda user: CacheIdentity("user", user, "cluster-a", "kueue", "fake"),
        user_efficiency=lambda _user: _efficiency(),
        community=provider.read_community,
        community_cache=community_cache,
        community_identity=lambda community: CacheIdentity(
            "community", community, "cluster-a", "kueue", "fake"
        ),
    )
    runtime = MetricsRuntime(
        settings,
        provider=provider,
        metrics_service=service,
        cache=platform_cache,
        user_cache=user_cache,
        community_cache=community_cache,
    )
    with TestClient(factory_module.create_app(settings=settings, runtime=runtime)) as client:
        response = client.get("/apis/canfar.net/v1alpha1/metrics/user/bob")

    assert response.status_code == 200
    assert response.json()["status"]["resources"][0]["efficiency"] == "0.5"


async def _efficiency() -> EfficiencyObservation:
    """Return a deterministic attributed efficiency observation."""
    return EfficiencyObservation(
        observed_at=datetime.now(UTC),
        efficiencies={"cpu": Decimal("0.5"), "memory": Decimal("0.75")},
    )


class _UnavailableCache:
    """Fail one cache surface as a required shared-cache outage would."""

    backend_name = "redis"

    def __init__(self, surface: str) -> None:
        self.policy = FRESHNESS_POLICIES[surface]
        self.available = False

    async def get_or_fill(self, _identity, _fill):
        """Raise the service-level cache outage."""
        raise CacheUnavailable("shared cache unavailable")

    async def shutdown(self) -> None:
        """Satisfy the runtime cache lifecycle seam."""


class _TerminalNotFoundCache:
    """Expose the typed Redis terminal miss at the service boundary."""

    backend_name = "redis"

    def __init__(self, surface: str) -> None:
        self.policy = FRESHNESS_POLICIES[surface]
        self.available = True

    async def get_or_fill(self, _identity, _fill):
        """Raise the authenticated cache terminal outcome."""
        raise CacheNotFound()

    async def shutdown(self) -> None:
        """Satisfy the runtime cache lifecycle seam."""


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


def test_http_health_openapi_and_sanitized_boundaries() -> None:
    """Health, OpenAPI, validation, routing, and 404 errors keep stable contracts."""
    runtime, _provider = _runtime()
    app = factory_module.create_app(settings=_settings(), runtime=runtime)

    with TestClient(app) as client:
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

        _assert_status(
            client.get("/apis/canfar.net/v1alpha1/metrics/platform/a%2Fb"),
            code=400,
            reason="BadRequest",
        )
        _assert_status(client.get("/does-not-exist"), code=404, reason="NotFound")

        method = client.post("/apis/canfar.net/v1alpha1/metrics/platform/canfar")
        _assert_status(method, code=405, reason="Invalid")
        assert "GET" in method.headers["allow"]


def test_routes_do_not_use_last_modified_validation() -> None:
    """Metrics responses do not expose HTTP Last-Modified or 304 semantics."""
    runtime, _provider = _runtime()
    app = factory_module.create_app(settings=_settings(), runtime=runtime)

    with TestClient(app) as client:
        paths = (
            "/apis/canfar.net/v1alpha1/metrics/platform/canfar",
            "/apis/canfar.net/v1alpha1/metrics/user/Bob",
            "/apis/canfar.net/v1alpha1/metrics/community/Astronomy",
        )
        for path in paths:
            first = client.get(path)
            assert first.status_code == 200
            assert "last-modified" not in first.headers
            conditional = client.get(
                path,
                headers={"If-Modified-Since": "Wed, 21 Oct 2015 07:28:00 GMT"},
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
def test_provider_failures_are_sanitized_as_service_unavailable(path, provider_kwargs) -> None:
    """Expected provider failures do not expose upstream details."""
    runtime, _provider = _runtime(provider=FakeProvider(**provider_kwargs))

    with TestClient(factory_module.create_app(settings=_settings(), runtime=runtime)) as client:
        response = client.get(path)

    _assert_status(response, code=503, reason="ServiceUnavailable")
    assert "Retry-After" not in response.headers


def test_cache_failure_is_sanitized_and_advertises_retry() -> None:
    """A required cache outage returns the bounded retry hint."""
    runtime, _provider = _runtime(platform_cache=_UnavailableCache("platform"))

    with TestClient(factory_module.create_app(settings=_settings(), runtime=runtime)) as client:
        response = client.get("/apis/canfar.net/v1alpha1/metrics/platform/canfar")

    _assert_status(response, code=503, reason="ServiceUnavailable")
    assert response.headers["retry-after"] == "1"


@pytest.mark.parametrize(
    ("surface", "path"),
    [
        ("user", "/apis/canfar.net/v1alpha1/metrics/user/bob"),
        ("community", "/apis/canfar.net/v1alpha1/metrics/community/astronomy"),
    ],
)
def test_workload_cache_failure_flaps_public_readiness_until_recovery(
    surface: str,
    path: str,
) -> None:
    """User and Community cache outages make readiness fail until recovered."""
    cache = _UnavailableCache(surface)
    cache.available = True
    runtime, _provider = _runtime(**{f"{surface}_cache": cache})

    with TestClient(factory_module.create_app(settings=_settings(), runtime=runtime)) as client:
        assert client.get("/readyz").status_code == 200

        cache.available = False
        response = client.get(path)
        _assert_status(response, code=503, reason="ServiceUnavailable")
        assert client.get("/readyz").status_code == 503

        cache.available = True
        assert client.get("/readyz").status_code == 200


def test_unexpected_provider_failure_is_sanitized_as_internal_error() -> None:
    """Unexpected source failures become a generic 500 response at the API boundary."""
    runtime, _provider = _runtime(
        provider=FakeProvider(platform_error=RuntimeError("secret upstream detail"))
    )

    with TestClient(
        factory_module.create_app(settings=_settings(), runtime=runtime),
        raise_server_exceptions=False,
    ) as client:
        response = client.get("/apis/canfar.net/v1alpha1/metrics/platform/canfar")

    _assert_status(response, code=500, reason="InternalError")
    assert "secret upstream detail" not in response.text


@pytest.mark.parametrize(
    ("path", "provider_kwargs"),
    [
        (
            "/apis/canfar.net/v1alpha1/metrics/user/missing",
            {"user_error": SubjectNotFoundError("no LocalQueue")},
        ),
        (
            "/apis/canfar.net/v1alpha1/metrics/community/missing",
            {"community_error": SubjectNotFoundError("no ClusterQueue")},
        ),
    ],
    ids=["user-not-found", "community-not-found"],
)
def test_missing_workload_subjects_are_sanitized_as_not_found(path, provider_kwargs) -> None:
    """Valid workload subjects without source data map to a stable 404."""
    runtime, _provider = _runtime(provider=FakeProvider(**provider_kwargs))

    with TestClient(factory_module.create_app(settings=_settings(), runtime=runtime)) as client:
        response = client.get(path)

    _assert_status(response, code=404, reason="NotFound")


@pytest.mark.parametrize(
    ("surface", "path"),
    [
        ("user", "/apis/canfar.net/v1alpha1/metrics/user/bob"),
        ("community", "/apis/canfar.net/v1alpha1/metrics/community/astronomy"),
    ],
)
def test_cached_terminal_subject_miss_maps_to_404(surface: str, path: str) -> None:
    """A Redis terminal miss remains a sanitized subject 404, never a 503."""
    runtime, _provider = _runtime(**{f"{surface}_cache": _TerminalNotFoundCache(surface)})

    with TestClient(factory_module.create_app(settings=_settings(), runtime=runtime)) as client:
        assert runtime._readiness._surfaces[surface].source_reachable  # noqa: SLF001
        response = client.get(path)
        assert runtime._readiness._surfaces[surface].source_reachable  # noqa: SLF001

    _assert_status(response, code=404, reason="NotFound")


def test_lifespan_degrades_when_kueue_startup_fails() -> None:
    """Redis-backed startup serves liveness while Kueue keeps readiness degraded."""
    provider = FakeProvider(startup_error=RuntimeStartupError("startup validation failed"))
    runtime, _provider = _runtime(provider=provider)
    app = factory_module.create_app(settings=_settings(), runtime=runtime)

    with TestClient(app) as client:
        assert client.get("/livez").status_code == 200
        assert client.get("/readyz").status_code == 503

    assert provider.started >= 1
    assert provider.stopped == 1


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
        await exit_task

    assert events == [
        "runtime-start",
        "runtime-shutdown-start",
        "runtime-shutdown-complete",
        "telemetry-shutdown",
    ]
