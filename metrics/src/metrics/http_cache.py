"""HTTP caching helpers for metrics responses (Cache-Control, Date, Expires, Last-Modified)."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
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
    return max(0, int(configured_ttl - age_seconds))


def metrics_success_cache_headers(
    *,
    snapshot_created: datetime,
    configured_ttl: int,
    shared_cache_public: bool,
    user_scoped: bool,
    now: datetime | None = None,
) -> dict[str, str]:
    """Build Date, Cache-Control, Expires, and Last-Modified for a successful metrics GET.

    Platform metrics GETs are ``public`` when ``shared_cache_public`` is true and
    ``user_scoped`` is false; otherwise they are ``private``. When ``user_scoped``
    is true, responses stay ``private`` even if ``shared_cache_public`` is true, so
    user-scoped or other private snapshot policies never opt into shared public caches.

    * ``max-age`` is **remaining** freshness (configured TTL minus snapshot age).
    * When ``configured_ttl == 0``, sends ``Cache-Control: no-store``.
    """
    now = _ensure_utc(now or datetime.now(UTC))
    created = _ensure_utc(snapshot_created)

    headers: dict[str, str] = {
        "Date": http_date(now),
        "Last-Modified": http_date(created),
    }

    if configured_ttl <= 0:
        headers["Cache-Control"] = "no-store"
        return headers

    remaining = remaining_freshness_seconds(
        snapshot_created,
        configured_ttl,
        now=now,
    )
    visibility_public = shared_cache_public and not user_scoped
    vis = "public" if visibility_public else "private"
    headers["Cache-Control"] = f"{vis}, max-age={remaining}"
    expires_at = now + timedelta(seconds=remaining)
    headers["Expires"] = http_date(expires_at)
    return headers
