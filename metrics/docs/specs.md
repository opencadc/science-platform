# Metrics observable specification

This document is the **implementation-of-record** for Metrics behavior. The
[Confluence API Contract](https://herzberg.atlassian.net/wiki/spaces/C/pages/2690809875/API+Contract)
remains the product-design authority; when they diverge, this file matches the
code and may lead Confluence until Confluence is updated.

Platform-owned session and queue labels live in
[`skaha/docs/labels.md`](../../skaha/docs/labels.md). Metrics selector rules
for those labels are stated below.

## HTTP surface

```text
GET /apis/canfar.net/v1alpha1/metrics/user/{username}
GET /apis/canfar.net/v1alpha1/metrics/community/{community}
GET /apis/canfar.net/v1alpha1/metrics/platform/{platform}
GET /apis/canfar.net/v1alpha1/metrics/session/{id}
GET /healthz
GET /livez
GET /readyz
```

There is one asynchronous request path per report:

```text
GET route
  -> validate subject/path
  -> read shared Redis snapshot
  -> fresh: return
  -> stale: return stale; one request-triggered lease may refresh
  -> cold/unserviceable: one Redis lease fills; followers await publication
  -> collect primary source (Kueue or Session Jobs)
  -> optionally collect efficiency and Session usage
  -> assemble Metrics response and publish snapshot
```

The four report routes return a `canfar.net/v1alpha1` `Metrics` object. The
path selects exactly one subject field. The route never accepts a Kubernetes
selector, PromQL expression, query URL, or backend header.

```yaml
apiVersion: canfar.net/v1alpha1
kind: Metrics
spec:
  user: bob
status:
  observedAt: "2026-08-25T12:00:00Z"
  reservingWorkloads: 2
  resources:
    - name: cpu
      requests: "1.5"
      efficiency: "0.42"
  conditions:
    - type: Ready
      status: "True"
      reason: Available
    - type: Cached
      status: "False"
      reason: Refreshed
```

The example uses a User shape. Community uses the same `requests` and
optional `efficiency` fields. Session uses the same workload row shape and
adds optional `usage` for CPU and memory. Platform uses `capacity`, `allocated`,
and optional `efficiency` instead of `requests`.

`/healthz` and `/livez` report process liveness. `/readyz` reports that the
Platform surface has a safe serving path and every shared cache is available.
It is not a substitute for a report's `Ready` condition.

## Report fields

All surfaces expose:

- one subject in `spec.user`, `spec.community`, `spec.platform`, or
  `spec.session`;
- `status.observedAt`, the conservative observation time for the report;
- `status.reservingWorkloads`, the count of reserving workloads for that
  surface (Kueue queues for User, Community, and Platform; matching Jobs for
  Session); and
- exactly one `Ready` and one `Cached` condition.

User and Community resources expose:

- `name`, the Kubernetes resource name;
- `requests`, the aggregate Kueue reservation; and
- optional `efficiency` for current CPU or memory usage.

Session resources expose:

- `name`, the Kubernetes resource name;
- `requests`, the aggregate Job template reservation;
- optional `usage` for live CPU or memory consumption; and
- optional `efficiency` for duration CPU or memory utilization.

Platform resources expose:

- `name`, the Kubernetes resource name;
- `capacity`, summed nominal quota;
- `allocated`, summed `flavorsUsage.resources[].total`; and
- optional current CPU or memory `efficiency`.

Resource names are open-ended. CPU is represented in decimal cores, memory in
GiB, and extended resources in their Kubernetes base units. Capacity and
allocated use the same unit for a resource name. Invalid or missing upstream
quantities fail the source read; they are not treated as zero.

## Kueue source contract

All Kueue reads use the pinned `kueue.x-k8s.io/v1beta2` API. Cohorts are not a
source. The provider never discovers Platform membership by listing every
ClusterQueue in the cluster.

### User

Metrics lists LocalQueues in every namespace from
`METRICS_PROVIDERS__KUEUE__NAMESPACES`, selects exact
`canfar.net/username=<username>` labels, and validates that:

- the LocalQueue references a ClusterQueue from
  `METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES`;
- its `canfar.net/community` equals the referenced ClusterQueue's community
  label; and
- its Kubernetes object identity is distinct from every other matching
  LocalQueue. Multiple distinct LocalQueues may carry the same User and
  Community labels and are all aggregated. A repeated `(namespace, name)`
  identity, or a conflicting UID when present, is corrupt metadata.

For each valid LocalQueue, it adds:

- `status.flavorsReservation.resources[].total` to `resources[].requests`; and
- `status.reservingWorkloads` to `status.reservingWorkloads`.

All configured namespaces must be read successfully for a complete cold User
report. No Kubernetes Pod list is part of this source contract.

### Community

Metrics filters the configured ClusterQueues by
`canfar.net/community=<community>`. Every matching ClusterQueue contributes
its `flavorsReservation.resources[].total` and `reservingWorkloads`. No match
is a 404. Community membership is not derived from LocalQueues or Cohorts.

### Platform

Metrics reads every configured ClusterQueue. It sums nominal quota into
`capacity`, `flavorsUsage.resources[].total` into `allocated`, and
`reservingWorkloads` into the report count. Kueue's `total` already includes
borrowed quota; there is no separate `borrowed` field. Cohorts are out of
scope.

The configured ClusterQueue set is the complete Platform boundary:

```text
METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES='["cq-astronomy","cq-physics"]'
```

Each configured ClusterQueue maps to one Community. A missing, inaccessible,
or malformed configured ClusterQueue is a primary-source failure; Platform
must not silently become a partial sum.

The `{platform}` path segment must equal `METRICS_PLATFORM_NAME` (default
`canfar`). A mismatch returns 404; it does not mean ClusterQueues are missing.

### Session

Metrics lists Jobs in every namespace from
`METRICS_PROVIDERS__KUEUE__NAMESPACES`, selects exact
`canfar.net/id=<id>` labels, and aggregates every matching Job including
desktop-app children that share the id.

For each matching Job, Metrics adds:

- summed non-pause container template `requests` to `resources[].requests`.

`status.reservingWorkloads` is the count of matching Jobs, including
desktop-app children that share the id. GPU requests are included; GPU usage
and efficiency are not exposed.

Optional live `usage` sums `metrics.k8s.io` CPU and memory for matching
Running pods and is omitted, not zero, when no Running pod metrics exist.
Optional Session `efficiency` uses fixed PromQL joined on
`label_canfar_net_id` over the bounded session window. No match is a 404.

## Optional efficiency contract

Efficiency activation is endpoint-only. If
`METRICS_PROVIDERS__PROMQL__BASE_URL` is absent, efficiency is disabled. If it
is present, Metrics POSTs form-encoded queries to
`{BASE_URL}/api/v1/query` with fixed server-owned PromQL. There is no
separate PromQL enable setting, and the service does not accept caller query
text or selector input. When `METRICS_PROVIDERS__PROMQL__MIMIR_TENANT_ID` is
set, requests include `X-Scope-OrgID`.

The query catalog uses these exporter label names:

```text
label_canfar_net_username
label_canfar_net_community
label_canfar_net_id
```

User queries select `label_canfar_net_username=<subject>` and require
`label_canfar_net_community!=""`. Community queries select
`label_canfar_net_community=<subject>` and require
`label_canfar_net_username!=""`. Platform queries require both labels
non-empty. Session queries select `label_canfar_net_id=<id>`. Every query
joins Running Pods via `kube_pod_status_phase{phase="Running"}`.

User, Community, and Platform efficiency remain current five-minute instant
ratios:

```text
CPU efficiency    = Running-Pod CPU usage rate / Running-Pod CPU requests
Memory efficiency = Running-Pod memory working set / Running-Pod memory requests
```

Session efficiency is duration utilization over a bounded window (earliest
matching Job `startTime` to now or latest completion, capped at six hours).
It is omitted when no Job has `startTime` yet.

The ratio is omitted for a zero denominator, an invalid vector, or an
unavailable optional backend. These are not lifetime usage fields, accounting
period, checkpoints, producer, or usage history.

## Cache policy

One shared external Redis stores versioned snapshots and distributed
single-flight leases. Subject-bearing key segments are protected digests; raw
User and Community values do not appear in Redis key paths. Snapshot identity
also includes schema and query revisions.

| Surface | Fresh | Serviceable stale | Retained only |
| --- | ---: | ---: | ---: |
| Session | 30 seconds | 60 seconds | 3 minutes |
| User | 2 minutes | 3 minutes | 5 minutes |
| Community | 5 minutes | 10 minutes | 15 minutes |
| Platform | 5 minutes | 30 minutes | 60 minutes |

Fresh snapshots return immediately. One lease winner refreshes a stale
snapshot; concurrent requests return that stale snapshot. A stale hit may
schedule one **request-triggered** background refresh; there is no periodic
refresh worker. A cold or unserviceable miss has one fill winner and waiting
followers. The fill is bounded and published once to Redis.

Missing User, Community, and Session subjects use a bounded authenticated
Redis terminal outcome so concurrent callers receive the same 404. A
process-local L1 copy may serve a previously known serviceable snapshot during
a Redis outage; it never extends the serviceable window and is not a second
shared cache.

## Conditions, status codes, and headers

Ready reasons:

| Reason | Meaning |
| --- | --- |
| `Available` | Primary source complete; optional sources usable when configured |
| `PartialData` | Primary succeeded; an optional source failed (PromQL on any surface, or Session usage / pod-state) |
| `StaleData` | Serving a serviceable stale snapshot (`Ready=False`; wins when the result is stale) |

Cached reasons: `FreshHit`, `StaleHit`, `Refreshed`, `RedisUnavailable`.

Status codes:

- A User with no matching LocalQueue returns 404.
- A Community with no matching configured ClusterQueue returns 404.
- A Session with no matching Job returns 404.
- `{platform}` ≠ `METRICS_PLATFORM_NAME` returns 404.
- A primary Kueue failure returns a serviceable cached report when one exists;
  otherwise it returns 503.
- A primary Session Job failure returns a serviceable cached report when one
  exists; otherwise it returns 503.
- A Prometheus/Mimir failure while
  `METRICS_PROVIDERS__PROMQL__BASE_URL` is present returns 200 with primary
  values, efficiency omitted, and `Ready=False`/`PartialData`.
- A kube-metrics failure while Running session pods exist returns 200 with Job
  values, usage omitted, and `Ready=False`/`PartialData`.
- A Pod list failure for an otherwise valid Session returns 200 with Job values
  and `Ready=False`/`PartialData`.
- Responses use `Cache-Control: no-store`; `Age` and `Cache-Status` describe
  internal snapshot handling.
- Error bodies use a sanitized Kubernetes `Status` envelope. Upstream URLs,
  query text, credentials, and exception details are not serialized.
- `Retry-After: 1` is set only for `metrics_cache_unavailable` 503 responses.

## Configuration contract

Settings are environment-only, use the `METRICS_` prefix, and use `__` for
nested fields. List values are JSON arrays:

```text
METRICS_CLUSTER_NAME
METRICS_PLATFORM_NAME
METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES
METRICS_PROVIDERS__KUEUE__NAMESPACES
METRICS_REDIS_URL
METRICS_CACHE__KEY_SECRET
METRICS_PROVIDERS__PROMQL__BASE_URL
METRICS_PROVIDERS__PROMQL__MIMIR_TENANT_ID
METRICS_OTEL__METRICS_ENABLED
METRICS_OTEL__EXPORTER_OTLP_ENDPOINT
```

`METRICS_PROVIDERS__PROMQL__BASE_URL` is optional and is the complete PromQL
activation contract: present means enabled; absent means disabled.
OTLP export requires both `METRICS_OTEL__METRICS_ENABLED=true` and
`METRICS_OTEL__EXPORTER_OTLP_ENDPOINT`.

Kubernetes endpoint and credentials come from the in-cluster service account
or kubeconfig. Redis, Prometheus/Mimir, and the OTLP metrics receiver are
external deployment services. The production Helm chart owns no instance of
them. Deploy-facing detail lives in
[`environment-contracts.md`](environment-contracts.md).

## Package shape

| Area | Responsibility |
| --- | --- |
| `api/v1alpha1` | Routes and HTTP error handling |
| `core` | Settings, composition, lifecycle, and readiness |
| `providers/kueue` | LocalQueue/ClusterQueue reads and Kueue normalization |
| `providers/session` | Session Job aggregation |
| `providers/kubemetrics` | Session live usage from `metrics.k8s.io` |
| `providers/promql` | Fixed Prometheus-compatible efficiency queries |
| `services` | Subject dispatch, aggregation, cache orchestration, and conditions |
| `cache` | Redis snapshots, leases, L1 fallback, and single-flight |
| `schemas` | Public Metrics and Kubernetes Status models |
| `telemetry` | Optional OTLP application-state metrics |
| `http_cache` | `Age` and `Cache-Status` success headers |
| `errors` | Application and provider error types |
| `dev` | Local kind stack helpers (not production) |

There is no accounting package, Cohort provider, Pod-inventory-as-primary
provider, or Metrics-owned monitoring stack.

## Telemetry

When enabled, OTLP application-state metrics cover request duration, cache
hits/misses, source outcomes, fill coordination, Redis health, lifecycle, and
readiness. OTLP traces and logs are not exported. Subject values, raw
selectors, PromQL, credentials, and full backend URLs are not telemetry
attributes.

Scope and provider attribute allowlists are
`platform|user|community|other` and `kueue|promql|other`. Session traffic and
the `session` / `kubemetrics` providers therefore export as `other`.

## Validation boundary

Unit tests validate aggregation, cache coordination, response conditions, and
fixed query selection. Integration tests may install disposable Redis,
Prometheus/Mimir, KSM, Kueue, or an OTLP metrics receiver. Those fixtures are
test dependencies only and do not change the production ownership boundary.
