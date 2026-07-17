# ADR-0021: SessionMetrics contract

## Status

Proposed (M7)

## Context

Session-scoped metrics require a complete contract and strict identity mapping.
M4 removed partial session routes until a provider could return the full model.

## Decision

- Introduce **`SessionMetrics`** as a versioned API contract
  (`kind: SessionMetrics`) with the same envelope shape as **PlatformMetrics**
  and **UserMetrics**.
- Route: `GET /api/v1/metrics/users/{user}/sessions/{uuid}` returns a **complete**
  `SessionMetrics` payload from **one** configured provider for the session
  scope—not stitched fragments.
- **`sources.sessions`** (exact key TBD at implementation) selects the provider
  (for example **`kube`** or **`prometheus`**) per ADR-0011.
- Session identity mapping and cardinality guardrails are enforced in the
  provider layer; `{uuid}` semantics are defined in M7 specs.
- User/session responses follow **private cache** rules (ADR-0017).
- When Skaha switches session-list pod usage from in-cluster Kubernetes metrics
  API to Metrics HTTP, **`MetricsDAO`** changes configuration only—call sites stay
  the same (system ADR-0001). SessionMetrics may back that path when the Metrics
  backend exposes session scope.

## Consequences

- Wire field details inside `data` are defined when M7 ships schemas.
- SessionMetrics is distinct from **UserMetrics** (ADR-0020) and
  **InteractiveQuota** (ADR-0016).

## References

- [`0020-user-metrics-attribution-contract.md`](0020-user-metrics-attribution-contract.md)
- [`0014-progressive-public-route-surface.md`](0014-progressive-public-route-surface.md)
- [`../../../docs/adr/0001-platform-stats-integration-boundary.md`](../../../docs/adr/0001-platform-stats-integration-boundary.md)
