# ADR-0010: Simple Kueue Metrics service

## Status

Accepted.

## Decision

Metrics is one asynchronous FastAPI service with one shared external Redis
cache. User reports list LocalQueues in configured namespaces by exact
`canfar.net/username`, restrict them to the configured ClusterQueues, and sum
`flavorsReservation` plus `reservingWorkloads`. Community reports aggregate
configured ClusterQueues labelled exact `canfar.net/community`; no match is a
404. Platform reports aggregate all configured ClusterQueues only: nominal
quota is `capacity`, `flavorsUsage` is `allocated`, and Cohorts are excluded.

Each surface uses distributed per-subject single-flight with fixed windows:
User and Community 2/10/15 minutes, Platform 5/30/60 minutes. Prometheus or
Mimir is optional and may supply current CPU/memory efficiency through fixed
server-owned PromQL over Running Pods. The presence of
`METRICS_PROVIDERS__PROMQL__BASE_URL` enables that source and absence disables
it; there is no separate enable flag. It does not become an accounting source
and callers never submit PromQL. The app may export application-state OTLP
metrics, but not OTLP traces or logs, to an external endpoint.

The public response contains `reservingWorkloads`; User and Community expose
`requests` plus optional `efficiency`; Platform exposes `capacity` and
`allocated` plus optional `efficiency`; `Ready` and `Cached` remain. Optional
efficiency failure returns HTTP 200 with `PartialData`. A primary Kueue failure
returns 503 when no serviceable snapshot exists. The production chart owns no
Redis, KSM, Prometheus/Mimir, or OTLP Collector; test fixtures may provide
disposable instances.

This decision supersedes the Pod-source and lifetime-accounting portions of
ADRs 0001, 0002, 0004, 0007, 0008, and 0009, and the Cohort aggregation
assumptions in the former Kueue design. There is no producer, checkpoint
store, usage-hours field, accounting period, or Metrics-owned time-series
database in the current service.
