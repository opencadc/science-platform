# Metrics

Metrics is the read-only service that reports current Kueue queue state for
CANFAR Users, Communities, and the Platform. It may add current CPU and memory
efficiency from an external Prometheus-compatible system; it does not create a
history or own a monitoring backend.

## Subjects and queues

**Metrics subject**:
The User, Community, or Platform named by one Metrics route. A subject selects
an aggregate; it is not an authorization claim.
_Avoid_: raw label selectors, caller-supplied PromQL, report inventory

**User**:
A canonical `canfar.net/username` label value. A User report aggregates the
matching LocalQueues in the configured namespaces.
_Avoid_: Pod owner, account, billing identity

**Community**:
A canonical `canfar.net/community` label value. A Community report aggregates
configured ClusterQueues carrying that label.
_Avoid_: Cohort, namespace, user list

**Session**:
A canonical `canfar.net/id` label value. A Session report aggregates every
matching Job in the configured namespaces, including desktop-app child Jobs
that share the same id.
_Avoid_: pod-name prefix, caller-supplied label selectors

**Platform**:
The configured Metrics deployment subject. A Platform report aggregates every
ClusterQueue named by `METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES`.
_Avoid_: every ClusterQueue visible to the Kubernetes identity

**LocalQueue**:
A namespaced Kueue queue that assigns a User's work to one configured
ClusterQueue. The queue carries the canonical username and community labels.
_Avoid_: workload inventory, Pod queue, user account

**ClusterQueue**:
A cluster-scoped Kueue queue that supplies the community and platform
aggregation boundary. Each configured ClusterQueue maps to one Community.
_Avoid_: Cohort, node pool, Prometheus series

**Configured namespace**:
A namespace in `METRICS_PROVIDERS__KUEUE__NAMESPACES` that Metrics searches for
User LocalQueues. The set is deployment configuration, not a request filter.
_Avoid_: all namespaces, workload namespace inferred from a Pod

## Report values

**Resource request**:
The Kueue-reserved quantity represented by `flavorsReservation` for a queue
or ClusterQueue, aggregated by Kubernetes resource name. It is a scheduler
quantity, not measured consumption.
_Avoid_: usage, capacity, usage-hours

**Pending workload**:
A Kueue workload counted by `pendingWorkloads`: it is waiting for admission.
It is not the public Metrics workload count.
_Avoid_: reserving workload, admitted workload

**Reserving workload**:
A Kueue workload counted by `reservingWorkloads`: it is admitted at the
cluster level and is holding or progressing through a quota reservation. It is
not a Kubernetes Pod-phase count.
_Avoid_: waiting workload, pending workload, running Pod, active Pod

**Platform capacity**:
The sum of nominal quota across the configured ClusterQueues, grouped by
resource name.
_Avoid_: node capacity, available capacity without a source

**Platform allocation**:
The sum of `flavorsUsage.resources[].total` across the configured ClusterQueues.
Borrowed quota is already included in `total`.
_Avoid_: requested resources, usage, reservation

**Efficiency**:
Current Running-Pod CPU or memory usage divided by the corresponding Running-
Pod resource request for one subject. It is optional, instantaneous, and
Prometheus/Mimir-backed; it is not lifetime utilization.
_Avoid_: accounting, usage-hours, overall efficiency

**Usage**:
Live CPU or memory consumption summed from `metrics.k8s.io` for matching
Running session pods. Session is the only Metrics surface that exposes usage.
_Avoid_: requests, lifetime totals, GPU utilization

**Session efficiency**:
Duration CPU or memory utilization for one session over its bounded window:
core-seconds used divided by core-seconds requested for CPU, and mean working
set divided by mean request for memory. The PromQL window starts at the
earliest matching Job `startTime`, ends at now or the latest completion time,
and is capped at six hours. This is not the five-minute instant ratio used by
User, Community, or Platform efficiency.
_Avoid_: instant efficiency, usage-hours, GPU efficiency

## Runtime boundaries

**Fresh report**:
A Redis snapshot inside its surface-specific fresh window: Session 30 seconds;
User 2 minutes; Community 5 minutes; Platform 5 minutes.
_Avoid_: live response, uncached response

**Serviceable stale report**:
A complete snapshot outside its fresh window but inside its serviceable window:
60 seconds for Session; 3 minutes for User; 10 minutes for Community; 30 minutes
for Platform. It may be served while a single request refreshes it.
_Avoid_: expired report, current data

**Retained snapshot**:
A Redis snapshot kept for recovery after serviceability ends: 3 minutes for
Session; 5 minutes for User; 15 minutes for Community; 60 minutes for Platform.
It is not returned by the API.
_Avoid_: stale response, valid cache

**Server-owned PromQL**:
A fixed query selected by Metrics for a known surface and resource. Supplying
the Prometheus/Mimir base endpoint enables the provider; absence disables it.
A caller cannot submit query text, labels, URLs, or headers.
_Avoid_: PromQL proxy, accounting query, user query

**External dependency**:
Redis, Prometheus/Mimir, or an OTLP metrics receiver supplied by the deployment. The
Metrics production chart references these services but does not install or
operate them.
_Avoid_: embedded production service, Metrics-owned database

**Application-state telemetry**:
Optional OTLP metrics describing request, cache, source, and readiness behavior.
The endpoint is external to the Metrics process and chart.
_Avoid_: business metric history, accounting series

## API terms

**Metrics report**:
One bounded `canfar.net/v1alpha1` `Metrics` response with one subject, one
observation time, resource values, and exactly one `Ready` and one `Cached`
condition.
_Avoid_: per-Pod inventory, time-series database, collection endpoint

**PartialData**:
The `Ready=False` reason used when an optional source fails but the primary
report is successfully served. This covers PromQL efficiency on any surface and
Session live usage or pod-state reads. The HTTP response remains 200.
_Avoid_: zero efficiency, accounting incomplete

**StaleData**:
The `Ready=False` reason used when a serviceable stale snapshot is served.
When the result is stale, this reason wins over `PartialData`.
_Avoid_: expired report, Redis unavailable

**Service-unavailable report**:
An HTTP 503 response when the primary Kueue or Session Job source fails and no
serviceable snapshot exists. Optional source failures alone do not produce 503
when primary data is complete.
_Avoid_: empty zero report, partial success
