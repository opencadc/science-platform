# ADR-0001: Platform stats integration boundary

## Status

Accepted

## Context

Science Portal and legacy clients consume cluster-wide figures through Skaha
`GET /v1/session?view=stats`. After the Metrics migration, Skaha must not derive
cluster totals from node listing or pod aggregation.

## Decision

- Skaha **platform stats** sources **platform capacity** and **platform
  allocation** exclusively from the co-deployed Metrics API
  (`GET /api/v1/metrics/platform` via `SKAHA_METRICS_BACKEND_URL`).
- Skaha performs **no in-process cache** for platform stats; Metrics owns TTL
  and snapshot freshness.
- On successful platform stats, `lastUpdate` reflects Metrics
  `metadata.created`, not Skaha assembly time.
- When Metrics is unreachable or session ceilings cannot be loaded, platform
  stats returns **HTTP 503** (fail closed) with stable client messages:
  **"Platform statistics unavailable"** (Metrics) and **"Session resource limits
  unavailable"** (LimitRange). No partial stats.
- Instantiate `MetricsDAO` lazily on the stats path only so a missing
  `SKAHA_METRICS_BACKEND_URL` does not break unrelated session GET routes.

## Consequences

- Metrics availability directly affects platform stats only; other Skaha
  endpoints continue.
- Session lifecycle tests must not depend on Metrics except where platform stats
  is under test.

## References

- [`../../skaha/CONTEXT.md`](../../skaha/CONTEXT.md)
- [`../../metrics/CONTEXT.md`](../../metrics/CONTEXT.md)
- [`../../skaha/docs/adr/0002-platform-stats-fail-closed.md`](../../skaha/docs/adr/0002-platform-stats-fail-closed.md)
