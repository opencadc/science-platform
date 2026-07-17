# ADR-0011: Complete provider metrics without composition

## Status

Accepted (M4)

## Context

M3-era code composed partial Kueue and Prometheus fragments into user/session
responses. That pattern produced incomplete contracts and unclear ownership.

## Decision

- Each **metric scope** is served by **one provider** returning a **complete**
  internal model for that scope.
- `MetricsRuntime` orchestrates cache and HTTP clients; it does **not** stitch
  partial capacity/usage results across providers.
- Adding a scope requires config (`sources.*`), provider method, route, cache
  TTL, schemas, and tests together.

## Consequences

- `kube` is not an alternate `sources.platform`; it serves quota/workload scopes
  (ADR-0015). **`prometheus`** and **`kube`** may serve **UserMetrics** or
  **SessionMetrics** when configured as the sole provider for that scope
  (ADR-0020, ADR-0021)—each must return the complete contract alone.
- Removing a route means removing the provider method and source binding, not
  leaving stub handlers.

## References

- [`0005-metrics-runtime-composition-root.md`](0005-metrics-runtime-composition-root.md)
