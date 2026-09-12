"""Focused tests for the Kueue-only Metrics provider."""

from __future__ import annotations

import asyncio
import contextlib
from typing import Any

import httpx
import kr8s
import pytest

from metrics.core.settings import CacheConfig, KueueProviderConfig, ProviderConfigs, Settings
from metrics.errors import (
    ProviderExecutionError,
    ProviderUnavailableError,
    RuntimeStartupError,
    SubjectNotFoundError,
)
from metrics.providers.kueue import KueueProvider


pytestmark = pytest.mark.anyio


def _settings(*, queues: list[str] | None = None, namespaces: list[str] | None = None) -> Settings:
    """Build valid mandatory-Redis settings for provider tests."""
    return Settings(
        cluster_name="cluster-a",
        redis_url="redis://redis.test:6379/0",
        cache=CacheConfig(key_secret="x" * 32),
        providers=ProviderConfigs(
            kueue=KueueProviderConfig(
                cluster_queues=queues or ["cq-astronomy", "cq-physics"],
                namespaces=namespaces or ["work-a", "work-b"],
            )
        ),
    )


def _cluster_queue(name: str, community: str, *, cpu: str = "4") -> dict[str, Any]:
    """Build one labelled ClusterQueue fixture."""
    return {
        "metadata": {"name": name, "labels": {"canfar.net/community": community}},
        "spec": {
            "resourceGroups": [{"flavors": [{"resources": [{"name": "cpu", "nominalQuota": cpu}]}]}]
        },
        "status": {
            "flavorsReservation": [{"resources": [{"name": "cpu", "total": "2"}]}],
            "flavorsUsage": [{"resources": [{"name": "cpu", "total": "1"}]}],
            "reservingWorkloads": 2,
        },
    }


def _local_queue(
    name: str,
    namespace: str,
    username: str,
    community: str,
    cluster_queue: str,
    *,
    cpu: str = "1",
    reserving: int = 1,
) -> dict[str, Any]:
    """Build one labelled LocalQueue fixture."""
    return {
        "metadata": {
            "name": name,
            "namespace": namespace,
            "labels": {
                "canfar.net/username": username,
                "canfar.net/community": community,
            },
        },
        "spec": {"clusterQueue": cluster_queue},
        "status": {
            "flavorsReservation": [{"resources": [{"name": "cpu", "total": cpu}]}],
            "flavorsUsage": [],
            "reservingWorkloads": reserving,
        },
    }


class FakeKueueApi:
    """Return ClusterQueue GETs and namespaced LocalQueue LISTs."""

    def __init__(
        self,
        queues: dict[str, dict[str, Any]],
        local_queues: dict[str, list[dict[str, Any]]],
        *,
        local_metadata: dict[str, dict[str, Any]] | None = None,
    ):
        self.queues = queues
        self.local_queues = local_queues
        self.local_metadata = local_metadata or {}
        self.local_selectors: list[tuple[str, str | None]] = []
        self.local_params: list[tuple[str, dict[str, str]]] = []

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
        """Implement the small kr8s call_api surface used by the provider."""
        del method, version
        if url.startswith("clusterqueues/"):
            name = url.rsplit("/", 1)[-1]
            value = self.queues.get(name)
            if value is None:
                raise kr8s.ServerError("not found", response=httpx.Response(404))
            yield httpx.Response(200, json=value)
            return
        assert namespace is not None
        selector = None if params is None else params.get("labelSelector")
        self.local_selectors.append((namespace, selector))
        self.local_params.append((namespace, dict(params or {})))
        yield httpx.Response(
            200,
            json={
                "items": self.local_queues.get(namespace, []),
                "metadata": self.local_metadata.get(namespace, {}),
            },
        )


def _provider(
    api: FakeKueueApi,
    *,
    queues: list[str] | None = None,
    namespaces: list[str] | None = None,
) -> KueueProvider:
    """Construct a provider over the fake API."""
    settings = _settings(queues=queues, namespaces=namespaces)
    return KueueProvider(settings, api=api)


async def test_platform_sums_capacity_allocation_and_reserving_workloads() -> None:
    """Platform totals use configured ClusterQueues and exclude Cohorts."""
    api = FakeKueueApi(
        {
            "cq-astronomy": _cluster_queue("cq-astronomy", "astronomy", cpu="4"),
            "cq-physics": _cluster_queue("cq-physics", "physics", cpu="6"),
        },
        {"work-a": [], "work-b": []},
    )

    result = await _provider(api).read_platform()

    assert result.capacity == {"cpu": "10"}
    assert result.allocated == {"cpu": "2"}
    assert result.reserving_workloads == 4


async def test_clusterqueue_reads_use_bounded_concurrency_in_configured_order() -> None:
    """Configured ClusterQueue GETs overlap without starting the full list at once."""
    names = ["cq-a", "cq-b", "cq-c", "cq-d", "cq-e"]

    class BlockingApi(FakeKueueApi):
        def __init__(self) -> None:
            super().__init__(
                {name: _cluster_queue(name, "astronomy", cpu="1") for name in names},
                {"work-a": []},
            )
            self.active = 0
            self.max_active = 0
            self.started: list[str] = []
            self.bound_reached = asyncio.Event()
            self.release = asyncio.Event()

        @contextlib.asynccontextmanager
        async def call_api(self, **kwargs):
            url = kwargs["url"]
            if not url.startswith("clusterqueues/"):
                async with super().call_api(**kwargs) as response:
                    yield response
                return
            self.started.append(url.rsplit("/", 1)[-1])
            self.active += 1
            self.max_active = max(self.max_active, self.active)
            if self.active == 4:
                self.bound_reached.set()
            try:
                await self.release.wait()
                async with super().call_api(**kwargs) as response:
                    yield response
            finally:
                self.active -= 1

    api = BlockingApi()
    task = asyncio.create_task(_provider(api, queues=names, namespaces=["work-a"]).read_platform())
    try:
        async with asyncio.timeout(1):
            await api.bound_reached.wait()
        await asyncio.sleep(0)
        assert api.started == names[:4]
        assert api.max_active == 4
        api.release.set()
        result = await task
    finally:
        api.release.set()
        if not task.done():
            task.cancel()
            await asyncio.gather(task, return_exceptions=True)

    assert result.capacity == {"cpu": "5"}
    assert api.started == names


async def test_clusterqueue_failure_cancels_siblings_and_propagates() -> None:
    """One failed configured GET cancels every in-flight sibling before returning."""
    names = ["cq-a", "cq-b", "cq-c", "cq-d", "cq-e"]

    class FailingApi(FakeKueueApi):
        def __init__(self) -> None:
            super().__init__(
                {name: _cluster_queue(name, "astronomy") for name in names},
                {"work-a": []},
            )
            self.started: list[str] = []
            self.cancelled: set[str] = set()
            self.siblings_started = asyncio.Event()

        @contextlib.asynccontextmanager
        async def call_api(self, **kwargs):
            name = kwargs["url"].rsplit("/", 1)[-1]
            self.started.append(name)
            if len(self.started) == 4:
                self.siblings_started.set()
            if name == "cq-a":
                await self.siblings_started.wait()
                raise kr8s.ServerError("failed", response=httpx.Response(500))
            try:
                await asyncio.Future()
            except asyncio.CancelledError:
                self.cancelled.add(name)
                raise
            raise AssertionError("unreachable")
            yield

    api = FailingApi()
    with pytest.raises(ProviderUnavailableError, match="ClusterQueue access failed"):
        async with asyncio.timeout(1):
            await _provider(api, queues=names, namespaces=["work-a"]).read_platform()

    assert api.started == names[:4]
    assert api.cancelled == {"cq-b", "cq-c", "cq-d"}


async def test_platform_accepts_empty_optional_status_fields() -> None:
    """A configured ClusterQueue may omit zero reservation and usage fields."""
    queue = _cluster_queue("cq-astronomy", "astronomy")
    queue["status"] = {}
    api = FakeKueueApi(
        {"cq-astronomy": queue},
        {"work-a": [], "work-b": []},
    )

    result = await _provider(api, queues=["cq-astronomy"]).read_platform()

    assert result.capacity == {"cpu": "4"}
    assert result.allocated == {"cpu": "0"}
    assert result.reserving_workloads == 0


async def test_user_accepts_empty_optional_status_fields() -> None:
    """An otherwise valid LocalQueue may report no reservation or active work."""
    local = _local_queue("bob-a", "work-a", "bob", "astronomy", "cq-astronomy")
    local["status"] = {
        "reservingWorkloads": None,
        "flavorsReservation": None,
        "flavorsUsage": None,
    }
    api = FakeKueueApi(
        {"cq-astronomy": _cluster_queue("cq-astronomy", "astronomy")},
        {"work-a": [local], "work-b": []},
    )

    result = await _provider(api, queues=["cq-astronomy"]).read_user("bob")

    assert result.requests == {}
    assert result.reserving_workloads == 0


async def test_community_filters_configured_clusterqueues_by_exact_label() -> None:
    """Community requests sum reservation values from matching ClusterQueues."""
    api = FakeKueueApi(
        {
            "cq-astronomy": _cluster_queue("cq-astronomy", "astronomy", cpu="4"),
            "cq-physics": _cluster_queue("cq-physics", "physics", cpu="6"),
        },
        {"work-a": [], "work-b": []},
    )

    result = await _provider(api).read_community("astronomy")

    assert result.requests == {"cpu": "2"}
    assert result.reserving_workloads == 2
    with pytest.raises(SubjectNotFoundError):
        await _provider(api).read_community("biology")


async def test_user_aggregates_valid_localqueues_across_namespaces() -> None:
    """Multiple namespaces aggregate while exact queue identity remains visible."""
    api = FakeKueueApi(
        {
            "cq-astronomy": _cluster_queue("cq-astronomy", "astronomy"),
            "cq-physics": _cluster_queue("cq-physics", "physics"),
        },
        {
            "work-a": [
                _local_queue("bob-a", "work-a", "bob", "astronomy", "cq-astronomy", cpu="1")
            ],
            "work-b": [_local_queue("bob-b", "work-b", "bob", "physics", "cq-physics", cpu="3")],
        },
    )

    result = await _provider(api).read_user("bob")

    assert result.requests == {"cpu": "4"}
    assert result.reserving_workloads == 2
    assert api.local_selectors == [
        ("work-a", "canfar.net/username=bob"),
        ("work-b", "canfar.net/username=bob"),
    ]


async def test_user_lists_namespaces_concurrently_in_configured_result_order() -> None:
    """Reverse completion does not change configured namespace validation order."""
    first = _local_queue("bob-a", "work-a", "alice", "astronomy", "cq-astronomy")
    second = _local_queue("bob-b", "work-b", "bob", "astronomy", "cq-physics")

    class ReverseCompletionApi(FakeKueueApi):
        def __init__(self) -> None:
            super().__init__(
                {
                    "cq-astronomy": _cluster_queue("cq-astronomy", "astronomy"),
                    "cq-physics": _cluster_queue("cq-physics", "physics"),
                },
                {"work-a": [first], "work-b": [second]},
            )
            self.started: list[str] = []
            self.completed: list[str] = []
            self.both_started = asyncio.Event()
            self.second_completed = asyncio.Event()

        @contextlib.asynccontextmanager
        async def call_api(self, **kwargs):
            namespace = kwargs.get("namespace")
            if namespace is None:
                async with super().call_api(**kwargs) as response:
                    yield response
                return
            self.started.append(namespace)
            if len(self.started) == 2:
                self.both_started.set()
            await self.both_started.wait()
            if namespace == "work-a":
                await self.second_completed.wait()
            async with super().call_api(**kwargs) as response:
                yield response
            self.completed.append(namespace)
            if namespace == "work-b":
                self.second_completed.set()

    api = ReverseCompletionApi()
    with pytest.raises(ProviderExecutionError, match="username label did not match"):
        async with asyncio.timeout(1):
            await _provider(api).read_user("bob")

    assert api.started == ["work-a", "work-b"]
    assert api.completed == ["work-b", "work-a"]


async def test_user_with_no_matching_localqueue_is_not_zero() -> None:
    """A valid empty search is a not-found subject, not a zero report."""
    api = FakeKueueApi(
        {"cq-astronomy": _cluster_queue("cq-astronomy", "astronomy")},
        {"work-a": [], "work-b": []},
    )

    with pytest.raises(SubjectNotFoundError):
        await _provider(api, queues=["cq-astronomy"]).read_user("bob")


@pytest.mark.parametrize(
    "mutate",
    [
        lambda item: item["metadata"]["labels"].pop("canfar.net/username"),
        lambda item: item["metadata"]["labels"].update({"canfar.net/community": "physics"}),
        lambda item: item["spec"].update({"clusterQueue": "cq-outside"}),
    ],
)
async def test_invalid_matching_localqueue_is_dependency_corruption(mutate) -> None:
    """Matching queues with invalid identity never disappear from the total."""
    local = _local_queue("bob-a", "work-a", "bob", "astronomy", "cq-astronomy")
    mutate(local)
    api = FakeKueueApi(
        {"cq-astronomy": _cluster_queue("cq-astronomy", "astronomy")},
        {"work-a": [local], "work-b": []},
    )

    with pytest.raises(ProviderExecutionError):
        await _provider(api, queues=["cq-astronomy"]).read_user("bob")


async def test_user_aggregates_distinct_localqueues_with_same_labels() -> None:
    """Distinct LocalQueues with the same user and community labels are summed."""
    first = _local_queue("bob-a", "work-a", "bob", "astronomy", "cq-astronomy")
    second = _local_queue("bob-b", "work-a", "bob", "astronomy", "cq-astronomy", cpu="2")
    api = FakeKueueApi(
        {"cq-astronomy": _cluster_queue("cq-astronomy", "astronomy")},
        {"work-a": [first, second], "work-b": []},
    )

    result = await _provider(api, queues=["cq-astronomy"]).read_user("bob")

    assert result.requests == {"cpu": "3"}
    assert result.reserving_workloads == 2


async def test_duplicate_localqueue_object_identity_is_dependency_corruption() -> None:
    """A repeated namespace/name LocalQueue object is not counted twice."""
    first = _local_queue("bob-a", "work-a", "bob", "astronomy", "cq-astronomy")
    second = _local_queue("bob-a", "work-a", "bob", "astronomy", "cq-astronomy")
    api = FakeKueueApi(
        {"cq-astronomy": _cluster_queue("cq-astronomy", "astronomy")},
        {"work-a": [first, second], "work-b": []},
    )

    with pytest.raises(ProviderExecutionError, match="identity was duplicated"):
        await _provider(api, queues=["cq-astronomy"]).read_user("bob")


async def test_configured_clusterqueue_requires_nonempty_community_label() -> None:
    """A configured queue without the community boundary fails startup."""
    queue = _cluster_queue("cq-astronomy", "astronomy")
    queue["metadata"]["labels"]["canfar.net/community"] = ""
    api = FakeKueueApi({"cq-astronomy": queue}, {"work-a": [], "work-b": []})

    with pytest.raises(RuntimeStartupError):
        await _provider(api, queues=["cq-astronomy"]).startup()


async def test_startup_uses_one_bounded_localqueue_access_probe_per_namespace() -> None:
    """Startup probes namespaces concurrently without following pagination tokens."""

    class ConcurrentProbeApi(FakeKueueApi):
        def __init__(self) -> None:
            super().__init__(
                {"cq-astronomy": _cluster_queue("cq-astronomy", "astronomy")},
                {"work-a": [], "work-b": []},
                local_metadata={
                    "work-a": {"continue": "next-a"},
                    "work-b": {"continue": "next-b"},
                },
            )
            self.probe_started: list[str] = []
            self.both_started = asyncio.Event()

        @contextlib.asynccontextmanager
        async def call_api(self, **kwargs):
            namespace = kwargs.get("namespace")
            if namespace is None:
                async with super().call_api(**kwargs) as response:
                    yield response
                return
            self.probe_started.append(namespace)
            if len(self.probe_started) == 2:
                self.both_started.set()
            await self.both_started.wait()
            async with super().call_api(**kwargs) as response:
                yield response

    api = ConcurrentProbeApi()

    async with asyncio.timeout(1):
        await _provider(api, queues=["cq-astronomy"]).startup()

    assert api.probe_started == ["work-a", "work-b"]
    assert len(api.local_params) == 2
    assert dict(api.local_params) == {
        "work-a": {"limit": "1"},
        "work-b": {"limit": "1"},
    }


async def test_startup_validates_every_configured_clusterqueue_before_probing() -> None:
    """Startup validates all configured ClusterQueues before namespace access probes."""
    invalid = _cluster_queue("cq-physics", "physics")
    invalid["metadata"]["labels"].pop("canfar.net/community")
    api = FakeKueueApi(
        {
            "cq-astronomy": _cluster_queue("cq-astronomy", "astronomy"),
            "cq-physics": invalid,
        },
        {"work-a": [], "work-b": []},
    )

    with pytest.raises(RuntimeStartupError):
        await _provider(api).startup()

    assert api.local_params == []
