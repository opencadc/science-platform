"""Focused tests for the queue-only public response contract."""

from __future__ import annotations

from datetime import UTC, datetime

import pytest
from pydantic import ValidationError

from metrics.schemas.metrics import Metrics


OBSERVED = datetime(2026, 1, 1, tzinfo=UTC)


def _conditions() -> list[dict[str, object]]:
    """Return valid Ready/Cached conditions."""
    timestamp = OBSERVED.isoformat().replace("+00:00", "Z")
    return [
        {"type": "Ready", "status": "True", "reason": "Available", "lastTransitionTime": timestamp},
        {
            "type": "Cached",
            "status": "False",
            "reason": "Refreshed",
            "lastTransitionTime": timestamp,
        },
    ]


def _envelope(spec: dict[str, str], status: dict[str, object]) -> dict[str, object]:
    """Build one wire envelope."""
    return {
        "apiVersion": "canfar.net/v1alpha1",
        "kind": "Metrics",
        "metadata": {"name": "metrics-test"},
        "spec": spec,
        "status": {"observedAt": OBSERVED, "reservingWorkloads": 1, **status},
    }


def test_user_contract_contains_reservations_and_no_lifetime_fields() -> None:
    """User reports expose queue state and optional efficiency only."""
    report = Metrics.model_validate(
        _envelope(
            {"user": "bob"},
            {
                "resources": [{"name": "cpu", "requests": "1", "efficiency": "0.5"}],
                "conditions": _conditions(),
            },
        )
    )
    payload = report.model_dump(mode="json", by_alias=True, exclude_none=True)
    assert payload["status"]["reservingWorkloads"] == 1
    assert "runningPods" not in payload["status"]
    assert "accountingPeriod" not in payload["status"]
    assert "usageHours" not in payload["status"]["resources"][0]
    assert "requestedHours" not in payload["status"]["resources"][0]


def test_platform_contract_allows_efficiency_with_capacity_and_allocation() -> None:
    """Platform reports may carry attributed CPU/memory efficiency."""
    report = Metrics.model_validate(
        _envelope(
            {"platform": "canfar"},
            {
                "resources": [
                    {"name": "cpu", "capacity": "4", "allocated": "2", "efficiency": "0.5"}
                ],
                "conditions": _conditions(),
            },
        )
    )
    assert report.status.resources[0].efficiency == "0.5"


def test_efficiency_is_limited_to_cpu_and_memory() -> None:
    """The public efficiency field follows the transport-neutral seam."""
    with pytest.raises(ValidationError):
        Metrics.model_validate(
            _envelope(
                {"user": "bob"},
                {
                    "resources": [{"name": "nvidia.com/gpu", "requests": "1", "efficiency": "0.5"}],
                    "conditions": _conditions(),
                },
            )
        )


@pytest.mark.parametrize(
    "resource",
    [
        {"name": "cpu", "requests": "1", "usageHours": "1"},
        {"name": "cpu", "requests": "1", "requestedHours": "1"},
        {"name": "cpu", "requests": "1", "runningPods": 1},
        {"name": "cpu", "requests": "1", "accountingPeriod": "ActiveWorkloadLifetime"},
    ],
)
def test_removed_lifetime_fields_are_forbidden(resource: dict[str, object]) -> None:
    """Accounting and Pod fields cannot cross the strict wire boundary."""
    with pytest.raises(ValidationError):
        Metrics.model_validate(
            _envelope(
                {"user": "bob"},
                {"resources": [resource], "conditions": _conditions()},
            )
        )


def test_user_contract_omits_usage_field() -> None:
    """User reports do not expose Session-only usage."""
    with pytest.raises(ValidationError):
        Metrics.model_validate(
            _envelope(
                {"user": "bob"},
                {
                    "resources": [{"name": "cpu", "requests": "1", "usage": "0.5"}],
                    "conditions": _conditions(),
                },
            )
        )


def test_conditions_are_exactly_ready_and_cached() -> None:
    """No third condition or accounting-specific reason is allowed."""
    invalid = _conditions() + [
        {
            "type": "Ready",
            "status": "False",
            "reason": "PartialData",
            "lastTransitionTime": OBSERVED.isoformat(),
        }
    ]
    with pytest.raises(ValidationError):
        Metrics.model_validate(
            _envelope(
                {"user": "bob"},
                {"resources": [{"name": "cpu", "requests": "1"}], "conditions": invalid},
            )
        )
