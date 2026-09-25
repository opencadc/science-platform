"""Translate internal snapshot freshness into standard HTTP cache metadata.

Metrics responses are never stored by intermediaries, but ``Age`` and
``Cache-Status`` still tell clients which internal snapshot produced a
response. The server's ``Date`` header comes from the ASGI server.
"""

from __future__ import annotations


def metrics_success_cache_headers(
    *,
    age_seconds: float,
    fresh_seconds: float,
    cached: bool,
    cache_available: bool,
) -> dict[str, str]:
    """Build cache metadata for a successful Metrics response.

    ``Cache-Status`` (RFC 9211) reports a reused snapshot as a ``hit`` and a
    snapshot filled for this request as ``fwd=uri-miss``. Its ``ttl`` is the
    remaining fresh time in whole seconds; a negative value marks a stale
    snapshot.

    Args:
        age_seconds: Snapshot age measured from the start of its fill.
        fresh_seconds: Fresh window of the snapshot's surface.
        cached: Whether a stored snapshot answered the request.
        cache_available: Whether Redis answered the lookup.

    Returns:
        HTTP response headers describing the snapshot and cache outcome.
    """
    ttl = int(fresh_seconds - age_seconds)
    if not cache_available:
        cache_status = f'metrics; hit; ttl={ttl}; detail="redis-unavailable"'
    elif cached:
        cache_status = f"metrics; hit; ttl={ttl}"
    else:
        cache_status = f"metrics; fwd=uri-miss; ttl={ttl}"
    return {
        "Cache-Control": "no-store",
        "Age": str(max(0, int(age_seconds))),
        "Cache-Status": cache_status,
    }
