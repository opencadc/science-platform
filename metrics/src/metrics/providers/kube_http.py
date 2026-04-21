"""Low-level HTTP helpers for talking to the Kubernetes API server from async code.

Intent
------
Metrics reads **cluster-scoped** Kueue objects and (elsewhere) node or
Prometheus endpoints. Every call must respect ``METRICS_KUBE_REQUEST_TIMEOUT_SECONDS``,
TLS policy, and bearer token rules without duplicating ``httpx`` setup.

**Parallel GETs** (:func:`kube_parallel_get_json`) reuse a single ``AsyncClient``
so TLS session setup and connection pooling apply across a burst of reads
(for example all configured ``ClusterQueue`` objects plus one ``Cohort``).
That keeps tail latency predictable when several objects are fetched per
request or startup validation.
"""

from __future__ import annotations

import asyncio
import os
from pathlib import Path
from typing import Any

import httpx


def resolve_kube_token(explicit: str | None) -> str | None:
    """Resolve the bearer token used for ``Authorization: Bearer …`` headers.

    Priority:
    1. Explicit ``METRICS_KUBE_API_TOKEN`` from settings when set.
    2. Pod service-account token file (default path matches Kubernetes' in-cluster
       convention), optionally overridden by ``METRICS_KUBE_SA_TOKEN_PATH``.

    Returns:
        Token string, or ``None`` if no token is available (anonymous access only
        works on clusters that allow it; production typically requires a token).
    """
    if explicit:
        return explicit
    path = Path(
        os.environ.get(
            "METRICS_KUBE_SA_TOKEN_PATH",
            "/var/run/secrets/kubernetes.io/serviceaccount/token",
        )
    )
    if path.is_file():
        return path.read_text(encoding="utf-8").strip()
    return None


def resolve_kube_tls_verify(verify_tls: bool) -> bool | str:
    """Map settings ``kube_verify_tls`` to an ``httpx`` ``verify`` argument.

    When verification is enabled and the standard service-account CA file is
    present, return that path so in-cluster TLS validates against the cluster
    root. Otherwise fall back to system trust store (``True``) or disable
    verification (``False``) for explicit dev-only setups.

    Args:
        verify_tls: Whether TLS certificate verification should be enabled.

    Returns:
        ``False``, ``True``, or a filesystem path to a PEM bundle.
    """
    if not verify_tls:
        return False
    ca = Path(
        os.environ.get(
            "METRICS_KUBE_SA_CA_PATH",
            "/var/run/secrets/kubernetes.io/serviceaccount/ca.crt",
        )
    )
    if ca.is_file():
        return str(ca)
    return True


def kube_auth_headers(token: str | None) -> dict[str, str]:
    """Build request headers for authenticated Kubernetes API calls.

    Args:
        token: Optional bearer token; when missing, returns an empty dict so
            callers rely on network policy / anonymous RBAC only.

    Returns:
        Headers mapping suitable for ``httpx`` ``headers=``.
    """
    if token:
        return {"Authorization": f"Bearer {token}"}
    return {}


async def kube_get_json(
    url: str,
    *,
    headers: dict[str, str],
    timeout: float,
    verify: bool | str,
) -> dict[str, Any]:
    """Perform a single ``GET`` and decode JSON response.

    Implemented on top of :func:`kube_parallel_get_json` so single and multi
    URL paths share one TLS/client policy.

    Raises:
        httpx.HTTPStatusError: When the server returns a non-success status.
        httpx.RequestError: On network-level failures.
    """
    docs = await kube_parallel_get_json(
        [url], headers=headers, timeout=timeout, verify=verify
    )
    return docs[0]


async def kube_parallel_get_json(
    urls: list[str],
    *,
    headers: dict[str, str],
    timeout: float,
    verify: bool | str,
) -> list[dict[str, Any]]:
    """``GET`` many URLs concurrently using one ``httpx.AsyncClient``.

    Results are returned **in the same order** as ``urls``. Callers rely on
    this when the first document is a cohort and the remainder are queues (or
    any other positional contract).

    Args:
        urls: Absolute Kubernetes API URLs to fetch.
        headers: Authentication and optional ``Accept`` headers.
        timeout: Per-request timeout bound passed to ``httpx.AsyncClient``.
        verify: TLS verification policy (see :func:`resolve_kube_tls_verify`).

    Returns:
        List of parsed JSON objects (typically Kubernetes resource bodies).

    Raises:
        httpx.HTTPStatusError: If any response has a non-success status (the
            first failing request propagates after ``gather`` completes).
        httpx.RequestError: On transport errors for any request.
    """
    if not urls:
        return []
    async with httpx.AsyncClient(timeout=timeout, verify=verify) as client:

        async def one(target: str) -> dict[str, Any]:
            response = await client.get(target, headers=headers)
            response.raise_for_status()
            return response.json()

        return list(await asyncio.gather(*(one(u) for u in urls)))
