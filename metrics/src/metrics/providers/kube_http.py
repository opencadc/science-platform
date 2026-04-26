"""HTTP helpers for the Kubernetes API server (injected httpx.AsyncClient)."""

from __future__ import annotations

import asyncio
import os
from pathlib import Path
from typing import Any

import httpx


def resolve_kube_token(
    explicit: str | None,
    token_file: str | None = None,
) -> str | None:
    """Resolve bearer token: explicit value, then token_file, then in-cluster file."""
    if explicit:
        return explicit
    if token_file:
        token_path = Path(token_file)
        if token_path.is_file():
            return token_path.read_text(encoding="utf-8").strip()
    path = Path(
        os.environ.get(
            "METRICS_KUBE_SA_TOKEN_PATH",
            "/var/run/secrets/kubernetes.io/serviceaccount/token",
        )
    )
    if path.is_file():
        return path.read_text(encoding="utf-8").strip()
    return None


def resolve_kube_verify(
    verify_tls: bool,
    *,
    ca_file: str | None = None,
) -> bool | str:
    """Return TLS ``verify`` argument for httpx: bool or path to a CA bundle.

    Args:
        verify_tls: When ``False``, TLS verification is disabled (not recommended
            for production).
        ca_file: Optional explicit CA file path; otherwise the in-cluster
            service account CA is used when present.

    Returns:
        ``False`` if verification is off; otherwise a path string or ``True`` for
        system trust store.
    """
    if not verify_tls:
        return False
    if ca_file:
        ca_path = Path(ca_file)
        if ca_path.is_file():
            return str(ca_path)
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
    """Build ``Authorization`` headers for a Kubernetes bearer token, if any."""
    if token:
        return {"Authorization": f"Bearer {token}"}
    return {}


async def kube_parallel_get_json(
    client: httpx.AsyncClient,
    urls: list[str],
    *,
    headers: dict[str, str],
) -> list[dict[str, Any]]:
    """GET each URL in parallel and return parsed JSON objects (same order as ``urls``)."""
    if not urls:
        return []

    async def fetch_url(target: str) -> dict[str, Any]:
        response = await client.get(target, headers=headers)
        response.raise_for_status()
        return response.json()

    return list(await asyncio.gather(*(fetch_url(u) for u in urls)))


async def kube_get_json(
    client: httpx.AsyncClient,
    url: str,
    *,
    headers: dict[str, str],
) -> dict[str, Any]:
    """GET a single URL and return the parsed JSON body."""
    docs = await kube_parallel_get_json(client, [url], headers=headers)
    return docs[0]
