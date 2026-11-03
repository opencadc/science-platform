# ADR-0001: MetricsDAO single seam

## Status

Accepted

## Context

Skaha previously spread metrics access across multiple DAO types and direct
Kubernetes calls. Session handlers need one stable entry point as pod-usage
sources evolve.

## Decision

- **`MetricsDAO`** is the sole Skaha adapter for metrics: platform stats HTTP,
  future Metrics-backend pod usage, and (today) Kubernetes pod metrics API
  reads.
- Session-layer code calls **`MetricsDAO` only**; do not reference
  `PlatformMetricsDAO`, `PodMetricsDAO`, or legacy `SkahaMetricsDAO` in specs.
- Pod usage reads the Kubernetes metrics API through Skaha's Fabric8 client
  (`SkahaKubernetesClient`, the same in-cluster SA as `SessionDAO`); see
  [ADR-0005](0005-fabric8-kubernetes-client.md). Fetch code never builds its own
  client.
- Format legacy session quantity strings via **`ResourceQuantityFormatter`**;
  keep **`PodResourceUsage`** as the session-list DTO for `cpuCoresInUse` /
  `memoryInUse`.
- Prefer **nested records** on cohesive types (`PlatformMetrics`, `PodMetrics`)
  with focused DAO adapters; avoid `*Utils` god-classes.

## Consequences

- Switching pod usage from Kubernetes metrics API to the Metrics backend is a
  `MetricsDAO` configuration change, not a session-handler change.

## References

- [`../../CONTEXT.md`](../../CONTEXT.md)
- `org.opencadc.skaha.metrics.MetricsDAO`
