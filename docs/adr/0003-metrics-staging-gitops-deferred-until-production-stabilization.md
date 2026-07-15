# ADR-0003: Metrics staging GitOps deferred until production stabilization

## Status

Accepted (M10 plan)

## Context

Argo CD staging promotion adds GitOps surface area. It should not precede a
stable production baseline and closed sev-1/sev-2 issues.

## Decision

- **M10** Argo CD staging work starts only after M1–M8 acceptance gates,
  **M9 stabilization exit criteria**, and no open sev-1/sev-2 production issues.
- M10 covers manifests, sync policy, secrets delivery, and rollback rehearsal—not
  new Metrics API or provider features.
- Staging values mapping and sync policy details live in GitOps repos
  (keel-deploy); this ADR records the ** sequencing gate** only.

## Consequences

- Feature milestones (M5–M7) are not blocked on Argo CD staging readiness.
- Production debug loop (M9) precedes staging GitOps automation (M10).
