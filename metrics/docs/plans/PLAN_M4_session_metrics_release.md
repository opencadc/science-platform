# Milestone M4: session metrics release

This plan defines the fourth milestone for the CANFAR Metrics API rollout. It
completes the initial API shape by adding session-scoped metrics with strict
identity mapping and operational protections.

## Summary

This milestone implements
`GET /api/v1/metrics/users/{user}/sessions/{uuid}` and completes the initial
platform-user-session rollout loop. You focus on session identity mapping,
high-cardinality controls, cache behavior, and stable production operation.

## In scope

This section describes milestone deliverables.

- Define canonical session identity mapping and validation rules.
- Implement session-scoped provider queries and service orchestration.
- Deliver session response payload with source provenance.
- Add session cache strategy and telemetry parity.
- Validate performance and behavior under bounded cardinality scenarios.

## Out of scope

This section lists deferred work.

- ArgoCD staging GitOps automation.
- Efficiency and waste-hour analytics expansion.
- Dashboard-specific aggregation layers.
- Multi-cluster federation redesign.

## Dependencies

This milestone depends on earlier milestone outputs.

- M1 setup and CI baseline.
- M2 platform route production controls.
- M3 user attribution and scoped query behavior.
- Existing model envelope and error contract.

## Roadmap and settings naming

Roadmap environment names use `integration` and `production`, while the current
settings model still accepts `int` and `prod` for `METRICS_ENVIRONMENT`. Keep
session rollout documentation aligned with the mapping and reconciliation plan
from milestone M2.

## Constraints

This milestone must preserve stability and resource efficiency.

- Keep session endpoint low impact and bounded.
- Keep identity mapping deterministic.
- Keep query and cache behavior explicit for operational debugging.
- Keep all runtime behavior configurable via environment variables.
- Align default cache TTL behavior with the M2 platform decision, including any
  change from the current short default used in unit tests.

## Alignment with platform mode work

Session metrics build on the same service runtime model as platform and user
metrics. Keep session work aligned with the single-mode contract, startup
validation rules, and provider boundaries from the M2 and M2b milestones so the
session route does not accidentally reintroduce cross-mode behavior.

## Implementation phases

This section defines execution sequencing.

1. **Session identity contract**
   - Define session label key mapping and conflict behavior.
   - Define user-session mismatch handling.
2. **Provider and service path**
   - Add session-scoped provider queries.
   - Add session-scoped compute and cache orchestration.
3. **Route and payload completion**
   - Harden the session route beyond the current contract-level behavior,
     including identity validation and failure semantics.
   - Align metadata, cache headers, and status semantics.
4. **Cardinality and safety checks**
   - Add tests that cover bounded high-cardinality scenarios.
   - Validate deterministic failure behavior for missing identities.
5. **Release and stabilization**
   - Run staged rollout checks.
   - Confirm session-scope telemetry behavior in release monitoring.

## Validation plan

This section defines mandatory verification.

- Run gate `harness-contracts`.
- Run gate `repository-coverage`.
- Run gate `harness-cli`.
- Use `FastAPI TestClient` for session-route contract tests that do not require a
  live cluster.
- When `METRICS_BASE_URL` is configured, run cluster-backed smoke tests in
  `tests/integration` against the deployed service the same way platform smoke
  tests do today.
- Validate
  `GET /api/v1/metrics/users/{user}/sessions/{uuid}` response contract.
- Validate session cache and telemetry behavior.
- Validate cardinality controls through targeted tests.

## Risks

This section summarizes key risks and mitigations.

- **High-cardinality pressure:** Keep bounded queries and strict cache keys.
- **Identity mismatch risk:** Enforce explicit mapping and deterministic errors.
- **Operational regressions:** Require rollout smoke and stabilization checks.
- **Scope drift:** Keep session contract tied to canonical route shape.

## Operational controls

This section lists controls required for production readiness.

- Require session-route smoke checks and telemetry checks before promotion.
- Require bounded query and timeout controls in configuration.
- Require cache-hit and latency monitoring during initial rollout.
- Require rollback criteria and incident notes for session rollout.

## Implementer handoff checklist

Use this checklist when you close milestone execution.

- [ ] Session identity contract is documented.
- [ ] Session route is implemented and validated.
- [ ] Cardinality safeguards are tested.
- [ ] Session-scope cache and telemetry are verified.
- [ ] Required gates pass.
- [ ] Stabilization outcomes are recorded in `docs/plans`.
