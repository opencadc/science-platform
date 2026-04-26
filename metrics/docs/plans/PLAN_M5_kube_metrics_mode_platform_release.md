# Milestone M5: kube-metrics platform source release

This plan defines the fifth milestone for the CANFAR Metrics API roadmap. It
implements kube-metrics as a first-class platform source for metrics
composition after the M4 provider runtime architecture reset.

## Repository snapshot versus milestone target

M4 establishes the provider runtime, complete metric source contract, and typed
source configuration. M5 closes when kube-metrics is fully wired as a supported
runtime source in that model.

## Summary

This milestone makes kube-metrics production-ready for platform scope while
preserving fail-fast startup behavior, generic resource maps, and environment
driven configuration.

`dev` uses a Kubernetes-first contract with Minikube, Helm, and `kubectl`.
`integration`, `staging`, and `production` deploy into existing clusters.

## In scope

- Implement kube-metrics runtime provider wiring for platform metrics.
- Validate kube-metrics dependencies during startup.
- Keep response shape based on generic `capacity` and `allocated` resource maps.
- Keep fixture ownership under `tests/fixtures/kube-metrics`.
- Add `FastAPI TestClient` and integration validation for valid and invalid
  kube-metrics startup.

## Out of scope

- User and session feature expansion.
- ArgoCD staging automation.
- Runtime fallback behavior to removed providers.

## Dependencies

- M1 foundation and M2 platform contract.
- M3 architecture realignment plan and outcomes.
- M4 provider runtime architecture.
- Existing Kubernetes integration workflow and Helm deployment path.

## Constraints

- Keep one process with explicit source configuration.
- Keep startup dependency checks fail-fast.
- Keep configuration 12-factor and Pydantic validated.
- Keep contracts open-ended for future resource names.

## Implementation phases

1. Define kube-metrics dependency contract for local and CI paths.
2. Implement provider adapter and source mapping for platform scope.
3. Add startup validation for required metrics dependencies.
4. Validate contract behavior through unit and integration tests.
5. Record architecture review checkpoint before milestone close.

## Validation plan

- Run gate `harness-contracts`.
- Run gate `repository-coverage`.
- Run gate `harness-cli`.
- Validate startup fails when kube-metrics dependencies are missing.
- Validate platform payload contract remains stable and generic.
- Validate local cluster-backed smoke checks pass in Minikube flow.

## Risks

- Dependency ambiguity across clusters.
- Startup-check regressions.
- Fixture sprawl across unrelated test folders.

## Operational controls

- Require documented dependency contract before rollout.
- Require startup validation evidence for success and failure cases.
- Require review checkpoint confirming boundaries from M3 remain clean.

## Implementer handoff checklist

- [ ] Kube-metrics runtime path is implemented for platform scope.
- [ ] Startup checks cover kube-metrics dependency failures.
- [ ] Contract remains generic and resource-name agnostic.
- [ ] Integration and app-factory tests pass.
- [ ] Required gates pass.
