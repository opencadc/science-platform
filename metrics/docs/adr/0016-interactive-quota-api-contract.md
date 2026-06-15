# ADR-0016: InteractiveQuota API contract

## Status

Proposed (M5 — not yet implemented)

## Context

Interactive session quota must be observable per user without exposing namespace
or label-selector mechanics in the public API.

## Decision

- Route: `GET /api/v1/metrics/users/{user}/quotas/interactive`.
- Response `kind: InteractiveQuota` with **fixed** and **flexible** buckets.
- Each bucket reports:
  - **Logical session count**: distinct values of the **one** session-type label
    key present on each Pod (Pods with zero or multiple configured session label
    keys are skipped, not double-counted).
  - Summed container `requests` and `limits` as open resource maps preserving
    arbitrary Kubernetes resource names.
- Route `{user}` maps directly to the configured user label value; namespace
  parameters are not part of the public API.
- Envelope follows versioned API shape: `version`, `kind`, `metadata.created`,
  `status`, `data`.

## Consequences

- Skaha (or session launch) must label Pods correctly; see system ADR-0004.
- Skipped or malformed Pods are counted in provider telemetry, not listed in the
  API response.

## References

- [`../../../docs/adr/0004-interactive-workload-pod-label-contract.md`](../../../docs/adr/0004-interactive-workload-pod-label-contract.md)
