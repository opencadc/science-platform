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

**Metric scope**: Named read surface mapped via `sources.*` to exactly one
provider. The only shipped scope is `platform`. A scope ships with its route,
cache TTL, provider method, schema, telemetry, and tests together.

**Source configuration**: Typed `sources` tree selecting which provider key backs
each scope. Distinct from `providers.*` connection settings.

**Complete provider metric**: Provider returns a full scope model; the runtime
does not compose fragments across providers.

**Provider fingerprint**: Stable segment in cache keys when queue lists or
provider config change.

**Versioned API envelope**: Responses use `version` (for example
`metrics.canfar.net/v1`), `kind`, `metadata.created`, `status`, and `data`.

**PlatformMetrics**: Cluster-wide Kueue-backed contract (`kind: PlatformMetrics`);
route `GET /api/v1/metrics/platform`. Shipped in M4.

## Relationships

- Metrics owns caching and snapshot freshness for platform reads; Skaha does
  not cache Metrics responses.
- Each key in `data.capacity` must also appear in `data.allocated` using the
  **same unit** for that resource name
  ([ADR-0003](docs/adr/0003-platform-capacity-allocated-unit-parity.md)).
- Platform `allocated` sums `status.flavorsUsage.resources[].total` only;
  do not add `borrowed` separately (total already includes borrowed quota).
## Example dialogue

> **Dev:** "Where does platform capacity come from?"
> **Domain expert:** "Summed nominal quota from the configured ClusterQueues in
> the Kueue provider — not node listing or pod aggregation."

## Proposed vocabulary

ADRs 0015–0018 and 0020–0021 are proposals only. Their provider names, source
keys, routes, schemas, cache rules, and telemetry do not exist in runtime
configuration or the public API.
