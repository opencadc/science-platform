# ADR-0004: Metrics unified subject resource in the CANFAR API group

## Status

Accepted.

## Decision

Replace the unreleased `PlatformMetrics` v1 API with a hard cutover to one
`Metrics` resource at `canfar.net/v1alpha1`; do not maintain a legacy
compatibility route or client fallback. The standalone service exposes only:

```text
GET /apis/canfar.net/v1alpha1/metrics/user/{user}
GET /apis/canfar.net/v1alpha1/metrics/community/{community}
GET /apis/canfar.net/v1alpha1/metrics/platform/{platform}
```

The path chooses the subject and the response echoes exactly one of
`spec.user`, `spec.community`, or `spec.platform`. The three surfaces share one
kind and resource schema so metric meaning and aggregation cannot drift.

The API group carries the CANFAR identity, so kinds use unprefixed CamelCase
domain types: the accepted mass noun `Metrics` now and the singular `Session`
if that object resource is added later. The resource singular and plural are
both `metrics`; a future collection kind is `MetricsList`, and
`metrics.canfar.net` remains the CRD name if Metrics is ever materialized as a
CRD. `Controller` and `Accounting` remain implementation roles unless a
separately named public domain resource is designed.

The first User phase reports current requested resources for Pods in phase
`Running` only. A request is the scheduler-effective whole-Pod value, including
regular containers, restartable sidecars, effective init containers, and Pod
overhead. Pending demand through LocalQueue/Kueue is deferred. The second User
phase adds per-resource `ActiveWorkloadLifetime` usage and efficiency for the
Pods `Running` at evaluation time: each Pod contributes observed and requested
resource-time over its own Running duration before aggregation. Public values
use core-hours, GiB-hours, or GPU-hours. Metrics queries controlled,
precomputed time series from Prometheus or Mimir rather than constructing this
history from the API request. Completed-Pod and fixed-window historical reports
are deferred. Redis is a required
runtime dependency for deployed User metrics, targeting approximately
two-minute freshness. A stale report may be served only with its original
observation time and explicit stale state; the fresh/stale/hard-expiry budgets
are fixed by ADR-0005.

## Consequences

- Metrics and Skaha must be deployed compatibly across the hard API cutover.
- Current requests must never be labelled as measured usage.
- CPU and memory efficiency remain separate ratios over resource-time; there is no
  combined “overall efficiency”.
- In the initial unauthenticated, cluster-internal API, subject IDs are exact
  canonical label values and are selectors rather than identity assertions.
- Public responses have exactly `Ready` and `Cached` conditions and do not
  expose source records.
- There is no collection POST, LIST/WATCH, report CRD, or explicit kubectl
  surface in the initial delivery.
- Because an aggregated `APIService` owns a whole group/version, a future
  Kubernetes aggregation design must serve the `canfar.net/v1alpha1` API family
  through one CANFAR extension API boundary rather than letting independent
  Metrics and Session servers claim the same group/version.
- The existing proposed-scope ADR is superseded.

## References

- [`../canfar-metrics-v1alpha1-design.md`](../canfar-metrics-v1alpha1-design.md)
- [`0002-platform-api-contract.md`](0002-platform-api-contract.md)
- [`0007-resource-time-accounting.md`](0007-resource-time-accounting.md)
