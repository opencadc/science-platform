# ADR-0020: UserMetrics and SessionMetrics contracts

## Status

Proposed (M6 UserMetrics, M7 SessionMetrics).
Consolidates ADR-0020 (UserMetrics contract) and ADR-0021 (SessionMetrics
contract).

## Context

M4 removed partial user/session routes until a provider could return complete
scope models. M6/M7 reintroduce them as first-class contracts parallel to
**PlatformMetrics**, not as composed fragments from multiple sources.

## Decision

- **UserMetrics (M6).** `kind: UserMetrics`, route
  `GET /api/v1/metrics/users/{user}`, versioned envelope (`version`, `kind`,
  `metadata.created`, `status`, `data`). `sources.users` (exact key TBD)
  selects **one** provider (for example `kube` or `prometheus`) that returns
  the complete model; the runtime does not merge partials (ADR-0005).
  Attribution uses canonical label-based mapping with bounded queries and
  deterministic errors (M6 specs).
- **SessionMetrics (M7).** `kind: SessionMetrics`, route
  `GET /api/v1/metrics/users/{user}/sessions/{uuid}`, same envelope and
  single-provider rule via `sources.sessions`. Session identity mapping and
  cardinality guardrails live in the provider layer; `{uuid}` semantics are
  defined in M7 specs. When Skaha switches session-list pod usage to Metrics
  HTTP, `MetricsDAO` changes configuration only (system ADR-0001).
- Both scopes follow **private cache** rules (ADR-0004) and are distinct from
  **InteractiveQuota** (ADR-0015): those routes and kinds are not redefined
  here.

## Consequences

- Wire field details inside `data` are locked when M6/M7 ship schemas in
  `src/metrics/schemas/`; these ADRs lock contract names, routes, and the
  single-provider rule only.
- Enabling a new source for these scopes follows the provider lifecycle rules
  of ADR-0005 and the client standard of ADR-0023.

## References

- [`0013-public-api-surface-and-sanitized-errors.md`](0013-public-api-surface-and-sanitized-errors.md)
- [`../../../docs/adr/0001-platform-stats-integration-boundary.md`](../../../docs/adr/0001-platform-stats-integration-boundary.md)
