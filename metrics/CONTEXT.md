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
that share the same id. A session exists while any matching Job exists, even
after every Job has finished.
_Avoid_: pod-name prefix, caller-supplied label selectors

**Active Job**:
A Session Job that has neither a true `Complete` nor a true `Failed` condition
and is not suspended. Only active Jobs hold quota; a suspended Job is waiting
for Kueue admission.
_Avoid_: running Pod, admitted workload, Job with a Pod

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

**Effective pod request**:
The Session request of one active Job, computed as Kubernetes and Kueue do:
per resource, the larger of the init-container peak (each regular init
container plus the native sidecars started before it) and the app containers
plus all native sidecars, plus pod overhead. It matches the quota Kueue
reserves for the Job.
_Avoid_: sum of every container, limits, usage

**Pending workload**:
A Kueue workload counted by `pendingWorkloads`: it is waiting for admission.
It is not the public Metrics workload count.
_Avoid_: reserving workload, admitted workload

**Reserving workload**:
A Kueue workload counted by `reservingWorkloads`: it is admitted at the
cluster level and is holding or progressing through a quota reservation. It is
not a Kubernetes Pod-phase count. For a Session the count is its active Jobs.
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
Prometheus/Mimir-backed; it is not lifetime utilization. It is read only for
a subject with reserving workloads; an idle subject reports none and stays
`Available`.
_Avoid_: accounting, usage-hours, overall efficiency

**Usage**:
Live CPU or memory consumption summed from `metrics.k8s.io` for matching
Running session pods. Session is the only Metrics surface that exposes usage.
_Avoid_: requests, lifetime totals, GPU utilization

**Session window**:
The interval a Session's duration efficiency describes. It starts at the
earliest matching Job `startTime` and ends now while any Job is unfinished,
otherwise at the latest terminal time (`completionTime`, or the transition
time of the terminal condition). PromQL reads at most its last six hours.
_Avoid_: pod lifetime, Redis window

**Session efficiency**:
Duration CPU or memory utilization for one session over its window: CPU
seconds used divided by CPU request-seconds, and summed working set divided by
summed memory request, where requests count only while each session pod was
`Running`, on a fixed 60-second step. A window shorter than one minute reports
none. This is not the five-minute instant ratio used by User, Community, or
Platform efficiency.
_Avoid_: instant efficiency, usage-hours, GPU efficiency

## Runtime boundaries

**Snapshot**:
One report, with complete primary data, stored in the shared Redis under a
key derived from its subject. Its Redis TTL is its stale window, measured from the start of the
fill that produced it; its stage is read from the remaining TTL. A snapshot
has exactly two stages and does not exist after the stale window.
_Avoid_: retained snapshot, cache history, backup copy

**Fresh report**:
A snapshot inside its surface-specific fresh window: Platform 5 minutes; User
2 minutes; Community 5 minutes; Session 30 seconds.
_Avoid_: live response, uncached response

**Stale report**:
A snapshot past its fresh window but inside its stale window: Platform 10
minutes; User 4 minutes; Community 10 minutes; Session 60 seconds. It is
served immediately while exactly one request refreshes it.
_Avoid_: expired report, retained snapshot, current data

**Refresh lease**:
The short Redis claim, fenced by a random token, that makes one request the
only caller of the source for one subject. It lasts one fill plus two Redis
commands and a 2-second margin, and always ends before a cold waiter gives up,
so a crashed owner is replaced.
_Avoid_: lock, mutex, leader

**Refresh owner**:
The request that claimed the refresh lease. For a stale snapshot it refreshes
in the background while it and every other request serve the stale report.
_Avoid_: producer, background collector, scheduler

**Failure cooldown**:
A short marker left in the lease key when a fill fails. While it lasts, no
replica calls the source; stale reports keep being served and cold requests
fail fast (503 after a source failure, 500 after an internal one).
_Avoid_: negative cache, retry loop, circuit breaker

**Outage copy**:
The last snapshot a replica read or published, kept in process memory and
served only while Redis is unreachable and only until that snapshot's Redis
TTL would have ended.
_Avoid_: second cache, L1 cache for normal reads, fallback source

**Latched readiness**:
`/readyz` succeeds after one validation proves Redis answers and every
configured ClusterQueue is readable and well formed, and then stays ready
until shutdown. A later shared outage degrades reports instead of removing
every replica at once.
_Avoid_: per-probe dependency check, liveness

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
Session live usage or Pod-list reads. The HTTP response remains 200.
_Avoid_: zero efficiency, accounting incomplete

**StaleData**:
The `Ready=False` reason used when a stale report is served, from Redis or
from the outage copy. When the result is stale, this reason wins over
`PartialData`.
_Avoid_: expired report, Redis unavailable

**RedisUnavailable**:
The `Cached=Unknown` reason used when Redis did not answer and the replica
served its outage copy.
_Avoid_: stale data, source failure

**Service-unavailable report**:
An HTTP 503 response when no snapshot exists and the primary Kueue or Session
Job source failed, is in a failure cooldown, or did not answer before the
cold-wait deadline; or when Redis is unreachable and the replica has no outage
copy (with `Retry-After: 1`). Optional source failures alone do not produce
503 when primary data is complete. An internal failure is a 500, not a 503.
_Avoid_: empty zero report, partial success
