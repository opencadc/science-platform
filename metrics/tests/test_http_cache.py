from __future__ import annotations

import re
from datetime import UTC, datetime, timedelta

from metrics.http_cache import (
    http_date,
    metrics_success_cache_headers,
    remaining_freshness_seconds,
)


def _max_age(cache_control: str) -> int:
    m = re.search(r"max-age=(\d+)", cache_control.lower())
    assert m is not None
    return int(m.group(1))


def test_remaining_freshness_seconds() -> None:
    created = datetime(2026, 1, 1, 12, 0, 0, tzinfo=UTC)
    now = created + timedelta(seconds=10)
    assert remaining_freshness_seconds(created, 60, now=now) == 50
    assert remaining_freshness_seconds(created, 60, now=created) == 60
    assert remaining_freshness_seconds(created, 5, now=now) == 0


def test_metrics_success_cache_headers_platform_public() -> None:
    snap = datetime(2026, 1, 1, 12, 0, 0, tzinfo=UTC)
    now = snap
    h = metrics_success_cache_headers(
        snapshot_created=snap,
        configured_ttl=30,
        shared_cache_public=True,
        user_scoped=False,
        now=now,
    )
    assert "Date" in h and "Last-Modified" in h and "Expires" in h
    assert h["Last-Modified"] == http_date(snap)
    assert _max_age(h["Cache-Control"]) == 30
    assert "public" in h["Cache-Control"]


def test_metrics_success_cache_headers_user_private() -> None:
    snap = datetime(2026, 1, 1, 12, 0, 0, tzinfo=UTC)
    h = metrics_success_cache_headers(
        snapshot_created=snap,
        configured_ttl=30,
        shared_cache_public=True,
        user_scoped=True,
        now=snap,
    )
    assert "private" in h["Cache-Control"]


def test_zero_ttl_no_store() -> None:
    snap = datetime(2026, 1, 1, 12, 0, 0, tzinfo=UTC)
    h = metrics_success_cache_headers(
        snapshot_created=snap,
        configured_ttl=0,
        shared_cache_public=True,
        user_scoped=False,
        now=snap,
    )
    assert h["Cache-Control"] == "no-store"
    assert "Expires" not in h
