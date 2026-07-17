# ADR-0004: Interactive workload pod label contract

## Status

Proposed (M5 — not yet implemented)

## Context

Metrics interactive quota (M5) lists Pods by label selectors. Skaha (or
equivalent session launch) must label workloads consistently or quota reads will
be wrong without API-level namespace parameters.

## Decision

- Interactive session Pods must expose configurable labels for:
  - **User** (route `{user}` maps to this label value)
  - **Allocation class** (`fixed` / `flexible` values)
  - **Exactly one session-type label** from a configured allow-list (notebook,
    desktop, carta, etc.)
- Label **key names** are configured in Metrics `KubeProviderConfig.labels`;
  label **values** on Pods are owned by session launch / Skaha.
- Namespace scope is provider-internal (configured `namespaces` list); not exposed
  on the public quota route.

## Consequences

- Platform teams validate label correctness on interactive workloads before
  enabling quota routes in production.
- Changes to label keys require coordinated Metrics config and Skaha launch
  template updates.

## References

- [`../../metrics/docs/adr/0016-interactive-quota-api-contract.md`](../../metrics/docs/adr/0016-interactive-quota-api-contract.md)
- [`../../skaha/CONTEXT.md`](../../skaha/CONTEXT.md)
