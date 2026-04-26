"""Core composition: settings, application factory, and metrics runtime.

Package-level imports stay lazy so ``import metrics.core.settings`` and provider modules
do not eagerly load the factory or construct the module-level :class:`FastAPI` app in
``metrics.core.factory``.
"""

from __future__ import annotations

import importlib
from typing import Any

__all__ = [
    "MetricsRuntime",
    "RuntimeStartupError",
    "Settings",
    "create_app",
]


def __getattr__(name: str) -> Any:
    """Lazily resolve public exports to avoid import cycles and eager app construction.

    ``create_app`` loads only when accessed, keeping the API router and runtime off the
    import path of provider base types and registry Adapters (Locality for startup).

    Args:
        name: Attribute name requested on this package module.

    Returns:
        The resolved object for ``name``.

    Raises:
        AttributeError: If ``name`` is not a public re-export of this package.
    """
    if name == "create_app":
        m = importlib.import_module("metrics.core.factory")
        return m.create_app
    if name == "MetricsRuntime":
        m = importlib.import_module("metrics.core.runtime")
        return m.MetricsRuntime
    if name == "Settings":
        m = importlib.import_module("metrics.core.settings")
        return m.Settings
    if name == "RuntimeStartupError":
        m = importlib.import_module("metrics.errors")
        return m.RuntimeStartupError
    msg = f"module {__name__!r} has no attribute {name!r}"
    raise AttributeError(msg)


def __dir__() -> list[str]:
    """List only the lazy public :data:`__all__` API (no import implementation names)."""
    return sorted(__all__)
