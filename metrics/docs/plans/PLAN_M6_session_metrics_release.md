# Milestone M6: session metrics release

This plan defines the sixth milestone for the CANFAR Metrics API roadmap. It
completes the initial API scope with session-scoped metrics and strict identity
controls.

## Repository snapshot versus milestone target

The session route already exists in current code, but the milestone target is
to harden identity semantics, cardinality guardrails, and rollout validation
for production use.

## Summary

M6 hardens `GET /api/v1/metrics/users/{user}/sessions/{uuid}` with stable
session identity mapping, bounded high-cardinality behavior, and rollout safety
controls.

## In scope

- Define canonical session identity mapping and validation rules.
- Implement session-scoped provider queries and service orchestration.
- Add session cache strategy and telemetry parity.
- Validate bounded behavior in targeted high-cardinality scenarios.

## Out of scope

- ArgoCD staging workflow.
- Multi-cluster federation redesign.
- Dashboard-specific aggregation features.

## Dependencies

- M1 foundation and CI baseline.
- M2 platform controls.
- M5 user metrics outputs.

## Constraints

- Keep session endpoint low impact and bounded.
- Keep identity mapping deterministic.
- Keep runtime behavior configurable via environment variables.

## Implementation phases

1. Define session identity and mismatch semantics.
2. Add session provider/service paths.
3. Harden route behavior and failure mapping.
4. Validate cardinality and rollout safety.

## Validation plan

- Run gate `harness-contracts`.
- Run gate `repository-coverage`.
- Run gate `harness-cli`.
- Validate session route contract and deterministic errors.
- Validate session cache and telemetry behavior.

## Risks

- High-cardinality pressure.
- Identity mismatch ambiguity.
- Operational regressions during rollout.

## Operational controls

- Require session-route smoke checks before promotion.
- Require bounded timeout and query controls.
- Require explicit rollback criteria.

## Implementer handoff checklist

- [ ] Session identity contract is documented and tested.
- [ ] Session route behavior is validated.
- [ ] Cardinality safeguards are covered by tests.
- [ ] Required gates pass.
