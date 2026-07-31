"""Exact parsing and formatting for Kubernetes resource quantities."""

from __future__ import annotations

import re
from decimal import (
    Context,
    Decimal,
    DecimalException,
    Inexact,
    InvalidOperation,
    Overflow,
    Rounded,
    Subnormal,
    Underflow,
    localcontext,
)

_MAX_QUANTITY = Decimal(2**63 - 1)
_ARITHMETIC_CONTEXT = Context(prec=200, Emax=999, Emin=-999)
for signal in (Inexact, InvalidOperation, Overflow, Rounded, Subnormal, Underflow):
    _ARITHMETIC_CONTEXT.traps[signal] = True

_QUANTITY_PATTERN = re.compile(
    r"(?P<number>[+]?(?:\d+(?:\.\d*)?|\.\d+))"
    r"(?P<suffix>Ki|Mi|Gi|Ti|Pi|Ei|[eE][+-]?\d+|[numkMGTPE]?)"
)
_DECIMAL_MULTIPLIERS = {
    "": Decimal(1),
    "n": Decimal("1e-9"),
    "u": Decimal("1e-6"),
    "m": Decimal("1e-3"),
    "k": Decimal("1e3"),
    "M": Decimal("1e6"),
    "G": Decimal("1e9"),
    "T": Decimal("1e12"),
    "P": Decimal("1e15"),
    "E": Decimal("1e18"),
}
_BINARY_MULTIPLIERS = {
    "Ki": Decimal(2**10),
    "Mi": Decimal(2**20),
    "Gi": Decimal(2**30),
    "Ti": Decimal(2**40),
    "Pi": Decimal(2**50),
    "Ei": Decimal(2**60),
}
_GIB = Decimal(2**30)


class InvalidQuantityError(ValueError):
    """Raised when upstream data is not a safe Kubernetes quantity."""


def _parse_quantity(raw: object) -> Decimal:
    if not isinstance(raw, str):
        raise InvalidQuantityError("invalid Kubernetes quantity")
    value = raw
    if not value or len(value) > 100:
        raise InvalidQuantityError("invalid Kubernetes quantity")
    match = _QUANTITY_PATTERN.fullmatch(value)
    if match is None:
        raise InvalidQuantityError("invalid Kubernetes quantity")

    suffix = match["suffix"]
    multiplier = _DECIMAL_MULTIPLIERS.get(suffix) or _BINARY_MULTIPLIERS.get(suffix)
    if multiplier is None:
        try:
            exponent = int(suffix[1:])
        except ValueError as exc:
            raise InvalidQuantityError("invalid Kubernetes quantity") from exc
        if not -999 <= exponent <= 999:
            raise InvalidQuantityError("invalid Kubernetes quantity")
        multiplier = Decimal(f"1e{exponent}")

    try:
        with localcontext(_ARITHMETIC_CONTEXT):
            amount = Decimal(match["number"]) * multiplier
    except DecimalException as exc:
        raise InvalidQuantityError("invalid Kubernetes quantity") from exc
    if not amount.is_finite() or amount < 0 or amount > _MAX_QUANTITY:
        raise InvalidQuantityError("invalid Kubernetes quantity")
    return amount


def _validate_resource_amount(resource_name: str, value: Decimal) -> None:
    try:
        with localcontext(_ARITHMETIC_CONTEXT):
            base_value = (
                value * _GIB if resource_name.lower() in ("memory", "ephemeral-storage") else value
            )
    except DecimalException as exc:
        raise InvalidQuantityError("invalid Kubernetes quantity") from exc
    if not base_value.is_finite() or base_value < 0 or base_value > _MAX_QUANTITY:
        raise InvalidQuantityError("invalid Kubernetes quantity")


def parse_cpu_to_cores(raw: object) -> Decimal:
    """Parse a Kubernetes CPU quantity as exact cores."""
    return _parse_quantity(raw)


def parse_memory_to_gib(raw: object) -> Decimal:
    """Parse a Kubernetes memory quantity as exact gibibytes."""
    try:
        with localcontext(_ARITHMETIC_CONTEXT):
            return _parse_quantity(raw) / _GIB
    except DecimalException as exc:
        raise InvalidQuantityError("invalid Kubernetes quantity") from exc


def parse_resource_amount(resource_name: str, raw: object) -> Decimal:
    """Parse any Kubernetes resource quantity into its public response unit."""
    name = resource_name.lower()
    if name == "cpu":
        return parse_cpu_to_cores(raw)
    if name in ("memory", "ephemeral-storage"):
        return parse_memory_to_gib(raw)
    return _parse_quantity(raw)


def format_resource_amount(resource_name: str, value: Decimal) -> str:
    """Format an exact resource total without scientific notation."""
    _validate_resource_amount(resource_name, value)
    text = format(value, "f")
    if "." in text:
        text = text.rstrip("0").rstrip(".")
    if resource_name.lower() in ("memory", "ephemeral-storage"):
        return f"{text}Gi"
    return text


def merge_resource_totals(
    target: dict[str, Decimal],
    name: str,
    delta: Decimal,
) -> None:
    """Accumulate an exact resource total while retaining valid zero values."""
    if not name:
        return
    try:
        with localcontext(_ARITHMETIC_CONTEXT):
            total = target.get(name, Decimal(0)) + delta
    except DecimalException as exc:
        raise InvalidQuantityError("invalid Kubernetes quantity") from exc
    _validate_resource_amount(name, total)
    target[name] = total
