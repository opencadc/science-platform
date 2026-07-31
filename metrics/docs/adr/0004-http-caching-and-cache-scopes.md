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
- Per-scope TTLs live in `CacheConfig` (`cache.scope_ttl_seconds`); the
  platform scope may override the global default (300s typical) and uses
  shared cache semantics.
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
