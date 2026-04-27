"""Prometheus provider (configurable; no supported metric scopes yet)."""

from __future__ import annotations

import hashlib
import json

from metrics.core.settings import Settings
from metrics.providers.base import MetricScope, Provider, ProviderMetrics


class PrometheusMetrics(ProviderMetrics):
    """Reserved for future scopes; none are active yet."""

    supported_scopes: frozenset[MetricScope] = frozenset()


class PrometheusProvider(Provider):
    """Holds config validation only; HTTP client is not opened until scopes exist."""

    def __init__(self, settings: Settings) -> None:
        """Retain Prometheus settings and an empty :class:`PrometheusMetrics` impl.

        Args:
            settings: ``providers.prometheus`` supplies URL and query defaults.
        """
        self._settings = settings
        self._prometheus_config = settings.providers.prometheus
        self._metrics = PrometheusMetrics()

    @property
    def name(self) -> str:
        """Stable key ``prometheus`` for configuration."""
        return "prometheus"

    def metrics(self) -> PrometheusMetrics:
        """Return the inert :class:`PrometheusMetrics` instance."""
        return self._metrics

    def cache_fingerprint(self) -> str:
        """Short hash of the non-null Prometheus config fields."""
        raw = json.dumps(
            self._prometheus_config.model_dump(mode="json", exclude_none=True),
            sort_keys=True,
        )
        return hashlib.sha256(raw.encode("utf-8")).hexdigest()[:24]

    async def startup(self) -> None:
        """Touch URL settings so obvious misconfig fails early in logs."""
        _ = self._prometheus_config.url

    async def shutdown(self) -> None:
        """No long-lived client; no-op."""
        return None
