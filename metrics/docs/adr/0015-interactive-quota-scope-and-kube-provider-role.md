# ADR-0015: Interactive quota scope and kube provider role

## Status

Proposed (M5 — not yet implemented)

## Context

No `kube` provider package or configuration exists before M5. Platform metrics
must remain Kueue-backed; a future quota implementation may read live Pod
state.

## Decision

- M5 may add **`sources.quotas.interactive: kube`** only with a complete kube
  quota provider; it must not alter `sources.platform`.
- Scope id: **`quotas.interactive`** (`MetricScope.INTERACTIVE_QUOTA`).
- Provider method: complete **`interactive_quota(user)`** model; no platform
  aggregation in kube.

## Consequences

- RBAC must allow **list/get Pods** in configured namespaces for quota, separate
  from Kueue ClusterQueue access for platform metrics.
- M5 Helm values must configure both Kueue platform source and kube quota
  source when interactive quota ships.

## References

- [`0011-complete-provider-metrics-without-composition.md`](0011-complete-provider-metrics-without-composition.md)
