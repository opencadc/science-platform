# ADR-0002: Platform stats fail closed

## Status

Accepted

## Context

Clients rely on platform stats for capacity planning. Partial or stale figures
after upstream failure are worse than an explicit error.

## Decision

- Return **HTTP 503** when the Metrics backend is unreachable or session
  resource ceilings cannot be loaded. **No partial stats.**
- Client messages are fixed and short; log descriptive errors server-side only.
- **`MetricsDAO`** is constructed lazily on the stats code path so missing
  `SKAHA_METRICS_BACKEND_URL` does not break other session GET routes.

## Consequences

- Science Portal and other clients must handle 503 on platform stats separately
  from session list success.

## References

- [`../../../docs/adr/0001-platform-stats-integration-boundary.md`](../../../docs/adr/0001-platform-stats-integration-boundary.md)
- [`../../CONTEXT.md`](../../CONTEXT.md)
