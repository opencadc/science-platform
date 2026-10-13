# ADR-0002: Co-deployed Metrics backend with Skaha

## Status

Accepted

## Context

Skaha needs in-cluster access to Metrics without requiring public ingress for
every environment. Deployment and RBAC should stay in one Helm release.

## Decision

- Skaha and Metrics ship as **one Helm release**. The Metrics workload is
  required and always enabled; it is not a separate Argo CD app for Metrics
  alone.
- Skaha, Metrics, chart-owned Jobs, and Helm test Pods always use the same
  Kubernetes ServiceAccount, resolved by the Skaha chart's `serviceAccount`
  settings (or `deployment.skaha.serviceAccountName`). Metrics has no separate
  ServiceAccount template or identity override. The Bitnami Redis dependency
  retains its own ServiceAccount configuration.
- Kueue read permissions for Metrics live in the Skaha chart as a
  resource-restricted ClusterRole/ClusterRoleBinding for configured
  ClusterQueues and one namespaced Role/RoleBinding per configured namespace
  for LocalQueue `list`.
- Skaha always receives an internal base URL (`SKAHA_METRICS_BACKEND_URL`)
  pointing at the in-cluster Metrics Service. Public ingress for Metrics is
  optional and environment-specific.
- Metrics requires a real lower-case DNS `metricsBackend.clusterName`, which is
  rendered as `METRICS_CLUSTER_NAME` for cache identity and PromQL cluster-label
  matching.
- Metrics uses one operator-provided shared external Redis deployment via
  `metricsBackend.redis.urlSecret`. The chart owns the cache integrity Secret:
  generated with Helm's `randBytes 32` on install and reused via `lookup` on
  native Helm upgrades. Argo CD Applications must ignore only `/data/key-secret`
  on this release's Secret and set `RespectIgnoreDifferences=true` so offline
  rendering cannot rotate the live key during sync. The Secret is immutable,
  deleted with the release on cascading uninstall, and recreated with a new key
  on fresh install. Metrics does not use a Bitnami or other chart-owned Redis
  instance. There is no cache backend selector.
- OTLP is metrics-only and external. This release does not own a traces/logs
  pipeline or an OpenTelemetry Collector.
- Authoritative deploy chart: `deployments/helm/applications/skaha`. The chart
  under `science-platform/metrics/helm/metrics-api` is reference only.

## Consequences

- GitOps (e.g. keel-deploy) supplies Metrics runtime config via Helm values
  (`metricsBackend.env` and the operator-provided Redis URL Secret). The cache
  integrity Secret is chart-managed and must not be set in `metricsBackend.env`.
- Argo CD deployers configure the exact cache Secret name, namespace, and
  `/data/key-secret` ignore rule directly in their Skaha Application. Both the narrowly scoped
  ignore rule and sync option are required. No bootstrap Job or extra Secret
  API permissions are needed. Non-cascading Application deletion preserves the
  installed resources and key by design.
- When `metricsBackend.rbac.enabled` is false, deployers must pre-provision
  the shared Skaha ServiceAccount with Kueue
  `ClusterQueue` `get` access for the configured queues and LocalQueue `list`
  access in every configured namespace.

## References

- Workspace `AGENTS.md` deployment bullets
- Authoritative deploy chart: `deployments/helm/applications/skaha` (sibling
  `deployments` repository when cloned alongside `science-platform`)
