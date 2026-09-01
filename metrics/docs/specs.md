# Metrics observable specification

The [Confluence API Contract](https://herzberg.atlassian.net/wiki/spaces/C/pages/2690809875/API+Contract)
is canonical. This document states the implementation-facing behavior for the
simple Metrics service. Required queue and conditional efficiency metadata is
specified in [`metadata-labels.md`](metadata-labels.md).

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

The four report routes return a `canfar.net/v1alpha1` `Metrics` object. The
path selects exactly one subject field:

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

## Report fields

All surfaces expose:

- one subject in `spec.user`, `spec.community`, `spec.platform`, or
  `spec.session`;
- `status.observedAt`, the conservative observation time for the report;
- `status.reservingWorkloads`, the sum of Kueue reserving workloads; and
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

### User

Metrics lists LocalQueues in every namespace from
`METRICS_PROVIDERS__KUEUE__NAMESPACES`, selects exact
`canfar.net/username=<username>` labels, and requires each selected queue to
reference one ClusterQueue from
`METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES`.

For each selected LocalQueue, it adds:

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
`reservingWorkloads` into the report count. Cohorts are out of scope.

The configured ClusterQueue set is the complete Platform boundary:

```text
METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES='["cq-astronomy","cq-physics"]'
```

Each configured ClusterQueue maps to one Community. A missing, inaccessible,
or malformed configured ClusterQueue is a primary-source failure; Platform
must not silently become a partial sum.

### Session

Metrics lists Jobs in every namespace from
`METRICS_PROVIDERS__KUEUE__NAMESPACES`, selects exact
`canfar.net/id=<id>` labels, and aggregates every matching Job including
desktop-app children that share the id.

For each matching Job, Metrics adds:

- summed non-pause container template `requests` to `resources[].requests`; and
- one Job to `status.reservingWorkloads`.

Optional live `usage` sums `metrics.k8s.io` CPU and memory for matching
Running pods. Optional Session `efficiency` uses fixed PromQL joined on
`label_canfar_net_id` over the bounded session window. No match is a 404.

## Optional efficiency contract

Efficiency activation is endpoint-only. If
`METRICS_PROVIDERS__PROMQL__BASE_URL` is absent, efficiency is disabled. If it
is present, Metrics executes fixed server-owned instant PromQL. There is no
separate PromQL enable setting, and the service does not accept caller query
text or selector input.

The query catalog uses these exporter label names:

```text
label_canfar_net_username
label_canfar_net_community
```

User and Community queries select the requested label value and Running Pods.
Platform queries select the configured platform population and Running Pods.
The result is current usage divided by current requests for CPU and memory:

```text
CPU efficiency    = Running-Pod CPU usage rate / Running-Pod CPU requests
Memory efficiency = Running-Pod memory working set / Running-Pod memory requests
```

The ratio is omitted for a zero denominator, an invalid vector, or an
unavailable optional backend. User, Community, and Platform efficiency remain
current five-minute instant ratios. Session efficiency is duration utilization
over the bounded session window and is omitted when no Job has `startTime` yet.
These are not lifetime usage fields, accounting period, checkpoints, producer,
or usage history.

## Cache policy

One shared external Redis stores versioned snapshots and distributed
single-flight leases. Subject-bearing key segments are protected digests; raw
User and Community values do not appear in Redis key paths.

| Surface | Fresh | Serviceable stale | Retained only |
| --- | ---: | ---: | ---: |
| Session | 30 seconds | 60 seconds | 3 minutes |
| User | 2 minutes | 3 minutes | 5 minutes |
| Community | 5 minutes | 10 minutes | 15 minutes |
| Platform | 5 minutes | 30 minutes | 60 minutes |

Fresh snapshots return immediately. One lease winner refreshes a stale
snapshot; concurrent requests return that stale snapshot. A cold or
unserviceable miss has one fill winner and waiting followers. The fill is
bounded and published once to Redis. There is no background refresh worker.

## Conditions, status codes, and headers

- A User with no matching LocalQueue returns 404.
- A Community with no matching configured ClusterQueue returns 404.
- A Session with no matching Job returns 404.
- A primary Kueue failure returns a serviceable cached report when one exists;
  otherwise it returns 503.
- A primary Session Job failure returns a serviceable cached report when one
  exists; otherwise it returns 503.
- A Prometheus/Mimir failure while
  `METRICS_PROVIDERS__PROMQL__BASE_URL` is present returns 200 with queue
  values, efficiency omitted, and `Ready=False`/`PartialData`.
- A kube-metrics failure while Running session pods exist returns 200 with Job
  values, usage omitted, and `Ready=False`/`PartialData`.
- `Ready=True`/`Available` means the required Kueue source was complete and
  optional efficiency, when requested, was usable.
- `Cached` reports fresh, refreshed, stale, or unavailable cache provenance.
- Responses use `Cache-Control: no-store`; `Age` and `Cache-Status` describe
  internal snapshot handling.
- Error bodies use a sanitized Kubernetes `Status` envelope. Upstream URLs,
  query text, credentials, and exception details are not serialized.

## Configuration contract

Settings are environment-only, use the `METRICS_` prefix, and use `__` for
nested fields. List values are JSON arrays:

```text
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

Kubernetes endpoint and credentials come from the in-cluster service account
or kubeconfig. Redis, Prometheus/Mimir, and the OTLP metrics receiver are external
deployment services. The production Helm chart owns no instance of them.

## Telemetry

When enabled, OTLP application-state metrics cover request duration, cache
hits/misses, source outcomes, fill coordination, and readiness. OTLP traces and
logs are not exported. Subject values, raw selectors, PromQL, credentials, and
full backend URLs are not telemetry attributes. The metrics exporter sends to
one configured external OTLP endpoint.

## Validation boundary

Unit tests validate aggregation, cache coordination, response conditions, and
fixed query selection. Integration tests may install disposable Redis,
Prometheus/Mimir, KSM, Kueue, or an OTLP metrics receiver. Those fixtures are test
dependencies only and do not change the production ownership boundary.
