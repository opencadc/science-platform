# ADR-0004: HTTP caching via response headers

## Status

Accepted

## Context

Shared caches and clients expect standard HTTP semantics. Embedding cache metadata
in JSON couples API shape to infrastructure concerns.

## Decision

- Communicate cache behavior for platform responses with HTTP headers
  (`Cache-Control`, `Date`, `Expires`, `Last-Modified`).
- Do **not** embed cache TTL or snapshot metadata in JSON bodies for shared
  cacheable resources.
- Per-scope TTLs live in `CacheConfig` (`cache.scope_ttl_seconds`); platform
  scope may override the default.

## Consequences

- Integration tests should assert headers, not JSON cache fields.

## References

- [`../specs.md`](../specs.md)
