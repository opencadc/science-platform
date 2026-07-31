from __future__ import annotations

from decimal import Decimal

import pytest

from metrics.quantity import (
    InvalidQuantityError,
    format_resource_amount,
    merge_resource_totals,
    parse_cpu_to_cores,
    parse_memory_to_gib,
    parse_resource_amount,
)


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        ("250m", Decimal("0.25")),
        ("0", Decimal("0")),
        ("1.5", Decimal("1.5")),
        ("1k", Decimal("1000")),
        ("1M", Decimal("1000000")),
        ("1E", Decimal("1000000000000000000")),
        ("1e3", Decimal("1000")),
        ("1E-3", Decimal("0.001")),
    ],
)
def test_parse_cpu_to_cores(raw: str, expected: Decimal) -> None:
    assert parse_cpu_to_cores(raw) == expected


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        ("512Mi", Decimal("0.5")),
        ("1.5Gi", Decimal("1.5")),
        ("1Ti", Decimal("1024")),
        ("1Ei", Decimal("1073741824")),
        ("1G", Decimal("0.931322574615478515625")),
    ],
)
def test_parse_memory_to_gib(raw: str, expected: Decimal) -> None:
    assert parse_memory_to_gib(raw) == expected


def test_format_cpu_always_uses_cores_not_millicores() -> None:
    """Capacity and allocated both use the same CPU unit (see docs/specs.md)."""
    assert format_resource_amount("cpu", Decimal("38")) == "38"
    assert format_resource_amount("cpu", Decimal("0.1")) == "0.1"
    assert format_resource_amount("cpu", Decimal("0")) == "0"
    assert format_resource_amount("cpu", Decimal("0.0005")) == "0.0005"
    assert format_resource_amount("cpu", Decimal("1.2300")) == "1.23"
    assert format_resource_amount("cpu", Decimal("1E+3")) == "1000"


def test_format_memory_uses_gi() -> None:
    assert format_resource_amount("memory", Decimal("88")) == "88Gi"
    assert format_resource_amount("memory", Decimal("0.097656")) == "0.097656Gi"


def test_extended_resources_use_same_quantity_parser() -> None:
    assert parse_resource_amount("nvidia.com/gpu", "1.5") == Decimal("1.5")
    assert parse_resource_amount("example.com/bandwidth", "1Mi") == Decimal("1048576")


def test_decimal_accumulation_is_exact() -> None:
    totals: dict[str, Decimal] = {}
    for _ in range(3):
        merge_resource_totals(totals, "cpu", parse_resource_amount("cpu", "0.1"))
    assert totals == {"cpu": Decimal("0.3")}
    assert format_resource_amount("cpu", totals["cpu"]) == "0.3"


def test_aggregate_and_format_overflow_are_rejected() -> None:
    maximum = Decimal(2**63 - 1)
    with pytest.raises(InvalidQuantityError):
        merge_resource_totals({"cpu": maximum}, "cpu", Decimal(1))
    with pytest.raises(InvalidQuantityError):
        format_resource_amount("cpu", maximum + 1)


@pytest.mark.parametrize("resource_name", ["memory", "ephemeral-storage"])
def test_storage_aggregate_overflow_is_checked_in_base_units(
    resource_name: str,
) -> None:
    totals: dict[str, Decimal] = {}
    five_exbi = parse_resource_amount(resource_name, "5Ei")
    merge_resource_totals(totals, resource_name, five_exbi)
    with pytest.raises(InvalidQuantityError):
        merge_resource_totals(totals, resource_name, five_exbi)


def test_empty_resource_name_is_not_aggregated() -> None:
    totals: dict[str, Decimal] = {}
    merge_resource_totals(totals, "", Decimal(1))
    assert totals == {}


@pytest.mark.parametrize(
    "raw",
    [
        None,
        "",
        "-1",
        "NaN",
        "Infinity",
        "1Zi",
        "1e",
        "9223372036854775808",
        "1e1000",
        " 1Gi ",
        1,
        Decimal("1"),
        0.1,
    ],
)
def test_invalid_quantities_are_rejected(raw: object) -> None:
    with pytest.raises(InvalidQuantityError):
        parse_resource_amount("nvidia.com/gpu", raw)
