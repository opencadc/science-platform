# Milestone M10: post-initial ArgoCD staging plan

This plan is intentionally deferred until the preceding delivery milestones are
stable in production. It captures GitOps staging promotion work without
blocking the core API roadmap.

## Objective

Introduce an ArgoCD-managed staging promotion path for the metrics service
using Helm values overlays and repository-driven deployment workflows.

## Preconditions

Complete these conditions before starting:

- M1 through M8 acceptance gates are satisfied, and M9 stabilization exit
  criteria are met and recorded.
- The initial production debug loop has no open severity-1 or severity-2
  issues.
- Chart defaults and environment overlays are stable.

## Scope

This post-initial work includes:

- ArgoCD application manifests for staging deployment.
- Environment promotion policy from `integration` to `staging`.
- Secret and credential delivery model for staging values.
- Promotion verification and rollback procedures.

This post-initial work excludes:

- Feature-level API contract changes.
- New provider logic beyond established milestone scope.

## Implementation phases

1. Add ArgoCD application manifests for `metrics-api`.
2. Map Helm `values-staging.yaml` into ArgoCD application configuration.
3. Define sync policy and manual approval controls.
4. Add staged smoke checks and rollback criteria.
5. Validate promotion flow in a staging rehearsal.

## Success criteria

- ArgoCD reconciles the metrics service chart to staging.
- Promotion flow and rollback are documented and rehearsed.
- Staging telemetry mirrors production observability expectations.
