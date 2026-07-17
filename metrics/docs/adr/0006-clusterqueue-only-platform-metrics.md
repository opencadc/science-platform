# ADR-0006: ClusterQueue-only platform metrics

## Status

Accepted

## Context

Kueue cohort configuration can tempt broader aggregation than operators configure
for CANFAR platform stats.

## Decision

- Platform metrics (`GET /api/v1/metrics/platform`) aggregate **configured
  ClusterQueues only**.
- **Cohort** is not part of provider configuration or capacity aggregation for
  this API.

## Consequences

- Operator queue lists in Helm/GitOps values define the platform metrics scope.

## References

- [`../../CONTEXT.md`](../../CONTEXT.md)
- `METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES`
