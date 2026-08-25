"""Translate internal snapshot freshness into standard HTTP cache metadata.

Metrics responses are never stored by intermediaries, but dates, age, and
cache status still tell clients which internal snapshot produced a response.
"""

from __future__ import annotations

from datetime import UTC, datetime
from email.utils import format_datetime


def _ensure_utc(dt: datetime) -> datetime:
    """Normalize an aware or naive datetime to UTC.

    Args:
        dt: Timestamp to normalize. Naive values are interpreted as UTC.

    Returns:
        An aware UTC datetime.
    """
    if dt.tzinfo is None:
        return dt.replace(tzinfo=UTC)
    return dt.astimezone(UTC)


def http_date(dt: datetime) -> str:
    """Format a timestamp as an IMF-fixdate suitable for HTTP headers.

    Args:
        dt: Timestamp to format; naive values are interpreted as UTC.

    Returns:
        A GMT HTTP-date string.
    """
    return format_datetime(_ensure_utc(dt), usegmt=True)


def remaining_freshness_seconds(
    snapshot_created: datetime,
    configured_ttl: int,
    *,
    now: datetime,
) -> int:
    """Calculate whole seconds left in a snapshot's configured fresh period.

    Elapsed time before the snapshot is clamped to zero. An expired snapshot
    may therefore produce a negative value, which is useful in ``Cache-Status``.

    Args:
        snapshot_created: Time at which the source snapshot was collected.
        configured_ttl: Freshness lifetime in seconds.
        now: Clock value used to calculate age.

    Returns:
        Remaining whole freshness seconds, or zero when caching is disabled.
    """
    if configured_ttl <= 0:
        return 0
    created = _ensure_utc(snapshot_created)
    clock = _ensure_utc(now)
    age_seconds = max(0.0, (clock - created).total_seconds())
    return int(configured_ttl - age_seconds)


def metrics_success_cache_headers(
    *,
    snapshot_created: datetime,
    configured_ttl: int,
    cached: bool,
    stale: bool,
    cache_available: bool,
    now: datetime | None = None,
) -> dict[str, str]:
    """Build cache metadata for a successful Metrics response.

    The response remains ``no-store`` because the service owns snapshot
    caching. ``Cache-Status`` reports whether that internal snapshot was
    refreshed, reused, stale, or served while Redis was unavailable.

    Args:
        snapshot_created: Collection time of the returned snapshot.
        configured_ttl: Freshness lifetime configured for the subject.
        cached: Whether the coordinator reused a stored snapshot.
        stale: Whether the reused snapshot is beyond its fresh period.
        cache_available: Whether the required shared cache is reachable.
        now: Optional clock override, primarily for deterministic callers.

    Returns:
        HTTP response headers describing the snapshot and cache outcome.
    """
    now = _ensure_utc(now or datetime.now(UTC))
    created = _ensure_utc(snapshot_created)
    age = max(0, int((now - created).total_seconds()))
    ttl = remaining_freshness_seconds(snapshot_created, configured_ttl, now=now)
    if not cache_available:
        cache_status = f'metrics; hit; ttl={ttl}; detail="redis-unavailable"'
    elif stale or cached:
        cache_status = f"metrics; hit; ttl={ttl}"
    else:
        cache_status = f"metrics; fwd=uri-miss; ttl={ttl}"
    return {
        "Date": http_date(now),
        "Cache-Control": "no-store",
        "Last-Modified": http_date(created),
        "Age": str(age),
        "Cache-Status": cache_status,
    }
