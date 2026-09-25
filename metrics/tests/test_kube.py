"""Shared Kubernetes reader: status mapping, credential refresh, and fan-out."""

from __future__ import annotations

import asyncio
import contextlib
from types import SimpleNamespace
from typing import Any

import httpx
import kr8s
import pytest

from metrics.cache import describe_failure
from metrics.errors import ProviderExecutionError, ProviderUnavailableError
from metrics.providers.kube import KubeReader, KubeStatusError, concurrently, fan_out

pytestmark = pytest.mark.anyio


class Api:
    """Answer each request from a queue of responses or exceptions."""

    def __init__(self, *answers: Any) -> None:
        self.answers = list(answers)
        self.calls: list[dict[str, Any]] = []
        self.auth = SimpleNamespace(token="old", reauthenticate=self._reauthenticate)
        self._session = SimpleNamespace(headers={"Authorization": "Bearer old"})
        self.reauthentications = 0

    async def _reauthenticate(self) -> None:
        self.reauthentications += 1
        self.auth.token = "new"

    @contextlib.asynccontextmanager
    async def call_api(self, **kwargs: Any):
        self.calls.append(kwargs)
        answer = self.answers.pop(0)
        if isinstance(answer, BaseException):
            raise answer
        yield answer


async def test_success_decodes_json_and_never_raises_for_status_in_kr8s() -> None:
    api = Api(httpx.Response(200, json={"items": []}))
    payload = await KubeReader(timeout=1, api=api).get(version="v1", url="pods", kind="Pod list")
    assert payload == {"items": []}
    assert api.calls[0]["raise_for_status"] is False


async def test_forbidden_is_one_request_with_a_sanitized_status_error() -> None:
    api = Api(httpx.Response(403, json={"message": "clusterqueues cq is forbidden for bob"}))
    with pytest.raises(KubeStatusError) as denied:
        await KubeReader(timeout=1, api=api).get(version="v1", url="x", kind="ClusterQueue")
    assert denied.value.status_code == 403 and len(api.calls) == 1
    assert "bob" not in str(denied.value) and api.reauthentications == 0
    assert isinstance(denied.value, ProviderUnavailableError)
    assert describe_failure(denied.value) == "KubeStatusError(403)"


async def test_unauthorized_refreshes_the_token_in_place_and_retries_once() -> None:
    api = Api(httpx.Response(401), httpx.Response(200, json={"ok": True}))
    reader = KubeReader(timeout=1, api=api)
    assert await reader.get(version="v1", url="x", kind="Job list") == {"ok": True}
    assert api.reauthentications == 1
    assert api._session.headers["Authorization"] == "Bearer new"

    api.answers = [httpx.Response(401), httpx.Response(401)]
    with pytest.raises(KubeStatusError) as unauthorized:
        await reader.get(version="v1", url="x", kind="Job list")
    assert unauthorized.value.status_code == 401 and api.reauthentications == 2


@pytest.mark.parametrize(
    "answer",
    [
        kr8s.APITimeoutError("slow"),
        kr8s.ConnectionClosedError("closed"),
        httpx.ConnectError("refused"),
    ],
)
async def test_transport_failures_are_provider_unavailable(answer: BaseException) -> None:
    with pytest.raises(ProviderUnavailableError, match="Job list request failed"):
        await KubeReader(timeout=1, api=Api(answer)).get(version="v1", url="x", kind="Job list")


@pytest.mark.parametrize("body", [b"not json", b"[1, 2]"])
async def test_undecodable_payloads_are_execution_errors(body: bytes) -> None:
    api = Api(httpx.Response(200, content=body))
    with pytest.raises(ProviderExecutionError):
        await KubeReader(timeout=1, api=api).get(version="v1", url="x", kind="Job list")


async def test_list_follows_bounded_continue_tokens() -> None:
    api = Api(
        httpx.Response(200, json={"items": [{"a": 1}], "metadata": {"continue": "t1"}}),
        httpx.Response(200, json={"items": [{"b": 2}], "metadata": {"continue": ""}}),
    )
    items = await KubeReader(timeout=1, api=api).list_all(
        version="v1", resource="pods", namespace="ns", kind="Pod list", selector="a=b"
    )
    assert items == [{"a": 1}, {"b": 2}]
    assert api.calls[1]["params"] == {"limit": "100", "labelSelector": "a=b", "continue": "t1"}

    looping = Api(
        httpx.Response(200, json={"items": [], "metadata": {"continue": "t1"}}),
        httpx.Response(200, json={"items": [], "metadata": {"continue": "t1"}}),
    )
    with pytest.raises(ProviderExecutionError, match="pagination token"):
        await KubeReader(timeout=1, api=looping).list_all(
            version="v1", resource="pods", namespace="ns", kind="Pod list"
        )


async def test_fan_out_and_concurrently_cancel_siblings_on_failure() -> None:
    cancelled: list[str] = []

    async def slow(name: str) -> str:
        try:
            await asyncio.sleep(10)
        except asyncio.CancelledError:
            cancelled.append(name)
            raise
        return name

    async def fail(name: str) -> str:
        await asyncio.sleep(0.01)
        raise ProviderUnavailableError(name)

    with pytest.raises(ProviderUnavailableError):
        await fan_out(["a", "b", "c"], lambda name: fail(name) if name == "b" else slow(name))
    assert sorted(cancelled) == ["a", "c"]

    cancelled.clear()
    with pytest.raises(ProviderUnavailableError):
        await concurrently(slow("left"), fail("right"))
    assert cancelled == ["left"]
    assert await concurrently(asyncio.sleep(0, "x"), asyncio.sleep(0, 2)) == ("x", 2)
