# ADR-0002: Co-deployed Metrics backend with Skaha

## Status

Accepted

## Context

Skaha needs in-cluster access to Metrics without requiring public ingress for
every environment. Deployment and RBAC should stay in one Helm release.

## Decision

- Skaha and Metrics ship as **one Helm release** with a boolean toggle for the
  Metrics workload (`metricsBackend.enabled`), not as a separate Argo CD app for
  Metrics alone.
- The Metrics pod uses the **same Kubernetes ServiceAccount** as the Skaha API
  workload (`deployment.skaha.serviceAccountName`).
- Kueue read permissions for Metrics live in the Skaha chart as ClusterRole and
  ClusterRoleBinding bound to that shared ServiceAccount.
- Skaha receives an internal base URL (`SKAHA_METRICS_BACKEND_URL`) pointing at
  the in-cluster Metrics Service when Metrics is enabled. Public ingress for
  Metrics is optional and environment-specific.
- Authoritative deploy chart: `deployments/helm/applications/skaha`. The chart
  under `science-platform/metrics/helm/metrics-api` is reference only.

## Consequences

- GitOps (e.g. keel-deploy) supplies full Metrics runtime config via Helm values
  (`metricsBackend.env` map, Redis URL from shared Bitnami subchart when
  enabled).
- When `metricsBackend.rbac.enabled` is false, deployers must pre-provision
  Kueue `ClusterQueue` and `Cohort` get/list for the shared ServiceAccount.

## References

- Workspace `AGENTS.md` deployment bullets
- Authoritative deploy chart: `deployments/helm/applications/skaha` (sibling
  `deployments` repository when cloned alongside `science-platform`)
