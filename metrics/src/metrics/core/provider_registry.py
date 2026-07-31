"""Maps ``sources.platform`` to concrete provider construction (M4 seam)."""

from __future__ import annotations

from collections.abc import Callable

from metrics.core.settings import Settings
from metrics.errors import RuntimeStartupError
from metrics.providers.base import PlatformMetrics, Provider
from metrics.providers.kueue import KueueProvider, kueue_http_client


def _build_kueue_provider(settings: Settings) -> Provider:
    """Construct Kueue and transfer ownership of its HTTP client to it."""
    kueue_config = settings.providers.kueue
    client = kueue_http_client(kueue_config)
    return KueueProvider(settings, client)


_PLATFORM_SOURCE_BUILDERS: dict[str, Callable[[Settings], Provider]] = {
    "kueue": _build_kueue_provider,
}


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
    name = (settings.sources.platform or "").strip().lower()
    builder = _PLATFORM_SOURCE_BUILDERS.get(name)
    if builder is None:
        allowed = ", ".join(sorted(_PLATFORM_SOURCE_BUILDERS))
        raise RuntimeStartupError(f"Unsupported platform source {name!r}; supported: {allowed}")
    return builder(settings)
