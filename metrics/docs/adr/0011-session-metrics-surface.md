# ADR-0011: Session Metrics surface

## Status

Accepted.

## Decision

Metrics adds a fourth subject, Session, at
`GET /apis/canfar.net/v1alpha1/metrics/session/{id}` where `{id}` is the exact
`canfar.net/id` label value. The response remains one `canfar.net/v1alpha1`
`Metrics` object with `spec.session`, `status.reservingWorkloads`, workload
`resources`, and exactly one `Ready` and one `Cached` condition.

The Session primary source is Job identity in every namespace from
`METRICS_PROVIDERS__KUEUE__NAMESPACES`. Metrics lists `batch/v1` Jobs labelled
`canfar.net/id={id}`, including desktop-app child Jobs that share the same id.
No matching Job is a 404. A Job list failure with no serviceable snapshot is a
503.

`status.reservingWorkloads` is the count of matching Jobs. `resources[].requests`
sum Job template container requests across all non-pause containers in every
matching Job. GPU requests are included; GPU usage and efficiency are not
exposed.

Optional live `resources[].usage` for CPU and memory comes from
`metrics.k8s.io` summed across matching Running pods and non-pause containers.
Usage is omitted, not zero, when no Running pod metrics exist. A kube-metrics
failure while Running pods exist returns HTTP 200 with queue data, usage
omitted, and `Ready=False`/`PartialData`.

Optional Session `resources[].efficiency` for CPU and memory is duration
utilization from fixed server-owned PromQL over the session window, capped at
six hours. The window starts at the earliest matching Job `startTime` and ends
at now when any matching pod is Running, otherwise at the latest Job
`completionTime`. Efficiency is omitted when no Job has `startTime` yet.
Prometheus/Mimir activation remains endpoint-only through
`METRICS_PROVIDERS__PROMQL__BASE_URL`. When the endpoint is absent, efficiency
is omitted with `Ready=True`/`Available`. When present and the query fails,
efficiency is omitted with `PartialData`.

Session cache windows are 30 seconds fresh, 60 seconds serviceable, and
3 minutes retained. User, Community, and Platform source rules, instant
efficiency semantics, and response shapes are unchanged. Platform and User or
Community workload rows do not expose `usage`.

## Consequences

- Skaha may source list-view pod usage from this route when configured to use
  the Metrics backend instead of direct `metrics.k8s.io` reads.
- Session efficiency uses Grafana Usage duration semantics, not the five-minute
  instant ratios used by User, Community, and Platform.
- Operators must allowlist `canfar.net/id` in kube-state-metrics for Session
  PromQL joins to survive after pods leave Running.
