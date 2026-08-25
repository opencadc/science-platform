"""HTTP metadata describing internal Metrics snapshots."""

from __future__ import annotations

from datetime import UTC, datetime
from email.utils import format_datetime


def _ensure_utc(dt: datetime) -> datetime:
    if dt.tzinfo is None:
        return dt.replace(tzinfo=UTC)
    return dt.astimezone(UTC)


def http_date(dt: datetime) -> str:
    """Format ``dt`` as an HTTP-date (IMF-fixdate) in GMT."""
    return format_datetime(_ensure_utc(dt), usegmt=True)


def remaining_freshness_seconds(
    snapshot_created: datetime,
    configured_ttl: int,
    *,
    now: datetime,
) -> int:
    """Seconds of freshness left given snapshot time and configured TTL."""
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
    """Build no-store, age, modification, and RFC 9211 cache-status headers."""
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
