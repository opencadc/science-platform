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
- The Metrics pod uses a **dedicated Kubernetes ServiceAccount**, distinct from
  the Skaha API workload identity. Operators may provide an existing Metrics
  ServiceAccount by setting `metricsBackend.serviceAccount.create=false` and
  `metricsBackend.serviceAccount.name`.
- Kueue read permissions for Metrics live in the Skaha chart as a
  resource-restricted ClusterRole/ClusterRoleBinding for configured
  ClusterQueues and one namespaced Role/RoleBinding per configured namespace
  for LocalQueue `list`.
- Skaha receives an internal base URL (`SKAHA_METRICS_BACKEND_URL`) pointing at
  the in-cluster Metrics Service when Metrics is enabled. Public ingress for
  Metrics is optional and environment-specific.
- Metrics requires a real lower-case DNS `metricsBackend.clusterName`, which is
  rendered as `METRICS_CLUSTER_NAME` for cache identity and PromQL cluster-label
  matching.
- Metrics uses one operator-provided shared external Redis deployment. Helm
  receives its URL through `metricsBackend.redis.urlSecret` and its cache
  integrity key through `metricsBackend.cacheKeySecret`; Metrics does not use a
  Bitnami or other chart-owned Redis instance. There is no cache backend
  selector: the required external Redis URL and integrity-key Secret references
  are the complete cache deployment contract.
- OTLP is metrics-only and external. This release does not own a traces/logs
  pipeline or an OpenTelemetry Collector.
- Authoritative deploy chart: `deployments/helm/applications/skaha`. The chart
  under `science-platform/metrics/helm/metrics-api` is reference only.

## Consequences

- GitOps (e.g. keel-deploy) supplies full Metrics runtime config via Helm values
  (`metricsBackend.env` map and operator-provided Secret references for the
  external Redis URL and cache integrity key).
- When `metricsBackend.rbac.enabled` is false, deployers must pre-provision
  the dedicated or operator-provided Metrics ServiceAccount with Kueue
  `ClusterQueue` `get` access for the configured queues and LocalQueue `list`
  access in every configured namespace.

## References

- Workspace `AGENTS.md` deployment bullets
- Authoritative deploy chart: `deployments/helm/applications/skaha` (sibling
  `deployments` repository when cloned alongside `science-platform`)
