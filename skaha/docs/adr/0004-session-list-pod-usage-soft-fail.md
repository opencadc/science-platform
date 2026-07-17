# ADR-0004: Session-list pod usage soft fail

## Status

Accepted

## Context

Session listing must remain usable when per-pod metrics are temporarily
unavailable.

## Decision

- On Kubernetes metrics API failure when loading pod usage, **soft-fail**: warn
  log, return `PodResourceUsage.empty()`, session list still succeeds.
- Platform stats remains **fail closed** (503) per ADR-0002; do not apply
  soft-fail semantics to platform stats.

## Consequences

- Session list may show empty usage fields during metrics API outages; platform
  stats endpoint returns 503 instead.

## References

- [`0002-platform-stats-fail-closed.md`](0002-platform-stats-fail-closed.md)
