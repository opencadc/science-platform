# ADR-0020: UserMetrics contract

## Status

Proposed (M6)

## Context

M4 removed partial user routes until a provider could return a complete scope
model. M6 reintroduces user-scoped metrics as a first-class contract parallel to
**PlatformMetrics**, not as composed fragments from multiple sources.

## Decision

- Introduce **`UserMetrics`** as a versioned API contract (`kind: UserMetrics`)
  with the same envelope shape as **PlatformMetrics** (`version`, `kind`,
  `metadata.created`, `status`, `data`).
- Route: `GET /api/v1/metrics/users/{user}` returns a **complete** `UserMetrics`
  payload from **one** configured provider for the user scope—not stitched
  fragments.
- **`sources.users`** (exact key TBD at implementation) selects the provider
  implementation—for example **`kube`** or **`prometheus`**. Each provider must
  implement a complete `UserMetrics` model for that scope (ADR-0011); runtime
  does not merge kube + prometheus partials.
- Attribution uses canonical label-based mapping with bounded queries and
  deterministic errors (implementation detail in M6 specs, not in this ADR).
- User-scoped responses follow **private cache** rules (ADR-0017).
- **InteractiveQuota** (`InteractiveQuota` kind, ADR-0016) is a separate quota
  contract; UserMetrics must not redefine that route or kind.

## Consequences

- Wire field details inside `data` are defined when M6 ships schemas in
  `src/metrics/schemas/`; this ADR locks the contract name, route, and
  single-provider rule only.
- Enabling prometheus for user scope opens a prometheus HTTP client in the active
  graph when that source is selected (ADR-0012).

## References

- [`0011-complete-provider-metrics-without-composition.md`](0011-complete-provider-metrics-without-composition.md)
- [`0014-progressive-public-route-surface.md`](0014-progressive-public-route-surface.md)
- [`0017-private-cache-for-user-scoped-metrics.md`](0017-private-cache-for-user-scoped-metrics.md)
