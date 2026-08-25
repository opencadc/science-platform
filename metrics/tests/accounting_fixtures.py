"""Deterministic active-workload lifetime fixtures."""

from datetime import UTC, datetime, timedelta
from decimal import Decimal

from metrics.services.models import PodResourceLifetime, ResourceInterval

OBSERVED_AT = datetime(2026, 8, 24, 12, tzinfo=UTC)
SOURCE_REVISION = "1"
RETENTION_SECONDS = 2_592_000
USAGE_METRIC = "canfar_active_workload_usage_hours_total"
REQUESTED_METRIC = "canfar_active_workload_requested_hours_total"
COMPLETE_METRIC = "canfar_active_workload_accounting_complete"


def interval(
    start_minutes: int,
    end_minutes: int,
    usage: str,
    requested: str,
) -> ResourceInterval:
    """Build one interval relative to the common observation time."""
    return ResourceInterval(
        started_at=OBSERVED_AT + timedelta(minutes=start_minutes),
        ended_at=OBSERVED_AT + timedelta(minutes=end_minutes),
        usage_rate=Decimal(usage),
        requested_rate=Decimal(requested),
    )


ACTIVE_LIFETIMES = (
    PodResourceLifetime(
        pod_uid="pod-old",
        resource="cpu",
        running_since=OBSERVED_AT - timedelta(hours=2),
        observed_at=OBSERVED_AT,
        intervals=(
            interval(-120, -60, "0.5", "2"),
            interval(-60, 0, "1", "2"),
        ),
    ),
    PodResourceLifetime(
        pod_uid="pod-new",
        resource="cpu",
        running_since=OBSERVED_AT - timedelta(minutes=30),
        observed_at=OBSERVED_AT,
        intervals=(interval(-30, 0, "0.25", "1"),),
    ),
    PodResourceLifetime(
        pod_uid="pod-old",
        resource="memory",
        running_since=OBSERVED_AT - timedelta(hours=2),
        observed_at=OBSERVED_AT,
        intervals=(interval(-120, 0, "4", "8"),),
    ),
    PodResourceLifetime(
        pod_uid="pod-new",
        resource="memory",
        running_since=OBSERVED_AT - timedelta(minutes=30),
        observed_at=OBSERVED_AT,
        intervals=(interval(-30, 0, "2", "4"),),
    ),
    PodResourceLifetime(
        pod_uid="pod-old",
        resource="nvidia.com/gpu",
        running_since=OBSERVED_AT - timedelta(hours=2),
        observed_at=OBSERVED_AT,
        intervals=(interval(-120, 0, "0.5", "1"),),
    ),
)

PROMETHEUS_SERIES = (
    {
        "__name__": USAGE_METRIC,
        "cluster": "fixture",
        "namespace": "workloads",
        "pod_uid": "pod-old",
        "resource": "cpu",
        "canfar_username": "ada",
        "canfar_community": "science",
        "source_revision": SOURCE_REVISION,
        "unit": "core-hours",
        "timestamp": OBSERVED_AT.timestamp(),
        "value": "1.5",
    },
    {
        "__name__": REQUESTED_METRIC,
        "cluster": "fixture",
        "namespace": "workloads",
        "pod_uid": "pod-old",
        "resource": "cpu",
        "canfar_username": "ada",
        "canfar_community": "science",
        "source_revision": SOURCE_REVISION,
        "unit": "core-hours",
        "timestamp": OBSERVED_AT.timestamp(),
        "value": "4",
    },
    {
        "__name__": COMPLETE_METRIC,
        "cluster": "fixture",
        "namespace": "workloads",
        "pod_uid": "pod-old",
        "resource": "cpu",
        "canfar_username": "ada",
        "canfar_community": "science",
        "source_revision": SOURCE_REVISION,
        "unit": "boolean",
        "reason": "complete",
        "timestamp": OBSERVED_AT.timestamp(),
        "value": "1",
    },
)
