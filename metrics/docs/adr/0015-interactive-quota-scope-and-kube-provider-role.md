# ADR-0015: Interactive quota scope and kube provider role

## Status

Proposed (M5 — not yet implemented)

## Context

The `kube` provider package exists for typed configuration before M5. Platform
metrics must remain Kueue-backed; quota reads come from live Pod state.

## Decision

- **`sources.quotas.interactive: kube`** binds interactive quota to the kube
  provider—not to `sources.platform`.
- Scope id: **`quotas.interactive`** (`MetricScope.INTERACTIVE_QUOTA`).
- Provider method: complete **`interactive_quota(user)`** model; no platform
  aggregation in kube.

## Consequences

- RBAC must allow **list/get Pods** in configured namespaces for quota, separate
  from Kueue ClusterQueue access for platform metrics.
- Helm values must configure both Kueue platform source and kube quota source
  when interactive quota is enabled.

## References

- [`0011-complete-provider-metrics-without-composition.md`](0011-complete-provider-metrics-without-composition.md)
