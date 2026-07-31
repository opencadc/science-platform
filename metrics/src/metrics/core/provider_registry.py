"""Maps ``sources.platform`` to concrete provider construction (M4 seam)."""

from __future__ import annotations

from collections.abc import Callable

from metrics.core.settings import Settings
from metrics.errors import RuntimeStartupError
from metrics.providers.base import PlatformMetrics, Provider


def _build_kueue_provider(settings: Settings) -> Provider:
    """Construct Kueue and transfer ownership of its HTTP client to it."""
    from metrics.providers.kueue import KueueProvider, kueue_http_client

    kueue_config = settings.providers.kueue
    client = kueue_http_client(kueue_config)
    return KueueProvider(settings, client)


_PLATFORM_SOURCE_BUILDERS: dict[str, Callable[[Settings], Provider]] = {
    "kueue": _build_kueue_provider,
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


def bind_platform_metrics(provider: Provider) -> PlatformMetrics:
    """Bind a selected provider to the platform capability.

    Args:
        provider: Provider selected by ``sources.platform``.

    Returns:
        The provider narrowed to the platform read interface.

    Raises:
        RuntimeStartupError: If the provider cannot read platform metrics.
    """
    if not isinstance(provider, PlatformMetrics):
        raise RuntimeStartupError(
            f"Platform source {provider.name!r} does not provide platform metrics"
        )
    return provider


def build_platform_provider(settings: Settings) -> Provider:
    """Construct the provider selected by :attr:`Settings.sources.platform`."""
    assert_supported_platform_source(settings)
    name = (settings.sources.platform or "").strip().lower()
    builder = _PLATFORM_SOURCE_BUILDERS[name]
    return builder(settings)
