# Metrics

The Metrics service exposes cluster-wide **platform capacity** and **platform
allocation** derived from Kueue. Skaha and other in-cluster consumers call it
via `GET /api/v1/metrics/platform`.

Shared cross-context vocabulary: [`../CONTEXT-MAP.md`](../CONTEXT-MAP.md).

Distilled decisions: [`docs/adr/README.md`](docs/adr/README.md).

## Language

**Platform capacity**: Total CPU and memory available across the cluster for
scheduling (Kueue-backed). Exposed as `data.capacity`. Open resource-name keys
(for example `cpu`, `memory`, `nvidia.com/gpu`). _Avoid_: "available" alone.

**Platform allocation**: CPU and memory already allocated on the cluster
(Kueue-backed). Exposed as `data.allocated`. Sourced from
`flavorsUsage.resources[].total`. _Avoid_: "requested" alone when meaning
cluster totals.

**Metrics backend**: This service when co-deployed with Skaha. Skaha reaches it
at `SKAHA_METRICS_BACKEND_URL` (in-cluster Service, not the edge hostname).
_Avoid_: "metrics pod" in specs.

**ClusterQueue-backed metrics**: Platform metrics aggregate configured Kueue
`ClusterQueue` objects only; cohort is not part of provider configuration or
capacity aggregation.

**Metric scope**: Named read surface (for example `platform`, `quotas.interactive`)
mapped via `sources.*` to exactly one provider. Scopes ship with routes, cache
TTL, provider methods, and tests together.

**Source configuration**: Typed `sources` tree selecting which provider key backs
each scope. Distinct from `providers.*` connection settings.

**Complete provider metric**: Provider returns a full scope model; the runtime
does not compose fragments across providers.

**Provider fingerprint**: Stable segment in cache keys when queue lists or
provider config change.

**Kube provider**: Kubernetes Pod/API adapter for **quota and user/session workload
scopes** when configured, not a substitute `sources.platform` source.

**Interactive quota**: User-scoped observed requests/limits for interactive
sessions, split into **fixed** and **flexible** allocation classes
(`GET /api/v1/metrics/users/{user}/quotas/interactive`).

**Logical interactive session**: Quota `count` groups Pods by one configured
session-type label value, not by Pod count.

**Private cache scope**: User/quota (and future user/session) responses use HTTP
`Cache-Control: private`, short TTL, and user-hashed internal cache keys.

**Versioned API envelope**: Responses use `version` (for example
`metrics.canfar.net/v1`), `kind`, `metadata.created`, `status`, and `data`.

**PlatformMetrics**: Cluster-wide Kueue-backed contract (`kind: PlatformMetrics`);
route `GET /api/v1/metrics/platform`. Shipped in M4.

**UserMetrics**: User-scoped contract (`kind: UserMetrics`); route
`GET /api/v1/metrics/users/{user}`. Provider selected via `sources.users`
(for example `kube` or `prometheus`). Proposed M6.

**SessionMetrics**: Session-scoped contract (`kind: SessionMetrics`); route
`GET /api/v1/metrics/users/{user}/sessions/{uuid}`. Provider selected via
`sources.sessions`. Proposed M7. May eventually back Skaha session-list pod
usage via `MetricsDAO`.

**InteractiveQuota**: Quota observation contract (`kind: InteractiveQuota`); route
`GET /api/v1/metrics/users/{user}/quotas/interactive`. Distinct from UserMetrics.
Proposed M5.

## Relationships

- Metrics owns caching and snapshot freshness for platform reads; Skaha does
  not cache Metrics responses.
- Each key in `data.capacity` must also appear in `data.allocated` using the
  **same unit** for that resource name
  ([ADR-0003](docs/adr/0003-platform-capacity-allocated-unit-parity.md)).
- Platform `allocated` sums `status.flavorsUsage.resources[].total` only;
  do not add `borrowed` separately (total already includes borrowed quota).
- Interactive quota depends on Pod label correctness on Skaha workloads
  ([system ADR-0004](../docs/adr/0004-interactive-workload-pod-label-contract.md)).

## Example dialogue

> **Dev:** "Where does platform capacity come from?"
> **Domain expert:** "Summed nominal quota from the configured ClusterQueues in
> the Kueue provider — not node listing or pod aggregation."

> **Dev:** "Can kube serve platform metrics if Kueue is down?"
> **Domain expert:** "No. Platform is `sources.platform: kueue` only. Kube may back
> quota or future **UserMetrics** / **SessionMetrics** scopes when configured—not
> platform totals."

> **Dev:** "Is UserMetrics the same as InteractiveQuota?"
> **Domain expert:** "No. **InteractiveQuota** observes scheduled requests/limits by
> allocation class. **UserMetrics** is a separate contract with its own `kind` and
> provider binding (ADR-0020)."

## Flagged ambiguities

- **Shipped today (M4):** `GET /api/v1/metrics/platform` and `GET /healthz` only.
- **Proposed (M5, not implemented):** interactive quota — ADRs 0015–0018, system
  ADR-0004.
- **Proposed (M6):** **UserMetrics** — ADR-0020.
- **Proposed (M7):** **SessionMetrics** — ADR-0021.
- Inactive provider types may appear in configuration but must not open upstream
  HTTP clients until their milestone activates them.
