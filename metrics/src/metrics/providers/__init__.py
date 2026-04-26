"""Convenience exports for Kueue and Prometheus provider entry points.

Eagerly importing Adapters here used to form a cycle with
``metrics.core`` (factory/runtime/registry). Locality is improved by
lazy :func:`__getattr__` resolution so ``import metrics.providers.base`` only loads
base / schema modules.
"""

from __future__ import annotations

import importlib
from typing import Any

__all__ = [
    "KueueProvider",
    "PrometheusProvider",
]


def __getattr__(name: str) -> Any:
    """Lazily load a concrete :class:`Provider` Adapter to avoid import cycles.

    Args:
        name: ``KueueProvider`` or ``PrometheusProvider``.

    Returns:
        The provider class.

    Raises:
        AttributeError: If ``name`` is not exported.
    """
    if name == "KueueProvider":
        m = importlib.import_module("metrics.providers.kueue")
        return m.KueueProvider
    if name == "PrometheusProvider":
        m = importlib.import_module("metrics.providers.prometheus")
        return m.PrometheusProvider
    msg = f"module {__name__!r} has no attribute {name!r}"
    raise AttributeError(msg)


def __dir__() -> list[str]:
    """List only the lazy public :data:`__all__` API (no import implementation names)."""
    return sorted(__all__)
