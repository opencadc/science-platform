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
  ([ADR-0002](docs/adr/0002-platform-api-contract.md)).
- Platform `allocated` sums `status.flavorsUsage.resources[].total` only;
  do not add `borrowed` separately (total already includes borrowed quota).
## Example dialogue

> **Dev:** "Where does platform capacity come from?"
> **Domain expert:** "Summed nominal quota from the configured ClusterQueues in
> the Kueue provider — not node listing or pod aggregation."

## Proposed vocabulary

The terms below describe the accepted design for the unreleased `v1alpha1`
contract. They do not exist in runtime configuration or the active API yet.
ADRs 0004–0009 own these decisions.

**Metrics**: Proposed Kubernetes API kind for one bounded metrics report about
a `Platform`, `User`, or `Community` subject. The API group `canfar.net`
carries the product identity, so the Kind is the unprefixed CamelCase domain
type. `Metrics` is the accepted mass noun. _Avoid_: `CanfarMetrics`, `CANFARMetrics`,
`MetricsReport`, and separate `PlatformMetrics`, `UserMetrics`, or
`CommunityMetrics` kinds.

**CANFAR API family**: Kubernetes-shaped resources sharing API group
`canfar.net`; the initial Kind is the mass noun `Metrics`, while a future
object resource would use a singular noun such as `Session`. A future
aggregated API server owns the group/version as one
serving boundary. _Avoid_: product-prefixed kinds such as `CanfarMetrics` or
implementation-role kinds such as `Controller` and `Accounting`.

**Metrics subject**: Aggregation selector chosen by the GET route and echoed as
exactly one of `spec.user`, `spec.community`, or `spec.platform`. In the
unauthenticated `v1alpha1` service this is not an authorization boundary:
`spec.user` is the exact `canfar.net/username` label value and
`spec.community` is the exact `canfar.net/community` label value. The
cluster-internal deployment boundary controls access until a separate
authorization design exists.
_Avoid_: raw Kubernetes label selectors or PromQL supplied as subject identity.

**Metrics report**: Bounded observations represented by one `Metrics`
object. Initial reports contain current requested resources and, in phase 2,
active-workload lifetime usage and efficiency; fixed-window history is
deferred. This remains the domain concept represented by the serialized
`Metrics` Kind.
_Avoid_: raw time series, unbounded query results, or a per-Pod inventory.

**Current requested resources**: Scheduler-effective whole-Pod resource
requests held by the subject's `Running` Pods at the report's observation
time. For each Pod, sum regular containers and restartable sidecars, compare
that sum with the largest effective init-container request, then add Pod
overhead. Preserve open Kubernetes resource names and treat an absent request
as unknown, not zero. This is reserved capacity, not measured consumption.
_Avoid_: total usage, current usage, or first-container-only requests.

**Active-workload lifetime accounting**: Serialized as
`accountingPeriod: ActiveWorkloadLifetime`. For the Pods `Running` at
observation time, report per-resource total usage hours, requested hours, and
their ratio, with each Pod integrated only over its own Running duration. CPU
uses core-hours, memory uses GiB-hours, and GPU uses GPU-hours. _Avoid_:
Running-Pod lifetime, live efficiency, overall efficiency, average Pod
efficiency.

**Running workload set**: Pods in phase `Running` at observation time that
are in a configured CANFAR workload namespace and carry
`app.kubernetes.io/managed-by=skaha`, `app.kubernetes.io/part-of=canfar`, and
the exact canonical User or Community subject label. Pending demand, completed
Pods, other namespaces, and non-Skaha Pods are outside this set. _Avoid_:
active workloads when the Pod phase boundary matters.

**Stale metrics report**: A previously collected report served after its fresh
period but before its stale-serviceable deadline, retaining the original
observation time and an explicit stale condition. After that deadline the
snapshot may remain in Redis briefly for recovery and diagnostics but the API
returns 503. _Avoid_: cached report when freshness matters.

**Resource-time accounting series**: Metrics-owned, versioned internal series
that expose additive requested and observed resource-time for each Pod UID and
resource, plus continuity/coverage needed to detect resets. Reports sum the
numerators and denominators before deriving efficiency. _Avoid_: a final
per-Pod ratio, an average of ratios, or a public raw PromQL contract.

**Source snapshot**: Normalized, schema-versioned observation for one source,
cluster, configured namespace set, and subject digest, stored in Redis. A
request may trigger a bounded fill when no serviceable snapshot exists, but
Redis leases ensure concurrent traffic shares that fill. _Avoid_: cached raw
upstream envelopes, an unscoped cluster-wide Pod inventory, or a downstream
query for every concurrent API request.

**Kueue source**: Internal `kueue` provider for Platform capacity and admitted
allocation. _Avoid_: treating Kueue quota as observed resource consumption.

**Kubernetes workload source**: Internal `kubernetes` provider for Running Pod
lifecycle, canonical labels, and declared requests. It reads Pod
specifications, not the Kubernetes Resource Metrics API. _Avoid_:
`kube_metrics` for this source.

**PromQL accounting source**: Internal `promql` provider for controlled
Prometheus- or Mimir-compatible queries that return active-workload lifetime
usage and efficiency inputs. _Avoid_: provider names tied to one compatible
backend product or caller-authored PromQL.

**Complete namespace observation**: A User or Community total assembled only
after every configured workload namespace has been observed successfully. A
subset is not a valid total; Metrics serves a prior complete snapshot or marks
the section unavailable. _Avoid_: partial namespace totals presented as
complete.

**Public source provenance**: Internal source adapters, snapshots, and their
individual timestamps are operational details and are not serialized in the
`Metrics` response. The public report exposes one conservative
`observedAt`, readiness, and cache status. Source-level detail remains in OTel
telemetry. _Avoid_: `status.sources`.

**Metrics query catalog**: Versioned allowlist of server-owned time-series
query templates addressed by stable query IDs. _Avoid_: raw PromQL proxy,
caller-authored query endpoint, upstream query URL.

**Metrics module architecture**: Incremental evolution of the existing
`api`, `core`, `providers`, `schemas`, and `services` packages. The runtime is
the explicit composition root, the shared Metrics service hides orchestration,
and source adapters normalize into service-owned models. `cache.py` and
`telemetry.py` become deeper packages only when their concrete behavior lands.
_Avoid_: a parallel `domain/application/ports/adapters` tree, generic provider
registry, service locator, or empty operator package.
