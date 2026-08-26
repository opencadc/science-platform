# Metrics architecture

This document describes the approved simple Metrics service. The
[Confluence API Contract](https://herzberg.atlassian.net/wiki/spaces/C/pages/2690809875/API+Contract)
remains canonical for product decisions; this file records the repository
boundary and operational shape. The queue and attribution label contract is
defined in [`metadata-labels.md`](metadata-labels.md).

## One service, three read surfaces

```text
FastAPI routes
      |
      v
one Metrics service  <----> one shared external Redis
      |
      +--> Kueue LocalQueues in configured namespaces  --> User
      +--> configured Kueue ClusterQueues               --> Community
      +--> all configured Kueue ClusterQueues           --> Platform
      +--> optional fixed PromQL to Prometheus/Mimir    --> efficiency
      +--> optional OTLP metrics to external endpoint   --> app state
```

There is one application process per Pod and one asynchronous request path.
The service owns route handling, source selection, normalization, aggregation,
cache policy, and response conditions. It does not own a producer, a
time-series database, a Kubernetes metrics exporter, or a second service for
accounting.

## Source boundaries

### User

For a User request, Metrics lists LocalQueues in every namespace named by
`METRICS_PROVIDERS__KUEUE__NAMESPACES`. It selects queues with the exact
`canfar.net/username=<username>` label. Every selected LocalQueue must refer to
one ClusterQueue in `METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES`; a reference
outside that set is a configuration error, not a reason to undercount.

The service sums `status.flavorsReservation.resources[].total` by Kubernetes
resource name and sums `status.reservingWorkloads`. A missing matching queue
is a 404. A LocalQueue list failure, missing configured queue, or invalid queue
reference is a primary-source failure.

### Community

For a Community request, Metrics filters the configured ClusterQueues by the
exact `canfar.net/community=<community>` label and aggregates every match. It
sums `flavorsReservation.resources[].total` and `reservingWorkloads`. Zero
matches are a 404. Community membership is not inferred by listing Users or
LocalQueues.

### Platform

The Platform source is exactly the configured ClusterQueue list. It sums
nominal quota into `capacity`, sums `status.flavorsUsage.resources[].total`
into `allocated`, and sums `reservingWorkloads`. It does not list or add
Cohort quota or usage. Borrowed quota is already represented by Kueue's
reported totals and is not added a second time.

All Kueue reads use the pinned `kueue.x-k8s.io/v1beta2` API. Kubernetes
quantity parsing is strict and resource names remain open-ended.

## Optional efficiency source

Prometheus or Mimir is an optional read source. Its sole activation switch is
`METRICS_PROVIDERS__PROMQL__BASE_URL`: absence disables efficiency and
presence enables the fixed catalog of instant PromQL queries. There is no
separate enable flag. User queries select
`label_canfar_net_username`; Community queries select
`label_canfar_net_community`; Platform queries select the configured platform
population. Each query includes Running-Pod state and returns current CPU and
memory usage/request ratios.

The source is optional because Kueue queue state is the primary report. A
Prometheus/Mimir outage does not erase queue values. The report omits
efficiency and is marked `Ready=False` with reason `PartialData` while still
returning HTTP 200. Metrics never accepts caller-authored PromQL.

## Cache and concurrency

Redis is the shared cache boundary for every replica. Snapshot keys include the
surface, subject, source/config revision, and a protected subject digest.
Each surface/subject has one distributed lease. A process-local task registry
may avoid duplicate work inside a single replica, but Redis is required for
cross-replica coalescing.

The cache policy is fixed:

| Surface | Fresh | Stale but serviceable | Retained, not served |
| --- | ---: | ---: | ---: |
| User | 2 minutes | through 10 minutes | through 15 minutes |
| Community | 2 minutes | through 10 minutes | through 15 minutes |
| Platform | 5 minutes | through 30 minutes | through 60 minutes |

Fresh data is returned immediately. For stale data, one lease holder performs
the refresh and concurrent requests receive the stale snapshot. For a cold or
unserviceable miss, one holder fills Redis and concurrent requests wait for
the same published result. Every fill is bounded; a crashed holder cannot
retain a lease indefinitely.

## Runtime and deployment ownership

`MetricsRuntime` is the composition root for the FastAPI process. It owns
provider clients, the Redis cache coordinator, optional PromQL transport, and
OTLP application-state metrics. It exports no OTLP traces or logs. External
I/O is asynchronous and constructors do not perform network work.

The production chart owns the Metrics Deployment, Service, RBAC, probes, and
configuration references. Redis, KSM, Prometheus/Mimir, and the OTLP metrics receiver
are external services. A disposable integration profile may install them for
tests, but those fixtures are not production components.

## Health and failure boundaries

- `/healthz` and `/livez` report process liveness.
- `/readyz` reports coordinated ability to use required Redis/Kueue sources;
  it is not a substitute for a report's `Ready` condition.
- A missing User or Community subject is 404.
- Optional efficiency failure returns 200 with `PartialData`.
- Primary Kueue failure returns a serviceable cached report when available;
  otherwise it returns 503.
- Missing values are never coerced to zero.

## Package shape

The package stays small and explicit:

| Area | Responsibility |
| --- | --- |
| `api/v1alpha1` | Routes and HTTP error/conditional-response handling |
| `core` | Settings, composition, lifecycle, and readiness |
| `providers/kueue` | LocalQueue/ClusterQueue reads and Kueue normalization |
| `providers/promql` | Fixed Prometheus-compatible instant queries |
| `services` | Subject dispatch, aggregation, cache orchestration, and conditions |
| `cache` | Redis snapshots, leases, and single-flight coordination |
| `schemas` | Public Metrics and Kubernetes Status models |
| `telemetry` | Optional OTLP application-state metrics |

There is no accounting package, Cohort provider, Pod-inventory provider, or
Metrics-owned monitoring stack.
