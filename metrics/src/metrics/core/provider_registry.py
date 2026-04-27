"""Maps ``sources.platform`` to concrete provider construction (M4 seam)."""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass

import httpx

from metrics.core.settings import Settings
from metrics.errors import RuntimeStartupError
from metrics.providers.base import MetricScope, Provider


@dataclass(frozen=True, slots=True)
class PlatformProviderBundle:
    """Long-lived HTTP client and :class:`Provider` for the active platform source."""

    http_client: httpx.AsyncClient
    provider: Provider


def _build_kueue_platform_bundle(settings: Settings) -> PlatformProviderBundle:
    """Construct the Kueue Adapter; imports kueue only at bundle construction (Locality)."""
    from metrics.providers.kueue import KueueProvider, kueue_http_client

    kueue_config = settings.providers.kueue
    client = kueue_http_client(kueue_config)
    return PlatformProviderBundle(http_client=client, provider=KueueProvider(settings, client))


_PLATFORM_SOURCE_BUILDERS: dict[str, Callable[[Settings], PlatformProviderBundle]] = {
    "kueue": _build_kueue_platform_bundle,
}


def supported_platform_sources() -> frozenset[str]:
    """Return configured platform source keys that have a registry entry."""
    return frozenset(_PLATFORM_SOURCE_BUILDERS)


def assert_supported_platform_source(settings: Settings) -> None:
    """Ensure ``sources.platform`` names a provider the registry can construct.

    Args:
        settings: Application settings with ``sources.platform`` set.

    Raises:
        RuntimeStartupError: If the platform source is missing or not registered.
    """
    name = (settings.sources.platform or "").strip().lower()
    if not name:
        raise RuntimeStartupError("METRICS_SOURCES__PLATFORM is required")
    if name not in _PLATFORM_SOURCE_BUILDERS:
        allowed = ", ".join(sorted(_PLATFORM_SOURCE_BUILDERS))
        raise RuntimeStartupError(f"Unsupported platform source {name!r}; supported: {allowed}")


def assert_platform_metrics_scope_capability(provider: Provider) -> None:
    """Require that the platform Adapter's metrics :class:`ProviderMetrics` list platform scope.

    A provider selected for ``sources.platform`` must include :class:`MetricScope` ``PLATFORM`` in
    the Implementation's :attr:`ProviderMetrics.supported_scopes` before the runtime
    can serve the platform module.

    Args:
        provider: Registry-built platform :class:`Provider` Adapter for ``sources.platform``.

    Raises:
        RuntimeStartupError: If ``supported_scopes`` does not include ``MetricScope.PLATFORM``.
    """
    implementation = provider.metrics()
    if MetricScope.PLATFORM not in implementation.supported_scopes:
        found = ", ".join(sorted(s.value for s in implementation.supported_scopes))
        raise RuntimeStartupError(
            f"Platform source {provider.name!r} must advertise {MetricScope.PLATFORM.value!r} "
            f"in supported_scopes; found: {found or '(empty)'}"
        )


def build_platform_provider_bundle(settings: Settings) -> PlatformProviderBundle:
    """Construct the HTTP client and provider for :attr:`Settings.sources.platform`."""
    assert_supported_platform_source(settings)
    name = (settings.sources.platform or "").strip().lower()
    builder = _PLATFORM_SOURCE_BUILDERS[name]
    bundle = builder(settings)
    assert_platform_metrics_scope_capability(bundle.provider)
    return bundle
