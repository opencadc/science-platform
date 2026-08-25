"""Active-workload lifetime accounting proof."""

from datetime import timedelta
from decimal import Decimal

import pytest

from metrics.services.models import LifetimeIssue, PodResourceLifetime
from metrics.services.resources import integrate_active_workload
from tests.accounting_fixtures import (
    ACTIVE_LIFETIMES,
    COMPLETE_METRIC,
    OBSERVED_AT,
    PROMETHEUS_SERIES,
    REQUESTED_METRIC,
    RETENTION_SECONDS,
    SOURCE_REVISION,
    USAGE_METRIC,
    interval,
)


def test_each_active_pod_uses_its_own_running_lifetime_and_units() -> None:
    result = integrate_active_workload(ACTIVE_LIFETIMES)

    assert result.ready
    assert result.resources["cpu"].unit == "core-hours"
    assert result.resources["cpu"].usage == Decimal("1.625")
    assert result.resources["cpu"].requested == Decimal("4.5")
    assert result.resources["memory"].unit == "GiB-hours"
    assert result.resources["memory"].usage == Decimal("9")
    assert result.resources["memory"].requested == Decimal("18")
    assert result.resources["nvidia.com/gpu"].unit == "GPU-hours"
    assert result.resources["nvidia.com/gpu"].usage == Decimal("1")
    assert result.resources["nvidia.com/gpu"].requested == Decimal("2")


def test_grouping_order_does_not_change_additive_totals() -> None:
    assert integrate_active_workload(ACTIVE_LIFETIMES) == integrate_active_workload(
        reversed(ACTIVE_LIFETIMES)
    )


def test_container_restart_and_recovered_counter_reset_keep_continuity() -> None:
    split_at_restart = PodResourceLifetime(
        pod_uid="same-pod-uid",
        resource="cpu",
        running_since=OBSERVED_AT - timedelta(hours=1),
        observed_at=OBSERVED_AT,
        intervals=(
            interval(-60, -40, "0.25", "1"),
            interval(-40, 0, "0.5", "1"),
        ),
    )

    total = integrate_active_workload((split_at_restart,))
    assert total.ready
    assert total.resources["cpu"].usage == Decimal("5") / Decimal("12")
    assert total.resources["cpu"].requested == Decimal("1")


def test_recreated_pod_does_not_inherit_disappeared_uid_lifetime() -> None:
    replacement = PodResourceLifetime(
        pod_uid="replacement-uid",
        resource="cpu",
        running_since=OBSERVED_AT - timedelta(minutes=15),
        observed_at=OBSERVED_AT,
        intervals=(interval(-15, 0, "1", "2"),),
    )

    total = integrate_active_workload((replacement,))
    assert total.resources["cpu"].usage == Decimal("0.25")
    assert total.resources["cpu"].requested == Decimal("0.5")


def test_recently_started_pod_has_complete_zero_lifetime() -> None:
    just_started = PodResourceLifetime(
        pod_uid="just-started",
        resource="cpu",
        running_since=OBSERVED_AT,
        observed_at=OBSERVED_AT,
    )

    total = integrate_active_workload((just_started,))
    assert total.ready
    assert total.resources["cpu"].usage == 0
    assert total.resources["cpu"].requested == 0


@pytest.mark.parametrize(
    "series",
    [
        PodResourceLifetime(
            pod_uid="missing",
            resource="cpu",
            running_since=OBSERVED_AT - timedelta(hours=1),
            observed_at=OBSERVED_AT,
        ),
        PodResourceLifetime(
            pod_uid="sample-gap",
            resource="cpu",
            running_since=OBSERVED_AT - timedelta(hours=1),
            observed_at=OBSERVED_AT,
            intervals=(interval(-60, -31, "1", "2"), interval(-29, 0, "1", "2")),
        ),
        PodResourceLifetime(
            pod_uid="scrape-gap",
            resource="cpu",
            running_since=OBSERVED_AT - timedelta(hours=1),
            observed_at=OBSERVED_AT,
            intervals=(interval(-60, 0, "1", "2"),),
            issues=frozenset({LifetimeIssue.SCRAPE_GAP}),
        ),
        PodResourceLifetime(
            pod_uid="unrecovered-reset",
            resource="cpu",
            running_since=OBSERVED_AT - timedelta(hours=1),
            observed_at=OBSERVED_AT,
            intervals=(interval(-60, 0, "1", "2"),),
            issues=frozenset({LifetimeIssue.COUNTER_RESET}),
        ),
        PodResourceLifetime(
            pod_uid="lost-checkpoint",
            resource="cpu",
            running_since=OBSERVED_AT - timedelta(hours=1),
            observed_at=OBSERVED_AT,
            intervals=(interval(-60, 0, "1", "2"),),
            issues=frozenset({LifetimeIssue.PROCESS_RESTART}),
        ),
        PodResourceLifetime(
            pod_uid="disappeared-during-read",
            resource="cpu",
            running_since=OBSERVED_AT - timedelta(hours=1),
            observed_at=OBSERVED_AT,
            intervals=(interval(-60, 0, "1", "2"),),
            issues=frozenset({LifetimeIssue.POD_DISAPPEARED}),
        ),
        PodResourceLifetime(
            pod_uid="corrupt-checkpoint",
            resource="cpu",
            running_since=OBSERVED_AT - timedelta(hours=1),
            observed_at=OBSERVED_AT,
            intervals=(interval(-60, 0, "1", "2"),),
            issues=frozenset({LifetimeIssue.CORRUPT_STATE}),
        ),
    ],
    ids=[
        "missing-series",
        "sampling-gap",
        "scrape-gap",
        "counter-reset",
        "process-restart",
        "pod-disappeared",
        "corrupt-state",
    ],
)
def test_incomplete_series_omits_resource_and_prevents_readiness(
    series: PodResourceLifetime,
) -> None:
    total = integrate_active_workload((series,))
    assert not total.ready
    assert "cpu" not in total.resources
    assert total.incomplete["cpu"]


def test_one_incomplete_pod_prevents_partial_resource_total() -> None:
    complete_cpu = ACTIVE_LIFETIMES[0]
    missing_cpu = PodResourceLifetime(
        pod_uid="missing",
        resource="cpu",
        running_since=OBSERVED_AT - timedelta(minutes=30),
        observed_at=OBSERVED_AT,
    )

    total = integrate_active_workload((complete_cpu, missing_cpu))
    assert "cpu" not in total.resources
    assert total.incomplete["cpu"] == frozenset({LifetimeIssue.MISSING_SERIES})


def test_series_contract_has_bounded_identity_and_continuity_signal() -> None:
    assert {series["__name__"] for series in PROMETHEUS_SERIES} == {
        USAGE_METRIC,
        REQUESTED_METRIC,
        COMPLETE_METRIC,
    }
    identity = {
        "canfar_community",
        "canfar_username",
        "cluster",
        "namespace",
        "pod_uid",
        "resource",
        "source_revision",
    }
    assert all(identity <= series.keys() for series in PROMETHEUS_SERIES)
    assert all(series["pod_uid"] == "pod-old" for series in PROMETHEUS_SERIES)
    assert all(series["source_revision"] == SOURCE_REVISION for series in PROMETHEUS_SERIES)
    assert all(series["timestamp"] == OBSERVED_AT.timestamp() for series in PROMETHEUS_SERIES)
    assert (
        len(
            {
                (series["__name__"], *(series[key] for key in sorted(identity)))
                for series in PROMETHEUS_SERIES
            }
        )
        == 3
    )
    assert [series["unit"] for series in PROMETHEUS_SERIES] == [
        "core-hours",
        "core-hours",
        "boolean",
    ]
    assert PROMETHEUS_SERIES[-1]["reason"] == "complete"
    assert PROMETHEUS_SERIES[-1]["value"] == "1"
    assert RETENTION_SECONDS == 30 * 24 * 60 * 60


@pytest.mark.parametrize(
    "series",
    [
        PodResourceLifetime(
            pod_uid="before-running",
            resource="cpu",
            running_since=OBSERVED_AT - timedelta(minutes=30),
            observed_at=OBSERVED_AT,
            intervals=(interval(-31, 0, "1", "1"),),
        ),
        PodResourceLifetime(
            pod_uid="after-observation",
            resource="cpu",
            running_since=OBSERVED_AT - timedelta(minutes=30),
            observed_at=OBSERVED_AT,
            intervals=(interval(-30, 1, "1", "1"),),
        ),
    ],
)
def test_interval_outside_own_running_lifetime_is_rejected(
    series: PodResourceLifetime,
) -> None:
    with pytest.raises(ValueError, match="outside"):
        integrate_active_workload((series,))


def test_duplicate_pod_resource_series_violates_cardinality() -> None:
    with pytest.raises(ValueError, match="duplicate"):
        integrate_active_workload((ACTIVE_LIFETIMES[0], ACTIVE_LIFETIMES[0]))
