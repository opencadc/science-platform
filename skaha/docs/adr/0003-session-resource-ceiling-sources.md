# ADR-0003: Session resource ceiling sources

## Status

Accepted

## Context

Platform stats exposes per-session maximums (`maxCPUCores`, `maxRAM`) alongside
cluster totals. Those ceilings must not be confused with cluster capacity.

## Decision

- When Helm `deployment.skaha.sessions.limitRange.enabled` is **true**, ceilings
  come from `limitRange.spec.max` via `LimitRangeResourceContext`.
- When LimitRange is **false**, ceilings come from **`skaha-config/k8s-resources.json`
  `defaultLimit`** values via `ResourceContexts` (shipped chart: 8 cores, 32 GiB),
  **not** from node capacity or Metrics platform capacity.
- **Session resource ceiling** is independent of **platform capacity**.

## Consequences

- Metrics platform capacity must never be used to populate session max fields.

## References

- [`../../CONTEXT.md`](../../CONTEXT.md)
