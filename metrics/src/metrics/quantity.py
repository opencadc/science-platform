"""Helpers to parse Kubernetes resource quantities."""

from __future__ import annotations


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
