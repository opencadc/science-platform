"""Parse and format Kubernetes resource quantities."""

from __future__ import annotations

import re
from decimal import Decimal, InvalidOperation, ROUND_UP, localcontext
from typing import Literal

from metrics.errors import ProviderExecutionError


_GIB = Decimal(2**30)
_NANO = Decimal("0.000000001")
_MAX_QUANTITY = Decimal(2**63 - 1)
_MIN_EXPONENT = -(2**31)
_MAX_EXPONENT = 2**31 - 1
_MAX_FORMATTED_AMOUNT_LENGTH = 4_096
_STORAGE_RESOURCES = frozenset({"memory", "ephemeral-storage"})
_INVALID_QUANTITY_MESSAGE = "Kubernetes resource data contained an invalid resource quantity"
_NUMBER = r"[0-9]+(?:\.[0-9]*)?|\.[0-9]+"
_QUANTITY_RE = re.compile(
    rf"^(?P<number>[+-]?(?:{_NUMBER}))"
    rf"(?P<suffix>Ki|Mi|Gi|Ti|Pi|Ei|[eE][+-]?[0-9]+|n|u|m|k|M|G|T|P|E)?$"
)
_DECIMAL_SUFFIXES = {
    "n": Decimal("0.000000001"),
    "u": Decimal("0.000001"),
    "m": Decimal("0.001"),
    "": Decimal(1),
    "k": Decimal(1000),
    "M": Decimal(1000**2),
    "G": Decimal(1000**3),
    "T": Decimal(1000**4),
    "P": Decimal(1000**5),
    "E": Decimal(1000**6),
}
_BINARY_SUFFIXES = {
    "Ki": Decimal(2**10),
    "Mi": Decimal(2**20),
    "Gi": Decimal(2**30),
    "Ti": Decimal(2**40),
    "Pi": Decimal(2**50),
    "Ei": Decimal(2**60),
}


def _as_decimal(value: object) -> Decimal:
    """Convert a provider quantity to Decimal without binary rounding."""
    if isinstance(value, bool):
        raise ProviderExecutionError(_INVALID_QUANTITY_MESSAGE)
    if isinstance(value, Decimal):
        result = value
    elif isinstance(value, int):
        result = Decimal(value)
    elif isinstance(value, float):
        result = Decimal(str(value))
    else:
        raise ProviderExecutionError(_INVALID_QUANTITY_MESSAGE)
    if not result.is_finite():
        raise ProviderExecutionError(_INVALID_QUANTITY_MESSAGE)
    return Decimal(0) if result.is_zero() else result


def parse_resource_amount(resource_name: str, raw: object) -> Decimal:
    """Parse one Kubernetes quantity into public units.

    CPU and extended resources are returned in base units. Memory and
    ephemeral-storage are returned in GiB. Kubernetes' nano precision is
    preserved by rounding non-zero values upward to one nanounit.
    """
    if not isinstance(raw, str) or not raw or raw != raw.strip() or len(raw) > 100:
        raise ProviderExecutionError(_INVALID_QUANTITY_MESSAGE)
    match = _QUANTITY_RE.fullmatch(raw)
    if match is None:
        raise ProviderExecutionError(_INVALID_QUANTITY_MESSAGE)
    try:
        number = Decimal(match.group("number"))
        suffix = match.group("suffix") or ""
        multiplier = _DECIMAL_SUFFIXES.get(suffix) or _BINARY_SUFFIXES.get(suffix)
        if multiplier is None:
            exponent = int(suffix[1:])
            if not _MIN_EXPONENT <= exponent <= _MAX_EXPONENT:
                raise ProviderExecutionError(_INVALID_QUANTITY_MESSAGE)
            value = Decimal(raw)
        else:
            with localcontext() as context:
                context.prec = max(128, len(number.as_tuple().digits) + 32)
                value = number * multiplier
    except (InvalidOperation, OverflowError, ValueError) as exc:
        raise ProviderExecutionError(_INVALID_QUANTITY_MESSAGE) from exc
    if not value.is_finite() or value < 0 or value > _MAX_QUANTITY:
        raise ProviderExecutionError(_INVALID_QUANTITY_MESSAGE)
    if not value.is_zero():
        with localcontext() as context:
            context.prec = max(128, len(value.as_tuple().digits) + 32)
            value = value.quantize(_NANO, rounding=ROUND_UP)
    if resource_name.lower() in _STORAGE_RESOURCES:
        with localcontext() as context:
            context.prec = max(64, len(value.as_tuple().digits) + 32)
            return value / _GIB
    return value


def _validate_resource_amount(resource_name: str, value: object) -> Decimal:
    """Validate one public-unit amount against Kubernetes bounds."""
    decimal_value = _as_decimal(value)
    with localcontext() as context:
        context.prec = max(64, len(decimal_value.as_tuple().digits) + 32)
        base_value = (
            decimal_value * _GIB if resource_name.lower() in _STORAGE_RESOURCES else decimal_value
        )
    if base_value < 0 or base_value > _MAX_QUANTITY:
        raise ProviderExecutionError(_INVALID_QUANTITY_MESSAGE)
    return decimal_value


def _plain_decimal(value: Decimal) -> str:
    """Render a bounded Decimal without exponent notation."""
    if value.is_zero():
        return "0"
    digits = len(value.as_tuple().digits)
    exponent = value.as_tuple().exponent
    if not isinstance(exponent, int):
        raise ProviderExecutionError(_INVALID_QUANTITY_MESSAGE)
    position = digits + exponent
    if exponent >= 0:
        plain_length = position
    elif position > 0:
        plain_length = digits + 1
    else:
        plain_length = 2 - position + digits
    if value.as_tuple().sign:
        plain_length += 1
    if plain_length > _MAX_FORMATTED_AMOUNT_LENGTH:
        raise ProviderExecutionError(_INVALID_QUANTITY_MESSAGE)
    text = format(value, "f")
    return text.rstrip("0").rstrip(".") if "." in text else text


def format_resource_amount(resource_name: str, value: object) -> str:
    """Format one public-unit amount for the Metrics response."""
    decimal_value = _validate_resource_amount(resource_name, value)
    text = _plain_decimal(decimal_value)
    return f"{text}Gi" if resource_name.lower() in _STORAGE_RESOURCES else text


def merge_resource_totals(target: dict[str, Decimal], name: str, delta: object) -> None:
    """Add one validated resource quantity to an aggregate map."""
    current = _as_decimal(target.get(name, Decimal(0)))
    increment = _as_decimal(delta)
    with localcontext() as context:
        context.prec = max(
            128,
            len(current.as_tuple().digits) + len(increment.as_tuple().digits) + 32,
        )
        total = current + increment
    target[name] = _validate_resource_amount(name, total)


RESOURCE_UNITS: dict[str, Literal["cores", "GiB", "units"]] = {
    "cpu": "cores",
    "memory": "GiB",
    "nvidia.com/gpu": "units",
}
