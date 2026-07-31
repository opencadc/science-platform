# ADR-0004: HTTP caching via headers; shared platform, private user scopes

## Status

Accepted (M2/M4); user-scope rules proposed with M5.
Consolidates ADR-0004 (HTTP caching via headers) and ADR-0017 (private cache
for user-scoped metrics).

## Context

Shared caches and clients expect standard HTTP semantics; embedding cache
metadata in JSON couples API shape to infrastructure. User-scoped responses
additionally must not populate shared caches or leak one user's data to
another.

## Decision

- Cache behavior is communicated with HTTP headers (`Cache-Control`, `Date`,
  `Expires`, `Last-Modified`); JSON bodies carry **no** TTL or snapshot
  metadata for cacheable resources.
- The platform scope uses the global `cache.ttl_seconds` (300s typical) with
  shared cache semantics. Per-scope TTL overrides were removed while platform
  is the only scope; they return with the first user-scoped cache (M5).
- User-scoped responses (quota in M5, user/session metrics in M6/M7) use
  **`Cache-Control: private`** with short TTLs (2s default for
  `quotas.interactive`). Internal cache keys include scope, provider
  fingerprint, and a **hashed user** segment.

## Consequences

- Integration tests assert headers, not JSON cache fields.
- Header policy is scope-specific: adding a scope means choosing its cache
  class (shared vs private) and TTL explicitly.

## References

- [`../specs.md`](../specs.md)
