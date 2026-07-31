# ADR-0015: Interactive quota — scope, API contract, list-on-request reads

## Status

Proposed (M5 — not yet implemented).
Consolidates ADR-0015 (quota scope and kube provider role), ADR-0016
(InteractiveQuota API contract), and ADR-0018 (list-on-request Kubernetes
reads).

## Context

Interactive session quota must be observable per user without exposing
namespace or label-selector mechanics in the public API. No `kube` provider
package exists before M5, and platform metrics must remain Kueue-backed.

## Decision

- **Scope and provider.** M5 may add `sources.quotas.interactive: kube` only
  with a complete kube quota provider (scope id `quotas.interactive`,
  provider method `interactive_quota(user)`); it must not alter
  `sources.platform` and does no platform aggregation.
- **API contract.** Route
  `GET /api/v1/metrics/users/{user}/quotas/interactive` returns
  `kind: InteractiveQuota` in the versioned envelope (`version`, `kind`,
  `metadata.created`, `status`, `data`) with **fixed** and **flexible**
  buckets. Each bucket reports the logical session count (distinct values of
  the one session-type label key per Pod; Pods with zero or multiple
  configured session label keys are skipped, not double-counted) and summed
  container `requests`/`limits` as open resource maps. `{user}` maps to the
  configured user label value; namespaces are not part of the public API.
- **Reads.** Quota is served via namespace-scoped Pod list + label selectors
  on each request, plus the private application cache (ADR-0004, 2s default
  TTL). Watch/informer indexes are deferred and must not change the public
  contract when introduced.

## Consequences

- RBAC must allow list/get Pods in configured namespaces, separate from Kueue
  ClusterQueue access; M5 Helm values configure both sources.
- Skaha (or session launch) must label Pods correctly (system ADR-0004);
  skipped or malformed Pods surface in provider telemetry, not API responses.
- Quota latency scales with Pod list size; the short cache TTL bounds repeat
  load.

## References

- [`0005-runtime-composition-and-provider-lifecycle.md`](0005-runtime-composition-and-provider-lifecycle.md)
- [`../../../docs/adr/0004-interactive-workload-pod-label-contract.md`](../../../docs/adr/0004-interactive-workload-pod-label-contract.md)
