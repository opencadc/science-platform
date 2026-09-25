# Metrics specification

This document is the **implementation-of-record** for Metrics behavior: the
HTTP API, its sources, the cache, and configuration. The
[Confluence API Contract](https://herzberg.atlassian.net/wiki/spaces/C/pages/2690809875/API+Contract)
remains the product-design authority; when they diverge, this file matches the
code and may lead Confluence until Confluence is updated. Terms are defined in
[`../CONTEXT.md`](../CONTEXT.md); decisions are recorded in
[`adr/README.md`](adr/README.md); platform-owned labels live in
[`skaha/docs/labels.md`](../../skaha/docs/labels.md).

## HTTP API

| Method | Path | Subject | Purpose |
| --- | --- | --- | --- |
| `GET` | `/apis/canfar.net/v1alpha1/metrics/platform/{platform}` | Platform | Configured ClusterQueue capacity and allocation |
| `GET` | `/apis/canfar.net/v1alpha1/metrics/user/{user}` | User | One user's LocalQueue reservations |
| `GET` | `/apis/canfar.net/v1alpha1/metrics/community/{community}` | Community | One community's ClusterQueue reservations |
| `GET` | `/apis/canfar.net/v1alpha1/metrics/session/{id}` | Session | One session's Job reservations and live usage |
| `GET` | `/healthz`, `/livez` | — | Process liveness |
| `GET` | `/readyz` | — | Process readiness |
| `GET` | `/openapi.json`, `/docs` | — | Generated OpenAPI description |

Every other method on a report path returns 405. Unknown paths return 404.

### Path parameters

Each subject is one Kubernetes label value: 1–63 characters matching
`^[A-Za-z0-9](?:[-A-Za-z0-9_.]{0,61}[A-Za-z0-9])?$`. Anything else, including
an encoded `/`, returns 400 before any source or cache access. Values are
case-sensitive and must equal the label value exactly:

- `{platform}` must equal `METRICS_PLATFORM_NAME` (default `canfar`); any
  other value returns 404.
- `{user}` is a `canfar.net/username` value; `{community}` a
  `canfar.net/community` value; `{id}` a `canfar.net/id` session id.

The API never accepts a Kubernetes selector, PromQL expression, URL, or
backend header from a caller.

### The `Metrics` report

A successful report is one `canfar.net/v1alpha1` `Metrics` object:

| Field | Type | Always | Meaning |
| --- | --- | --- | --- |
| `apiVersion` | string | yes | `canfar.net/v1alpha1` |
| `kind` | string | yes | `Metrics` |
| `metadata.name` | string | yes | DNS-safe report name: `<surface>-<subject>` when that is a lower-case DNS label of at most 63 characters; otherwise a slug plus a 12-character SHA-256 prefix of the subject |
| `spec.<surface>` | string | yes | Exactly one of `platform`, `user`, `community`, or `session`, echoing the path subject |
| `status.observedAt` | RFC 3339 UTC | yes | Conservative observation time: the oldest source timestamp in the report (see [Report time](#report-time)) |
| `status.reservingWorkloads` | integer ≥ 0 | yes | Kueue `reservingWorkloads` (Platform, User, Community) or active Job count (Session) |
| `status.resources[]` | array | yes | One entry per resource name, sorted by name; may be empty for a User, Community, or Session that reserves nothing |
| `status.conditions[]` | array | yes | Exactly one `Ready` and one `Cached` condition |

Resource entries by surface:

| Field | Platform | User, Community | Session | Meaning |
| --- | --- | --- | --- | --- |
| `name` | yes | yes | yes | Kubernetes resource name (`cpu`, `memory`, `nvidia.com/gpu`, …) |
| `capacity` | yes | — | — | Summed ClusterQueue nominal quota |
| `allocated` | yes | — | — | Summed ClusterQueue `flavorsUsage` total |
| `requests` | — | yes | yes | Kueue reservation (User, Community) or effective pod requests of active Jobs (Session) |
| `usage` | — | — | optional | Live `metrics.k8s.io` usage of Running session pods; `cpu` and `memory` only |
| `efficiency` | optional | optional | optional | PromQL efficiency ratio; `cpu` and `memory` only |

Quantities are plain decimal strings without exponents: CPU and extended
resources in base units (cores for `cpu`), `memory` and `ephemeral-storage` in
GiB with a `Gi` suffix. `capacity` and `allocated` always use the same unit for
a name. Efficiency is a plain non-negative ratio (it can exceed 1 when usage
exceeds requests). Optional fields are omitted, never zero-filled. A quantity
that is present but invalid fails the source read; it is never treated as zero.
An absent Kueue usage or reservation list means nothing is allocated or
reserved.

### Conditions

| Type | Status | Reason | Meaning |
| --- | --- | --- | --- |
| `Ready` | `True` | `Available` | The snapshot is fresh and every optional source that should contribute did |
| `Ready` | `False` | `PartialData` | The primary source is complete but an optional source failed (efficiency on any surface; Session usage or pod state) |
| `Ready` | `False` | `StaleData` | The snapshot is past its fresh window but inside its stale window; it wins over `PartialData` |
| `Cached` | `True` | `FreshHit` | A stored fresh snapshot answered the request, or the request shared a fill another request on the same replica started |
| `Cached` | `True` | `StaleHit` | A stored stale snapshot answered the request (a refresh is running or cooling down) |
| `Cached` | `False` | `Refreshed` | The source was read to answer this request |
| `Cached` | `Unknown` | `RedisUnavailable` | Redis was unreachable and this replica served its last known snapshot, or Redis failed while this request's fill was being published |

Both conditions carry `lastTransitionTime` equal to `status.observedAt`.
Every `Ready` pair describes serviceable data: the primary data of a stale
snapshot is complete and never older than its stale window (optional
enrichment may be missing; `StaleData` then hides `PartialData`), and
`PartialData` on Platform only means efficiency is missing.

### Response headers

Every report and every error response carries `Cache-Control: no-store`; Metrics
owns caching and reports are not conditional.

| Header | Present | Meaning |
| --- | --- | --- |
| `Cache-Control` | reports and errors | `no-store`; Metrics owns caching, intermediaries must not store reports |
| `Date` | always | Set once by the HTTP server |
| `Age` | success | Whole seconds since the snapshot's fill started |
| `Cache-Status` | success | RFC 9211: `metrics; hit; ttl=<n>` for a stored snapshot, `metrics; fwd=uri-miss; ttl=<n>` for a fill, plus `detail="redis-unavailable"` when served from process memory. `ttl` is the remaining fresh time in whole seconds; it is negative once the snapshot is stale |
| `Retry-After` | some 503s | `1`, only when the cache itself cannot answer: Redis is unavailable and this replica has no copy, or the replica is shutting down |

### Status codes

| Code | When |
| --- | --- |
| 200 | A fresh, stale, or last-known snapshot exists, or one was filled for this request |
| 400 | The path subject is not a valid label value |
| 404 | Unknown route; `{platform}` is not `METRICS_PLATFORM_NAME`; no LocalQueue carries the user; no configured ClusterQueue carries the community; no Job carries the session id |
| 405 | A method other than `GET` on a report route |
| 500 | An unexpected internal failure while producing the report; without a stale snapshot, that subject answers 500 on every replica until the failure cooldown ends |
| 503 | No serviceable snapshot and the primary source failed, is cooling down after a failure, or did not answer within the cold-wait deadline; or Redis is unavailable and this replica holds no snapshot (`Retry-After: 1`) |

Optional-source failures never produce a 503 when the primary source is
complete. Failure bodies are a sanitized Kubernetes `Status`; upstream URLs,
queries, credentials, subjects, and exception text are never serialized.

### Health endpoints

`/healthz` and `/livez` return `200 {"status": "ok"}` whenever the process
serves HTTP.

`/readyz` returns `200 {"status": "ready"}` once the process has proven, in one
validation, that Redis answers and that every configured ClusterQueue is
readable and well formed. Until then it returns `503 {"status": "not ready"}`
and each probe re-runs one shared, bounded validation. Readiness **latches**:
after the first success it stays 200 until shutdown. A later shared Redis or
Kueue outage therefore degrades reports (stale data, then sanitized 503s)
instead of removing every replica from the Service at once, while a new pod
that cannot reach its dependencies never becomes ready and never replaces a
working one during a rollout. LocalQueue and Job list access are probed at
startup and logged, but never affect readiness; PromQL is not contacted until
the first efficiency read. A pod that cannot reach Redis at all during startup
logs `Redis is unavailable at startup` and exits, so it restarts rather than
waiting unready.

### Examples

A fresh Platform fill (`GET .../platform/canfar`):

```http
HTTP/1.1 200 OK
cache-control: no-store
age: 0
cache-status: metrics; fwd=uri-miss; ttl=299
content-type: application/json
```

```json
{
  "apiVersion": "canfar.net/v1alpha1",
  "kind": "Metrics",
  "metadata": {"name": "platform-canfar"},
  "spec": {"platform": "canfar"},
  "status": {
    "observedAt": "2026-09-25T06:23:46.833000Z",
    "reservingWorkloads": 2,
    "resources": [
      {"name": "cpu", "capacity": "3.3", "allocated": "0.3", "efficiency": "0.12373696406419614"},
      {"name": "memory", "capacity": "3.25Gi", "allocated": "0.09375Gi", "efficiency": "0.016398111979166668"}
    ],
    "conditions": [
      {"type": "Ready", "status": "True", "reason": "Available", "lastTransitionTime": "2026-09-25T06:23:46.833000Z"},
      {"type": "Cached", "status": "False", "reason": "Refreshed", "lastTransitionTime": "2026-09-25T06:23:46.833000Z"}
    ]
  }
}
```

A Session snapshot 51 seconds old (30-second fresh window), served while the
one request that found it stale refreshes it; stale reads look the same on
every surface:

```http
HTTP/1.1 200 OK
cache-control: no-store
age: 51
cache-status: metrics; hit; ttl=-21
```

```json
"conditions": [
  {"type": "Ready", "status": "False", "reason": "StaleData", "lastTransitionTime": "2026-09-25T06:24:27Z"},
  {"type": "Cached", "status": "True", "reason": "StaleHit", "lastTransitionTime": "2026-09-25T06:24:27Z"}
]
```

A User report (`GET .../user/bob`); Community reports have the same shape with
`spec.community`:

```json
{
  "apiVersion": "canfar.net/v1alpha1",
  "kind": "Metrics",
  "metadata": {"name": "user-bob"},
  "spec": {"user": "bob"},
  "status": {
    "observedAt": "2026-09-25T06:40:02.113000Z",
    "reservingWorkloads": 2,
    "resources": [
      {"name": "cpu", "requests": "0.41", "efficiency": "0.32588180288633123"},
      {"name": "memory", "requests": "0.296875Gi", "efficiency": "0.010636613175675675"}
    ],
    "conditions": [
      {"type": "Ready", "status": "True", "reason": "Available", "lastTransitionTime": "2026-09-25T06:40:02.113000Z"},
      {"type": "Cached", "status": "True", "reason": "FreshHit", "lastTransitionTime": "2026-09-25T06:40:02.113000Z"}
    ]
  }
}
```

A live desktop session with one desktop-app child (`GET .../session/e2e-live`).
The desktop Job's init container requests 0.2 CPU and its app container 0.1, so
its effective request is 0.2; the child requests 0.1. `observedAt` is the
PodMetrics sample time:

```json
{
  "apiVersion": "canfar.net/v1alpha1",
  "kind": "Metrics",
  "metadata": {"name": "session-e2e-live"},
  "spec": {"session": "e2e-live"},
  "status": {
    "observedAt": "2026-09-25T06:23:35Z",
    "reservingWorkloads": 2,
    "resources": [
      {"name": "cpu", "requests": "0.3", "usage": "0.099744619", "efficiency": "0.619458027899771"},
      {"name": "memory", "requests": "0.09375Gi", "usage": "0.001537322998046875Gi", "efficiency": "0.011311848958333334"}
    ],
    "conditions": [
      {"type": "Ready", "status": "True", "reason": "Available", "lastTransitionTime": "2026-09-25T06:23:35Z"},
      {"type": "Cached", "status": "False", "reason": "Refreshed", "lastTransitionTime": "2026-09-25T06:23:35Z"}
    ]
  }
}
```

A session whose Jobs have all finished still exists and reserves nothing. This
one ran for a few seconds, under the one-minute efficiency minimum:

```json
"status": {
  "observedAt": "2026-09-25T06:23:47.011000Z",
  "reservingWorkloads": 0,
  "resources": [],
  "conditions": [
    {"type": "Ready", "status": "True", "reason": "Available", "lastTransitionTime": "2026-09-25T06:23:47.011000Z"},
    {"type": "Cached", "status": "False", "reason": "Refreshed", "lastTransitionTime": "2026-09-25T06:23:47.011000Z"}
  ]
}
```

A report whose optional efficiency read failed:

```json
"conditions": [
  {"type": "Ready", "status": "False", "reason": "PartialData", "lastTransitionTime": "2026-09-25T06:40:02Z"},
  {"type": "Cached", "status": "False", "reason": "Refreshed", "lastTransitionTime": "2026-09-25T06:40:02Z"}
]
```

A report served from process memory during a Redis outage:

```http
HTTP/1.1 200 OK
age: 300
cache-status: metrics; hit; ttl=-1; detail="redis-unavailable"
```

```json
"conditions": [
  {"type": "Ready", "status": "False", "reason": "StaleData", "lastTransitionTime": "2026-09-25T06:23:46.833000Z"},
  {"type": "Cached", "status": "Unknown", "reason": "RedisUnavailable", "lastTransitionTime": "2026-09-25T06:23:46.833000Z"}
]
```

Failures:

```json
{"apiVersion": "v1", "kind": "Status", "status": "Failure", "reason": "NotFound", "message": "The requested resource was not found.", "code": 404}
```

```http
HTTP/1.1 503 Service Unavailable
cache-control: no-store
retry-after: 1
```

```json
{"apiVersion": "v1", "kind": "Status", "status": "Failure", "reason": "ServiceUnavailable", "message": "The requested metrics report could not be produced.", "code": 503}
```

`reason` is `BadRequest` for 400, `NotFound` for 404, `MethodNotAllowed` for
405 (with an `Allow` header), `InternalError` for 500, and
`ServiceUnavailable` for 503.

## Sources

All Kubernetes reads use the pod's ServiceAccount (or a kubeconfig in
development) through one reader that maps HTTP statuses itself: a denied
request is a single request that fails the source read, and an expired token is
refreshed in place and retried once. List reads (LocalQueues, Jobs, Pods) are
served from the API server's watch cache, because before Kubernetes 1.31 a
list without a resource version is read from etcd and scans the whole
namespace. A cached list can trail by milliseconds, so an empty result is
confirmed with one consistent read before a subject is reported missing.
Kueue reads use the pinned
`kueue.x-k8s.io/v1beta2` API. Cohorts are not a source, and Platform membership
is never discovered by listing every ClusterQueue in the cluster.

### Platform

Metrics reads each configured ClusterQueue by name. It sums nominal quota
across every resource group and flavor into `capacity`,
`status.flavorsUsage[].resources[].total` into `allocated` (Kueue's total
already includes borrowed quota), and `reservingWorkloads` into the report
count. Every allocated resource must also have capacity. The configured set is
the complete Platform boundary:

```text
METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES='["cq-astronomy","cq-physics"]'
```

Each configured ClusterQueue carries one `canfar.net/community` label. A
missing, forbidden, or malformed configured ClusterQueue fails the read;
Platform never becomes a partial sum.

### User

Metrics lists LocalQueues labelled exactly `canfar.net/username=<user>` in
every configured namespace, reading ClusterQueues at the same time. Every
matching LocalQueue must:

- be returned from the namespace that was queried;
- reference a configured ClusterQueue;
- carry a `canfar.net/community` equal to that ClusterQueue's label; and
- have a distinct `(namespace, name)` and, when present, UID.

Any matching LocalQueue that fails a check fails the User read (a 503 when no
snapshot exists); it is never silently dropped. The report sums
`status.flavorsReservation[].resources[].total` into `requests` and
`reservingWorkloads` into the count. No match is a 404.

### Community

Metrics filters the configured ClusterQueues by
`canfar.net/community=<community>` and sums their
`flavorsReservation` totals and `reservingWorkloads`. No match is a 404.
Community membership is not derived from LocalQueues or Cohorts.

### Session

Metrics lists Jobs labelled exactly `canfar.net/id=<id>` in every configured
namespace, including desktop-app child Jobs that share the id, and lists the
session's Pods at the same time. No matching Job is a 404.

- A Job **reserves** quota while it has neither a true `Complete` nor a true
  `Failed` condition and is not suspended (a suspended Job is waiting for Kueue
  admission). `reservingWorkloads` is the number of such Jobs.
- `requests` sums each reserving Job's **effective pod request**, computed as
  Kubernetes and Kueue do: per resource, the larger of the init-container peak
  (each regular init container plus the native sidecars started before it) and
  the app containers plus all native sidecars, plus any pod overhead.
  A finished session returns 200 with no resources.
- `usage` sums `metrics.k8s.io` CPU and memory of the session's Running pods,
  read only in namespaces that have one. It is omitted when no pod is Running.
- A Pod-list failure keeps the Job data and marks the report `PartialData`.

The session window used by efficiency starts at the earliest Job `startTime`.
It ends now while any Job is unfinished; otherwise at the latest terminal time
(`completionTime`, or the transition time of the terminal condition).

GPU requests are reported; GPU usage and efficiency are not.

## Efficiency

Efficiency is optional and activated only by
`METRICS_PROVIDERS__PROMQL__BASE_URL`. Metrics POSTs fixed, server-owned PromQL
to `{BASE_URL}/api/v1/query`, adding `X-Scope-OrgID` when
`METRICS_PROVIDERS__PROMQL__MIMIR_TENANT_ID` is set. Queries use the
kube-state-metrics label names `label_canfar_net_username`,
`label_canfar_net_community`, and `label_canfar_net_id`, scope every selector
to `METRICS_CLUSTER_NAME` and the configured namespaces, and join the
`Running` pod phase.

Efficiency is read after the primary source proves the subject exists, and
only within the time left in the fill (at most 5 seconds). Platform, User, and
Community efficiency is read only when `reservingWorkloads` is above zero.
Session efficiency is read only when the session has active Jobs and its
window is at least one minute long; efficiency is reported per requested
resource, so a session with no requests has nothing to report. A subject that
is skipped reports no efficiency and stays `Available`. A failure, timeout, or an unusable vector
(missing CPU or memory, non-finite, stale samples) omits efficiency and marks
the report `PartialData`.

Platform, User, and Community efficiency is an instant ratio over labelled
Running pods (Platform requires both labels, User requires a community label,
Community requires a username label):

```text
cpu    = sum(rate(container CPU seconds[5m])) / sum(CPU requests)
memory = sum(container working set)            / sum(memory requests)
```

Session efficiency is a duration ratio over the session window, capped at six
hours and evaluated at the window end. Heavy selectors are scoped to pods of
the session's Jobs (`<job-name>-<suffix>`). Requests are integrated only while
each pod was `Running`, on one fixed 60-second subquery step:

```text
cpu    = CPU seconds used over the window / (Σ steps of CPU requests while Running × 60 s)
memory = Σ steps of working set while Running / Σ steps of memory requests while Running
```

The integrals are exact to within one step at each pod's start and end.
Sessions younger than one minute report no efficiency.

## Cache

Every replica shares one external Redis. Each subject has two keys,
`<prefix><schema>:<source>:<query>:<surface>:{<digest>}:value` and `…:lease`,
where the digest is an HMAC of the subject identity (subject values never
appear in key names) and the braces keep both keys in one Redis Cluster slot.

### Windows

A snapshot has exactly two stages:

| Surface | Fresh | Stale (Redis lifetime) |
| --- | ---: | ---: |
| Platform | 5 minutes | 10 minutes |
| User | 2 minutes | 4 minutes |
| Community | 5 minutes | 10 minutes |
| Session | 30 seconds | 60 seconds |

A snapshot is written with a Redis TTL equal to its stale window, measured from
the start of the fill that produced it, and its stage is read from that
remaining TTL: no replica or source clock is compared. When the TTL reaches
zero the snapshot no longer exists; nothing is retained or served beyond the
stale window.

### Request flow

- **Fresh**: serve it.
- **Stale**: serve it immediately. The one request whose atomic Redis read
  finds the snapshot stale and the lease free claims the lease and starts a
  detached refresh; every other request on every replica serves the stale
  snapshot without touching the source.
- **Absent**: one request claims the lease and fills; concurrent requests on
  the same replica share it, and other replicas poll Redis until the snapshot
  is published, a failure is recorded, or the cold-wait deadline passes.
- **Not found**: a subject-level not-found is published for the fresh window,
  so every replica answers 404 without re-reading the source.
- **Failed fill**: the lease becomes a failure cooldown (5 seconds, at most
  the fresh window and never longer than the stale snapshot it protects). During the cooldown stale
  snapshots keep being served and cold requests fail fast, with 503 after a
  source failure or 500 after an internal one; no replica calls the source
  until it ends.
- **Redis outage**: the source is never called without a lease. A replica
  serves its in-memory copy of a snapshot it last saw, with its real stage,
  until that snapshot's Redis TTL would have ended; otherwise 503 with
  `Retry-After: 1`.

The lease lasts one 10-second fill plus two 0.5-second Redis commands plus a
2-second margin (13 seconds) and always ends before a cold waiter gives up
(15 seconds), so waiters
take over the lease of a crashed replica. Every owner exit (publish, cooldown,
release) is fenced on the lease token in Lua, so an owner that stalled past its
lease can never overwrite its successor. A client-side retry of the claim
recognizes its own token. Snapshot payloads are MAC-authenticated against their
key name; an unreadable payload is overwritten by one fill.

The cache schema revision is part of every key. A change to the stored payload
or its meaning bumps the revision, so replicas of different versions use
disjoint keys during a rollout and a rollback needs no cleanup.

### Report time

`status.observedAt` is the oldest of the primary observation time, the live
usage sample time, and the instant-efficiency sample time. Session duration
efficiency describes the whole window and does not age the report. `Age` and
`Cache-Status` come from the cache's own clock (time since the fill started).

## Configuration

Settings are environment variables with the `METRICS_` prefix and `__` between
nested names; list values are JSON arrays. Startup rejects invalid settings,
placeholder cache keys, and retired names, printing one line per problem
(never a value) and exiting with status 2. See
[`environment-contracts.md`](environment-contracts.md) for the full list,
defaults, and retired names.

| Variable | Required | Default | Meaning |
| --- | --- | --- | --- |
| `METRICS_CLUSTER_NAME` | yes | — | Lower-case DNS identity; part of every cache identity and the PromQL `cluster` label |
| `METRICS_REDIS_URL` | yes | — | `redis://` or `rediss://` URL of the shared Redis |
| `METRICS_CACHE__KEY_SECRET` | yes | — | At least 32 bytes of random data for key digests and payload MACs |
| `METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES` | yes | — | JSON array: the complete Platform ClusterQueue set |
| `METRICS_PROVIDERS__KUEUE__NAMESPACES` | yes | — | JSON array: namespaces searched for LocalQueues, Session Jobs, Pods, and PodMetrics |
| `METRICS_PLATFORM_NAME` | no | `canfar` | The only accepted `{platform}` |
| `METRICS_PROVIDERS__PROMQL__BASE_URL` | no | — | Enables efficiency |
| `METRICS_OTEL__METRICS_ENABLED` + `METRICS_OTEL__EXPORTER_OTLP_ENDPOINT` | no | off | Enable OTLP application metrics |

## Telemetry

When enabled, OTLP/HTTP application metrics (no traces or logs) are exported
with `service.version` set to the package release and `service.instance.id` to
the pod UID (or host name):

| Instrument | Unit | Attributes |
| --- | --- | --- |
| `canfar.metrics.compute.duration` | s | `metrics.scope`, `result.status` |
| `canfar.metrics.cache.lookups` | 1 | `cache.backend`, `cache.result` (`hit`, `stale`, `miss`, `invalid` for a payload that failed authentication or decoding), `metrics.scope` |
| `canfar.metrics.cache.age` | s | as lookups |
| `canfar.metrics.cache.leases` | 1 | `lease.outcome` (`acquired`, `contended`, `cooldown`, `error`), `metrics.scope` |
| `canfar.metrics.cache.fill.duration` | s | `result.status` (`ok`, `not_found`, `timeout`, `error`, `cancelled`), `metrics.scope` |
| `canfar.metrics.provider.duration` / `.errors` | s / 1 | `provider.name` (`kueue`, `session`, `promql`), `metrics.scope`, `result.status` |
| `canfar.metrics.redis.duration` | s | `db.operation.name` (`ping`, `observe`, `publish`, `cooldown`, `release`), `result.status` |
| `canfar.metrics.redis.health` | 1 | 1 after a successful Redis command, 0 after a failed one |
| `canfar.metrics.readiness` | 1 | 1 once readiness latches, 0 before and after shutdown |
| `canfar.metrics.lifecycle.duration` | s | `lifecycle.operation`, `result.status` |

`metrics.scope` is `platform`, `user`, `community`, or `session`. Unknown
attribute values collapse to `other`. Subject values, selectors, PromQL,
credentials, and full backend URLs are never attributes. Lookups count one per
coalesced request group, so `compute.duration` count minus `cache.lookups`
count approximates the requests that shared another's lookup (requests
rejected before a lookup, such as a wrong `{platform}`, count only in
`compute.duration`).

Logs are privacy-safe: failed fills are logged once, by the fill owner, with
the surface, failure category, and exception chain (types, HTTP status, and
Metrics' own fixed messages); 5xx responses are logged with their code and route
template, never the path.

## Consumers

Skaha is the production consumer:

- `view=stats` reads the Platform report: `status.observedAt` (as
  `lastUpdate`), every `resources[]` entry's `name`, `capacity`, and
  `allocated`, and the condition pairs, which must match the table above. Any
  valid `Ready` pair is accepted; an unreachable Metrics or a malformed report
  is a 503 in Skaha.
- When `SKAHA_POD_METRICS_SOURCE=backend`, the session list reads
  `spec.session` and `resources[].usage` from the Session report.

## Package shape

| Module | Responsibility |
| --- | --- |
| `api/v1alpha1` | Routes, response assembly, conditions |
| `core/settings` | Environment settings and validation |
| `core/runtime` | Wiring, readiness, lifecycle |
| `core/factory` | FastAPI app, health routes, error handlers |
| `services/metrics` | Surface dispatch and cache-outcome mapping |
| `services/snapshots` | Snapshot assembly: primary source plus bounded optional enrichment |
| `services/models`, `services/resources` | Observations and Kubernetes quantity arithmetic |
| `providers/kube` | Shared Kubernetes reads: status mapping, paging, fan-out |
| `providers/kueue` | ClusterQueue and LocalQueue aggregation |
| `providers/session` | Session Job aggregation and window |
| `providers/kubemetrics` | Session live usage from `metrics.k8s.io` |
| `providers/promql` | Fixed efficiency queries |
| `cache` | Two-stage Redis snapshots, fenced lease, outage copy |
| `schemas` | Public `Metrics` and `Status` models |
| `telemetry` | Optional OTLP application metrics |
| `http_cache` | `Age` and `Cache-Status` headers |
| `dev` | Local kind lifecycle (development only, not in the wheel) |

There is no accounting package, Cohort provider, Pod-inventory-as-primary
provider, or Metrics-owned monitoring stack.

## Validation boundary

Unit tests cover aggregation, the Lua-backed cache with several simulated
replicas (including a randomized single-flight property test), conditions,
headers, and query construction. Integration tests run against a real Redis and
a disposable kind stack with Kueue, kube-state-metrics, Prometheus, and an OTLP
receiver. Those fixtures are test dependencies only and do not change the
production ownership boundary.
