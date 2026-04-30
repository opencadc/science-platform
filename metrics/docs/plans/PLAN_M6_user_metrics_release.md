# Milestone M6: user metrics release

This plan defines the sixth milestone for the CANFAR Metrics API roadmap. It
introduces production-grade user-scoped metrics on top of the platform contract
and M4 provider runtime architecture.

## Repository snapshot versus milestone target

M4 removes the pre-release user route until a provider can return a complete
user metric contract. M6 reintroduces the route with attribution semantics,
bounded operational controls, and release-grade validation.

## Summary

M6 hardens `GET /api/v1/metrics/users/{user}` with deterministic attribution,
bounded query behavior, and stable operational controls.

## In scope

- Define canonical user attribution contracts from labels.
- Implement user-scoped usage retrieval and service orchestration.
- Deliver stable payload and cache semantics for user scope.
- Add tests for attribution edge cases and deterministic failures.

## Out of scope

- Session-level contract expansion.
- ArgoCD staging deployment automation.
- Analytics slices beyond route-level contract behavior.

## Dependencies

- M1 setup and CI baseline.
- M2 platform route contract and observability controls.
- M3 architecture realignment.
- M4 provider runtime architecture.
- M5 interactive quota outputs where applicable.

## Constraints

- Keep user query behavior bounded.
- Keep error semantics deterministic.
- Keep all runtime configuration environment-driven and Pydantic validated.
- Do not redefine the interactive quota route delivered in M5.

## Implementation phases

1. Define user attribution contract and conflict behavior.
2. Implement provider and service support for user scope.
3. Harden route payload and metadata behavior.
4. Add guardrail tests and rollout validation.

## Validation plan

- Run gate `harness-contracts`.
- Run gate `repository-coverage`.
- Run gate `harness-cli`.
- Validate user route payload and error contracts.
- Validate cache and telemetry parity for user scope.

## Risks

- Label inconsistency.
- Attribution ambiguity.
- Query-cardinality pressure.

## Operational controls

- Require route-specific smoke checks before promotion.
- Require attribution test coverage for edge cases.
- Require post-release observation notes.

## Implementer handoff checklist

- [ ] User attribution contract is documented and tested.
- [ ] User route behavior is deterministic.
- [ ] Cache and telemetry behavior is validated for user scope.
- [ ] Required gates pass.
