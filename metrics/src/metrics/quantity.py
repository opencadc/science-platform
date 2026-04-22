"""Parse and format Kubernetes-style resource quantities for metrics aggregation.

Functions here convert API strings (for example ``512Mi``, ``2500m`` CPU) into
floats suitable for summation, then back into stable string forms for JSON
responses. :func:`parse_resource_amount` / :func:`format_resource_amount` extend
CPU/memory handling to arbitrary resource names using simple numeric fallback
so GPU counts and vendor-specific resources can flow through the platform maps
without bespoke parsers for each type.
"""

from __future__ import annotations

import logging

logger = logging.getLogger(__name__)

BINARY_UNITS = {
    "Ki": 1024,
    "Mi": 1024**2,
    "Gi": 1024**3,
    "Ti": 1024**4,
    "Pi": 1024**5,
}

DECIMAL_UNITS = {
    "K": 1000,
    "M": 1000**2,
    "G": 1000**3,
    "T": 1000**4,
    "P": 1000**5,
}


def parse_cpu_to_cores(raw: str | int | float | None) -> float:
    """Parse a Kubernetes CPU quantity to cores."""
    if raw is None:
        return 0.0

    value = str(raw).strip()
    if not value:
        return 0.0

    if value.endswith("m"):
        return float(value[:-1]) / 1000.0

    return float(value)


def parse_memory_to_gib(raw: str | int | float | None) -> float:
    """Parse a Kubernetes memory quantity to GiB."""
    if raw is None:
        return 0.0

    value = str(raw).strip()
    if not value:
        return 0.0

    for unit, multiplier in BINARY_UNITS.items():
        if value.endswith(unit):
            return float(value[: -len(unit)]) * multiplier / (1024**3)

    for unit, multiplier in DECIMAL_UNITS.items():
        if value.endswith(unit):
            return float(value[: -len(unit)]) * multiplier / (1024**3)

    return float(value) / (1024**3)


def parse_resource_amount(resource_name: str, raw: str | int | float | None) -> float:
    """Parse a Kubernetes-style quantity to a float suitable for aggregation.

    ``cpu`` uses millicore semantics via :func:`parse_cpu_to_cores`; ``memory``
    and ``ephemeral-storage`` use binary SI via :func:`parse_memory_to_gib`. All
    other names use a trimmed string→float parse so integer-like resources
    (GPUs, custom counters) still aggregate sensibly.
    """
    if raw is None:
        return 0.0
    name = str(resource_name).lower()
    if name == "cpu":
        return parse_cpu_to_cores(raw)
    if name == "memory" or name == "ephemeral-storage":
        return parse_memory_to_gib(raw)
    value = str(raw).strip()
    if not value:
        return 0.0
    try:
        return float(value)
    except ValueError:
        logger.warning(
            "failed to parse quantity for resource %r raw=%r; treating as 0",
            resource_name,
            raw,
        )
        return 0.0


def format_resource_amount(resource_name: str, value: float) -> str:
    """Format an aggregated float back to a Kubernetes-friendly quantity string.

    Output shape matches common API conventions: fractional CPU as ``m`` suffix,
    memory as ``Gi`` binary strings, integral non-standard resources without
    trailing zeros.
    """
    name = str(resource_name).lower()
    if name == "cpu":
        if 0 < value < 1:
            return f"{max(int(round(value * 1000)), 1)}m"
        text = f"{value:.6f}".rstrip("0").rstrip(".")
        return text or "0"
    if name in ("memory", "ephemeral-storage"):
        text = f"{value:.6f}".rstrip("0").rstrip(".")
        return f"{text}Gi" if text else "0Gi"
    if abs(value - round(value)) < 1e-9:
        return str(int(round(value)))
    text = f"{value:.6f}".rstrip("0").rstrip(".")
    return text or "0"


def merge_resource_totals(target: dict[str, float], name: str, delta: float) -> None:
    """Accumulate ``delta`` into ``target[name]`` in place (skip zero deltas).

    Keys preserve API casing; callers that need case-insensitive aggregation
    should normalize names before calling this helper.
    """
    if not name or delta == 0.0:
        return
    target[name] = target.get(name, 0.0) + delta
