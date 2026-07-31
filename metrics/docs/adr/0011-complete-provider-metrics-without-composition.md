# ADR-0011: Complete provider metrics without composition

## Status

Accepted (M4)

## Context

M3-era code composed partial Kueue and Prometheus fragments into user/session
responses. That pattern produced incomplete contracts and unclear ownership.

## Decision

- Each **metric scope** is served by **one provider** returning a **complete**
  internal model for that scope.
- `MetricsRuntime` orchestrates provider lifecycle and cache resources; it does
  **not** stitch partial capacity/usage results across providers. Providers own
  their injected clients per ADR-0022.
- Adding a scope requires config (`sources.*`), provider method, route, cache
  TTL, schemas, and tests together.

## Consequences

- Proposed provider/scope examples in ADRs 0015–0018 and 0020–0021 are not
  runtime configuration. A future scope can ship only with one complete
  provider implementation.
- Removing a route means removing the provider method and source binding, not
  leaving stub handlers.

## References

- [`0005-metrics-runtime-composition-root.md`](0005-metrics-runtime-composition-root.md)
