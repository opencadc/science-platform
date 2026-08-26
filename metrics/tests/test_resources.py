"""Focused tests for Kubernetes resource quantity conversion."""

from __future__ import annotations

from decimal import Decimal

import pytest

from metrics.errors import ProviderExecutionError
from metrics.services.resources import (
    format_resource_amount,
    merge_resource_totals,
    parse_resource_amount,
)


@pytest.mark.parametrize(
    ("resource", "raw", "expected"),
    [
        ("cpu", "100m", Decimal("0.1")),
        ("cpu", "2", Decimal("2")),
        ("memory", "2Gi", Decimal("2")),
        ("memory", "512Mi", Decimal("0.5")),
        ("ephemeral-storage", "1Gi", Decimal("1")),
        ("nvidia.com/gpu", "2", Decimal("2")),
    ],
)
def test_parse_resource_amount(resource: str, raw: str, expected: Decimal) -> None:
    """Parse CPU, storage, and extended-resource quantities exactly."""
    assert parse_resource_amount(resource, raw) == expected


@pytest.mark.parametrize("raw", ["", "wat", "-1", "1 Gi", "NaN", "1e999999"])
def test_parse_resource_amount_rejects_invalid_values(raw: str) -> None:
    """Reject malformed or negative Kubernetes quantities."""
    with pytest.raises(ProviderExecutionError):
        parse_resource_amount("cpu", raw)


def test_format_resource_amount_uses_public_units() -> None:
    """Format storage with Gi and other resources as plain decimals."""
    assert format_resource_amount("cpu", Decimal("1.500")) == "1.5"
    assert format_resource_amount("memory", Decimal("2")) == "2Gi"


def test_merge_resource_totals_retains_zeroes_and_adds_exactly() -> None:
    """Aggregate values without floating-point drift."""
    totals: dict[str, Decimal] = {}
    merge_resource_totals(totals, "cpu", Decimal("0.1"))
    merge_resource_totals(totals, "cpu", Decimal("0.2"))
    merge_resource_totals(totals, "memory", Decimal(0))
    assert totals == {"cpu": Decimal("0.3"), "memory": Decimal(0)}
