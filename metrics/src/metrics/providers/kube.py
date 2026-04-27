"""Kubernetes provider placeholder plus shared Kubernetes HTTP helper functions."""

from __future__ import annotations

import asyncio
import json
import os
from hashlib import sha256
from pathlib import Path
from typing import Any

import httpx

from metrics.core.settings import KubeProviderConfig, Settings
from metrics.providers.base import MetricScope, Provider, ProviderMetrics


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


class KubeMetrics(ProviderMetrics):
    """Inert Kueue-style metrics type until kube metrics scopes are implemented."""

    supported_scopes: frozenset[MetricScope] = frozenset()


class KubeProvider(Provider):
    """Placeholder provider; configuration must keep ``enabled`` false."""

    def __init__(self, settings: Settings) -> None:
        """Capture settings; no HTTP clients are opened for this reserved provider.

        Args:
            settings: Application settings; ``providers.kube`` is validated inert.
        """
        self._settings = settings
        self._kube_config: KubeProviderConfig = settings.providers.kube
        self._metrics = KubeMetrics()

    @property
    def name(self) -> str:
        """Stable key ``kube`` for this reserved provider."""
        return "kube"

    def metrics(self) -> KubeMetrics:
        """Return the (currently empty) :class:`KubeMetrics` implementation."""
        return self._metrics

    def cache_fingerprint(self) -> str:
        """Deterministic short hash of the JSON-encoded kube config block."""
        raw = json.dumps(
            self._kube_config.model_dump(mode="json", exclude_none=True),
            sort_keys=True,
        )
        return sha256(raw.encode("utf-8")).hexdigest()[:24]

    async def startup(self) -> None:
        """No network work; validation happens in :class:`KubeProviderConfig`."""
        return None

    async def shutdown(self) -> None:
        """No resources held; no-op."""
        return None
