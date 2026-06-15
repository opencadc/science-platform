# ADR-0017: Private cache for user-scoped metrics

## Status

Proposed (M5 — not yet implemented)

## Context

Platform responses use shared HTTP caching (ADR-0004). User-scoped quota must not
populate shared caches or leak one user's data to another.

## Decision

- User/quota responses use **`Cache-Control: private`** and short TTL (default
  **2 seconds** for `quotas.interactive` via `cache.scope_ttl_seconds`).
- Internal cache keys include scope, provider fingerprint, and a **hashed user**
  segment.
- Platform scope TTL defaults remain longer (for example 300s) and may use
  shared cache semantics.

## Consequences

- Extends ADR-0004: header policy is scope-specific, not one-size-fits-all.
- M6/M7 user and session scopes inherit private-cache rules when implemented.

## References

- [`0004-http-caching-via-headers.md`](0004-http-caching-via-headers.md)
