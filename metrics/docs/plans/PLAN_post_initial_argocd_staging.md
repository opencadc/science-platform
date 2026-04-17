# Post-initial ArgoCD staging plan

This plan is intentionally deferred until Milestones 1 through 4 are stable in
production. It captures the next GitOps steps without blocking the initial
rollout.

## Objective

Introduce an ArgoCD-managed staging promotion path for the metrics service
using Helm values overlays and repository-driven deployment workflows.

## Preconditions

Complete these conditions before starting:

- M1 through M4 acceptance gates are satisfied.
- Production debug loop has no open severity-1 or severity-2 issues.
- Chart defaults and environment overlays are stable.

## Scope

The post-initial work includes:

- ArgoCD application manifests for staging deployment.
- Environment promotion policy from `int` to `staging`.
- Secret and credential delivery model for staging values.
- Promotion verification and rollback procedures.

The post-initial work excludes:

- Feature-level API contract changes.
- New provider logic beyond existing milestone scope.

## Implementation phases

Use these phases to execute GitOps staging integration.

1. Add ArgoCD application manifests for `metrics-api`.
2. Map Helm `values-staging.yaml` into ArgoCD application configuration.
3. Define sync policy and manual approval controls.
4. Add staged smoke checks and rollback criteria.
5. Validate promotion flow in a staging rehearsal.

## Success criteria

Treat this effort complete when:

- ArgoCD can reconcile the metrics service chart to staging.
- Promotion flow and rollback are documented and rehearsed.
- Staging telemetry mirrors production observability expectations.
