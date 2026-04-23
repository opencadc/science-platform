from __future__ import annotations

import logging

import pytest

from metrics.quantity import (
    format_resource_amount,
    parse_cpu_to_cores,
    parse_memory_to_gib,
    parse_resource_amount,
)


def test_parse_cpu_to_cores() -> None:
    assert parse_cpu_to_cores("500m") == 0.5
    assert parse_cpu_to_cores("2") == 2.0
    assert parse_cpu_to_cores(4) == 4.0
    assert parse_cpu_to_cores(None) == 0.0


def test_parse_memory_to_gib() -> None:
    assert parse_memory_to_gib("1024Mi") == 1.0
    assert parse_memory_to_gib("1Gi") == 1.0
    assert round(parse_memory_to_gib("1G"), 3) == 0.931
    assert parse_memory_to_gib(1024**3) == 1.0
    assert parse_memory_to_gib(None) == 0.0


def test_format_cpu_always_uses_cores_not_millicores() -> None:
    """Capacity and allocated both use the same CPU unit (see docs/specs.md)."""
    assert format_resource_amount("cpu", 38.0) == "38"
    assert format_resource_amount("cpu", 0.1) == "0.1"
    assert format_resource_amount("cpu", 0.0) == "0"
    # Would have been "100m" with millicore formatting — compare-friendly with "38"
    assert format_resource_amount("cpu", 0.0005) == "0.0005"


def test_format_memory_uses_gi() -> None:
    assert format_resource_amount("memory", 88.0) == "88Gi"
    assert format_resource_amount("memory", 0.097656) == "0.097656Gi"


def test_parse_resource_amount_logs_warning_for_bad_generic_quantity(
    caplog: pytest.LogCaptureFixture,
) -> None:
    caplog.set_level(logging.WARNING, logger="metrics.quantity")
    assert parse_resource_amount("nvidia.com/gpu", "not-a-number") == 0.0
    messages = [r.getMessage() for r in caplog.records]
    assert any("not-a-number" in m and "nvidia.com/gpu" in m for m in messages)
