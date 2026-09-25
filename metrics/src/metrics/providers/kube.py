"""Shared Kubernetes read plumbing for the Metrics providers.

Every provider reads through one ``KubeReader``: a lazily bound kr8s API
handle whose requests map HTTP status codes themselves. kr8s' own
``raise_for_status`` path re-authenticates up to three times on 401/403 and
recreates the shared HTTP client each time, which multiplies denied requests
and closes connections that concurrent reads are still using. Here a 403 is
a single sanitized ``KubeStatusError``, and a 401 refreshes the bearer token
in place and retries once.
"""

from __future__ import annotations

import asyncio
import json
import re
from collections.abc import Awaitable, Callable
from datetime import UTC, datetime
from typing import Any, TypeVar

import httpx
import kr8s
import kr8s.asyncio

from metrics.errors import ProviderExecutionError, ProviderUnavailableError

LABEL_VALUE = re.compile(r"^[A-Za-z0-9](?:[-A-Za-z0-9_.]{0,61}[A-Za-z0-9])?$")
MAX_RESULT_OBJECTS = 3_000
_MAX_LIST_PAGES = 1_000
_MAX_CONTINUE_TOKEN_LENGTH = 4_096
_LIST_PAGE_LIMIT = 100
_READ_CONCURRENCY = 4
_TRANSPORT_ERRORS = (kr8s.APITimeoutError, kr8s.ConnectionClosedError, httpx.HTTPError)

_Input = TypeVar("_Input")
_Output = TypeVar("_Output")
_First = TypeVar("_First")
_Second = TypeVar("_Second")


class KubeStatusError(ProviderUnavailableError):
    """Report a non-success Kubernetes API status without its response body."""

    def __init__(self, kind: str, status_code: int) -> None:
        """Keep the status code for logs and a sanitized message."""
        super().__init__(f"{kind} request returned HTTP {status_code}")
        self.status_code = status_code


def observation_time() -> datetime:
    """Return a UTC timestamp with millisecond precision."""
    now = datetime.now(UTC)
    return now.replace(microsecond=now.microsecond // 1_000 * 1_000)


def label_value(value: str) -> str:
    """Require a Kubernetes label value before it reaches a selector."""
    if not isinstance(value, str) or LABEL_VALUE.fullmatch(value) is None:
        raise ProviderExecutionError("Subject value is not a valid label value")
    return value


def label_selector(label: str, value: str) -> str:
    """Build one exact-match selector for a validated label value."""
    return f"{label}={label_value(value)}"


def mapping(value: object, message: str) -> dict[str, Any]:
    """Require one decoded Kubernetes object member to be a mapping."""
    if not isinstance(value, dict):
        raise ProviderExecutionError(message)
    return value


def sequence(value: object, message: str) -> list[Any]:
    """Require one decoded Kubernetes object member to be a list."""
    if not isinstance(value, list):
        raise ProviderExecutionError(message)
    return value


def labels_of(doc: dict[str, Any], kind: str) -> dict[str, str]:
    """Return a validated Kubernetes label map."""
    metadata = mapping(doc.get("metadata"), f"{kind} metadata was invalid")
    labels = mapping(metadata.get("labels"), f"{kind} labels were missing or invalid")
    if not all(isinstance(name, str) and isinstance(value, str) for name, value in labels.items()):
        raise ProviderExecutionError(f"{kind} labels were invalid")
    return labels


def name_of(doc: dict[str, Any], kind: str) -> str:
    """Return one object's non-empty ``metadata.name``."""
    metadata = mapping(doc.get("metadata"), f"{kind} metadata was invalid")
    name = metadata.get("name")
    if not isinstance(name, str) or not name:
        raise ProviderExecutionError(f"{kind} metadata name was missing or invalid")
    return name


def parse_timestamp(value: object, message: str) -> datetime:
    """Parse one RFC 3339 Kubernetes timestamp into UTC."""
    if not isinstance(value, str) or not value:
        raise ProviderExecutionError(message)
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exc:
        raise ProviderExecutionError(message) from exc
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        raise ProviderExecutionError(message)
    return parsed.astimezone(UTC)


async def fan_out(
    items: list[_Input],
    operation: Callable[[_Input], Awaitable[_Output]],
) -> list[_Output]:
    """Run independent reads with bounded workers and ordered results.

    The first failure cancels and awaits every sibling before it propagates,
    so no request outlives the caller.
    """
    next_index = 0
    results: dict[int, _Output] = {}

    async def worker() -> None:
        nonlocal next_index
        while next_index < len(items):
            index = next_index
            next_index += 1
            results[index] = await operation(items[index])

    tasks = [asyncio.create_task(worker()) for _ in range(min(_READ_CONCURRENCY, len(items)))]
    try:
        await asyncio.gather(*tasks)
    except BaseException:
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        raise
    return [results[index] for index in range(len(items))]


async def concurrently(
    first: Awaitable[_First], second: Awaitable[_Second]
) -> tuple[_First, _Second]:
    """Await two independent reads together; a failure cancels the other."""
    first_task = asyncio.ensure_future(first)
    second_task = asyncio.ensure_future(second)
    try:
        await asyncio.gather(first_task, second_task)
    except BaseException:
        for task in (first_task, second_task):
            task.cancel()
        await asyncio.gather(first_task, second_task, return_exceptions=True)
        raise
    return first_task.result(), second_task.result()


class KubeReader:
    """Read Kubernetes JSON through one lazily bound kr8s API handle."""

    def __init__(self, *, timeout: float, api: Any | None = None) -> None:
        """Keep the request timeout and an optional pre-bound (or fake) API."""
        self._timeout = timeout
        self._api = api
        self._credentials = asyncio.Lock()

    async def _bound(self) -> Any:
        """Bind the kr8s API from in-cluster credentials or kubeconfig once."""
        if self._api is None:
            try:
                api = await kr8s.asyncio.api()
            except Exception as exc:
                raise ProviderUnavailableError("Could not configure Kubernetes API access") from exc
            api.timeout = self._timeout
            self._api = api
        return self._api

    async def _refresh_credentials(self, api: Any) -> None:
        """Reload a rotated ServiceAccount token without closing the shared client."""
        async with self._credentials:
            await api.auth.reauthenticate()
            session = getattr(api, "_session", None)
            if session is not None and api.auth.token:
                session.headers["Authorization"] = f"Bearer {api.auth.token}"

    async def get(
        self,
        *,
        version: str,
        url: str,
        kind: str,
        namespace: str | None = None,
        params: dict[str, str] | None = None,
    ) -> dict[str, Any]:
        """Return one decoded JSON object, mapping every failure to a provider error."""
        api = await self._bound()
        for attempt in range(2):
            try:
                async with api.call_api(
                    method="GET",
                    version=version,
                    namespace=namespace,
                    url=url,
                    params=params,
                    raise_for_status=False,
                ) as response:
                    status = response.status_code
                    body = response.content if 200 <= status < 300 else b""
            except _TRANSPORT_ERRORS as exc:
                raise ProviderUnavailableError(f"{kind} request failed") from exc
            if status == 401 and attempt == 0:
                await self._refresh_credentials(api)
                continue
            if not 200 <= status < 300:
                raise KubeStatusError(kind, status)
            try:
                payload = json.loads(body)
            except (json.JSONDecodeError, UnicodeDecodeError) as exc:
                raise ProviderExecutionError(f"{kind} payload was not valid JSON") from exc
            if not isinstance(payload, dict):
                raise ProviderExecutionError(f"{kind} payload was not an object")
            return payload
        raise KubeStatusError(kind, 401)

    async def list_all(
        self,
        *,
        version: str,
        resource: str,
        namespace: str,
        kind: str,
        selector: str | None = None,
    ) -> list[dict[str, Any]]:
        """List every matching object in one namespace with bounded pagination."""
        items: list[dict[str, Any]] = []
        continue_token: str | None = None
        seen_tokens: set[str] = set()
        for _page in range(_MAX_LIST_PAGES):
            params = {"limit": str(_LIST_PAGE_LIMIT)}
            if selector:
                params["labelSelector"] = selector
            if continue_token is not None:
                params["continue"] = continue_token
            payload = await self.get(
                version=version, url=resource, kind=kind, namespace=namespace, params=params
            )
            page_items = payload.get("items")
            if not isinstance(page_items, list) or not all(
                isinstance(item, dict) for item in page_items
            ):
                raise ProviderExecutionError(f"{kind} contained an invalid object shape")
            if len(items) + len(page_items) > MAX_RESULT_OBJECTS:
                raise ProviderExecutionError(f"{kind} exceeded the result limit")
            items.extend(page_items)
            metadata = payload.get("metadata")
            if metadata is None:
                return items
            if not isinstance(metadata, dict):
                raise ProviderExecutionError(f"{kind} metadata was invalid")
            next_token = metadata.get("continue")
            if next_token in (None, ""):
                return items
            if (
                not isinstance(next_token, str)
                or len(next_token) > _MAX_CONTINUE_TOKEN_LENGTH
                or next_token in seen_tokens
            ):
                raise ProviderExecutionError(f"{kind} pagination token was invalid")
            seen_tokens.add(next_token)
            continue_token = next_token
        raise ProviderExecutionError(f"{kind} exceeded the pagination limit")

    async def probe(self, *, version: str, resource: str, namespace: str, kind: str) -> None:
        """Prove list access in one namespace without following pagination."""
        payload = await self.get(
            version=version, url=resource, kind=kind, namespace=namespace, params={"limit": "1"}
        )
        items = payload.get("items")
        if not isinstance(items, list) or not all(isinstance(item, dict) for item in items):
            raise ProviderExecutionError(f"{kind} access probe returned an invalid list")

    def close(self) -> None:
        """Drop the API handle reference; kr8s owns the shared client."""
        self._api = None
