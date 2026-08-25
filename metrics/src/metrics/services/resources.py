"""Validate and integrate active-workload resource rates into resource-hours.

The functions are deliberately pure: producers supply timestamped intervals or
already integrated samples, and incomplete resources are omitted rather than
presented as misleading partial totals.
"""

from __future__ import annotations

from collections.abc import Iterable
from datetime import datetime, timedelta
from decimal import Decimal
from typing import Literal

from metrics.services.models import (
    ActiveWorkloadLifetime,
    LifetimeIssue,
    PodResourceLifetime,
    ResourceHours,
    ResourceInterval,
)

_HOUR_SECONDS = Decimal(3600)
RESOURCE_UNITS: dict[str, Literal["core-hours", "GiB-hours", "GPU-hours"]] = {
    "cpu": "core-hours",
    "memory": "GiB-hours",
    "nvidia.com/gpu": "GPU-hours",
}


def _hours(duration: timedelta) -> Decimal:
    """Convert a duration to decimal hours without float rounding.

    Args:
        duration: Exact interval duration.

    Returns:
        Duration expressed as decimal hours.
    """
    seconds = Decimal(duration.days * 86_400 + duration.seconds) + Decimal(
        duration.microseconds
    ) / Decimal(1_000_000)
    return seconds / _HOUR_SECONDS


def _validate_timestamp(value: datetime) -> None:
    """Require a timezone-aware lifetime timestamp.

    Args:
        value: Timestamp supplied by an accounting producer.

    Raises:
        ValueError: If the timestamp has no usable timezone.
    """
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("lifetime timestamps must include a timezone")


def _validate_interval(interval: ResourceInterval) -> None:
    """Validate interval ordering and non-negative finite rates.

    Args:
        interval: Constant-rate interval to validate.

    Raises:
        ValueError: If timestamps, duration, or rates are invalid.
    """
    _validate_timestamp(interval.started_at)
    _validate_timestamp(interval.ended_at)
    if interval.ended_at <= interval.started_at:
        raise ValueError("lifetime intervals must have positive duration")
    if any(
        not value.is_finite() or value < 0
        for value in (interval.usage_rate, interval.requested_rate)
    ):
        raise ValueError("resource rates must be finite and non-negative")


def _integrate_series(
    series: PodResourceLifetime,
) -> tuple[Decimal, Decimal, frozenset[LifetimeIssue]]:
    """Integrate one Pod-resource series and identify coverage gaps.

    Args:
        series: Intervals for one Pod UID and resource.

    Returns:
        Usage hours, requested hours, and all detected completeness issues.

    Raises:
        ValueError: If identities, timestamps, intervals, or rates are invalid.
    """
    _validate_timestamp(series.running_since)
    _validate_timestamp(series.observed_at)
    if not series.pod_uid or not series.resource:
        raise ValueError("Pod UID and resource name are required")
    if series.observed_at < series.running_since:
        raise ValueError("observation cannot precede the Pod Running time")

    usage = Decimal(0)
    requested = Decimal(0)
    issues = set(series.issues)
    cursor = series.running_since

    for interval in sorted(series.intervals, key=lambda item: item.started_at):
        _validate_interval(interval)
        if interval.started_at < series.running_since or interval.ended_at > series.observed_at:
            raise ValueError("interval lies outside the Pod Running lifetime")
        if interval.started_at < cursor:
            raise ValueError("lifetime intervals overlap")
        if interval.started_at > cursor:
            issues.add(LifetimeIssue.SAMPLING_GAP)
        duration = _hours(interval.ended_at - interval.started_at)
        usage += interval.usage_rate * duration
        requested += interval.requested_rate * duration
        cursor = interval.ended_at

    if cursor < series.observed_at:
        issues.add(
            LifetimeIssue.MISSING_SERIES if not series.intervals else LifetimeIssue.SAMPLING_GAP
        )
    return usage, requested, frozenset(issues)


def integrate_active_workload(
    lifetimes: Iterable[PodResourceLifetime],
) -> ActiveWorkloadLifetime:
    """Integrate and sum complete per-Pod Running lifetimes.

    A resource is excluded from totals when any Pod series for that resource is
    incomplete. Efficiency is intentionally left to the presentation layer.

    Args:
        lifetimes: Series for unique Pod UID and resource pairs, all observed at
            the same time.

    Returns:
        Complete resource-hour totals, issues, Pod identities, and coverage.

    Raises:
        ValueError: If a series is invalid, duplicated, unsupported, or observed
            at a different time.
    """
    totals: dict[str, tuple[Decimal, Decimal]] = {}
    incomplete: dict[str, set[LifetimeIssue]] = {}
    identities: set[tuple[str, str]] = set()
    observed_at: datetime | None = None

    for series in lifetimes:
        if series.resource not in RESOURCE_UNITS:
            raise ValueError(f"unsupported resource unit: {series.resource}")
        identity = (series.pod_uid, series.resource)
        if identity in identities:
            raise ValueError("duplicate Pod UID and resource series")
        identities.add(identity)
        if observed_at is None:
            observed_at = series.observed_at
        elif series.observed_at != observed_at:
            raise ValueError("all lifetime series must share one observation time")

        usage, requested, issues = _integrate_series(series)
        if issues:
            incomplete.setdefault(series.resource, set()).update(issues)
            continue
        old_usage, old_requested = totals.get(series.resource, (Decimal(0), Decimal(0)))
        totals[series.resource] = (old_usage + usage, old_requested + requested)

    resources = {
        resource: ResourceHours(
            unit=RESOURCE_UNITS[resource],
            usage=usage,
            requested=requested,
        )
        for resource, (usage, requested) in sorted(totals.items())
        if resource not in incomplete
    }
    return ActiveWorkloadLifetime(
        resources=resources,
        incomplete={resource: frozenset(issues) for resource, issues in sorted(incomplete.items())},
        pod_uids=frozenset(pod_uid for pod_uid, _resource in identities),
        coverage={
            resource: frozenset(pod_uid for pod_uid, current in identities if current == resource)
            for resource in sorted({resource for _pod_uid, resource in identities})
        },
    )


def aggregate_active_workload_hours(
    samples: Iterable[tuple[str, str, Decimal, Decimal, frozenset[LifetimeIssue]]],
) -> ActiveWorkloadLifetime:
    """Aggregate producer-integrated totals without publishing partial values.

    Args:
        samples: Pod UID, resource, usage hours, requested hours, and issues for
            each producer sample.

    Returns:
        Complete totals plus per-resource issues and Pod coverage.

    Raises:
        ValueError: If a Pod UID, resource, or resource-hour value is invalid.
    """
    totals: dict[str, tuple[Decimal, Decimal]] = {}
    incomplete: dict[str, set[LifetimeIssue]] = {}
    pod_uids: set[str] = set()
    coverage: dict[str, set[str]] = {}
    for pod_uid, resource, usage, requested, issues in samples:
        if not pod_uid:
            raise ValueError("Pod UID is required")
        pod_uids.add(pod_uid)
        coverage.setdefault(resource, set()).add(pod_uid)
        if resource not in RESOURCE_UNITS:
            raise ValueError(f"unsupported resource unit: {resource}")
        if any(not value.is_finite() or value < 0 for value in (usage, requested)):
            raise ValueError("resource hours must be finite and non-negative")
        if issues:
            incomplete.setdefault(resource, set()).update(issues)
            continue
        old_usage, old_requested = totals.get(resource, (Decimal(0), Decimal(0)))
        totals[resource] = (old_usage + usage, old_requested + requested)

    return ActiveWorkloadLifetime(
        resources={
            resource: ResourceHours(
                unit=RESOURCE_UNITS[resource],
                usage=usage,
                requested=requested,
            )
            for resource, (usage, requested) in sorted(totals.items())
            if resource not in incomplete
        },
        incomplete={resource: frozenset(issues) for resource, issues in sorted(incomplete.items())},
        pod_uids=frozenset(pod_uids),
        coverage={
            resource: frozenset(resource_pods)
            for resource, resource_pods in sorted(coverage.items())
        },
    )
