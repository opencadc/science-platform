# Milestone M3: user metrics release

This plan defines the third milestone for the CANFAR Metrics API rollout. It
introduces user-scoped metrics while preserving low-impact query behavior and
clear attribution contracts.

## Summary

This milestone implements `GET /api/v1/metrics/users/{user}` as a production
endpoint. You establish user label mapping rules, provider queries, caching,
and operational guardrails for scoped usage.

## In scope

This section lists execution items for this milestone.

- Define canonical user attribution contract from labels.
- Implement user-scoped usage retrieval and service orchestration.
- Deliver stable user response payload with source provenance.
- Add user-scope cache and telemetry parity with platform route.
- Add tests for attribution and missing-label behavior.

## Out of scope

This section lists deferred work.

- Session-level endpoint behavior.
- ArgoCD deployment automation.
- Efficiency/waste analytics layer.
- Non-canonical label governance redesign.

## Dependencies

This milestone relies on foundational and platform release outputs.

- M1 delivery scaffolding and CI checks.
- M2 contract and observability controls.
- Provider query paths and service cache primitives.
- Existing route and model envelope structure.

## Roadmap and settings naming

Roadmap environment names use `integration` and `production`, while the current
settings model still accepts `int` and `prod` for `METRICS_ENVIRONMENT`. Keep
user-facing documentation aligned with the mapping and reconciliation plan
from milestone M2.

## Constraints

This milestone must protect service and infrastructure behavior.

- Keep user query behavior bounded and efficient.
- Keep error semantics deterministic.
- Keep backward compatibility optional until first stable deployment.
- Keep environment-driven configuration for label keys and query controls.
- Align default cache TTL behavior with the M2 platform decision, including any
  change from the current short default used in unit tests.

## Alignment with platform mode work

User metrics reuse the same runtime configuration and observability conventions as
the platform route. That means user work must stay consistent with the
single-mode contract, startup validation rules, and provider boundaries defined
in the M2 and M2b milestones, even when user routes do not introduce new cluster
modes.

## Implementation phases

This section orders user metrics implementation.

1. **Attribution contract definition**
   - Define user label keys and conflict handling.
   - Document required metadata assumptions for user resolution.
2. **Provider and service implementation**
   - Add user-scoped provider query support.
   - Add user-scoped service compute and cache path.
3. **API route completion**
   - Harden the user route beyond the current contract-level behavior, including
     attribution rules and error semantics.
   - Align metadata and cache headers with platform route.
4. **Guardrails and tests**
   - Add tests for missing labels and deterministic error paths.
   - Validate query behavior under small bounded scenarios.
5. **Release and monitoring**
   - Deploy and verify user route behavior in release environment.
   - Confirm telemetry and cache behavior for user scope.

## Validation plan

This section defines verification and acceptance checks.

- Run gate `harness-contracts`.
- Run gate `repository-coverage`.
- Run gate `harness-cli`.
- Use `FastAPI TestClient` for user-route contract tests that do not require a
  live cluster.
- When `METRICS_BASE_URL` is configured, run cluster-backed smoke tests in
  `tests/integration` against the deployed service the same way platform smoke
  tests do today.
- Validate `GET /api/v1/metrics/users/{user}` payload contract.
- Validate user-scope cache and telemetry behavior.
- Validate attribution edge cases and error responses.

## Risks

This section lists risks and controls.

- **Label inconsistency risk:** Document canonical mapping and validate in tests.
- **Cardinality risk:** Keep bounded query controls and monitor resource impact.
- **Attribution ambiguity:** Keep explicit conflict handling and clear errors.
- **Telemetry blind spot risk:** Verify scope tags for user-path metrics.

## Operational controls

This section defines controls required for rollout readiness.

- Require route-specific smoke checks before promotion.
- Require user attribution test coverage for edge cases.
- Require cache and telemetry scope verification.
- Require post-release observation notes for initial rollout window.

## Implementer handoff checklist

Use this checklist for milestone closure.

- [ ] User attribution contract is documented.
- [ ] User route is implemented and tested.
- [ ] User-scope cache behavior is validated.
- [ ] User-scope telemetry signals are verified.
- [ ] Required gates pass.
- [ ] Release outcomes are recorded in `docs/plans`.
