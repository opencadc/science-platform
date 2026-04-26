"""Reserved kube-metrics provider; no supported metric scopes yet."""

from __future__ import annotations

import json
from hashlib import sha256

from metrics.core.settings import KubeProviderConfig, Settings
from metrics.providers.base import MetricScope, Provider, ProviderMetrics


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
