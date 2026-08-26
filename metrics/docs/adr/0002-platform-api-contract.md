# ADR-0002: Metrics API contract and aggregation policy

## Status

Partially superseded by [ADR-0010](0010-simple-kueue-metrics-service.md).
The shared `Metrics` envelope, unit parity, cache headers, and sanitized error
policy remain accepted; the old Pod-source and accounting sections do not.

## Decision

The service exposes exactly three report routes under
`canfar.net/v1alpha1`:

```text
GET /apis/canfar.net/v1alpha1/metrics/user/{username}
GET /apis/canfar.net/v1alpha1/metrics/community/{community}
GET /apis/canfar.net/v1alpha1/metrics/platform/{platform}
```

All routes return one `Metrics` object with one subject, `observedAt`, named
resources, `reservingWorkloads`, and exactly one `Ready` plus one `Cached`
condition. User and Community resources use `requests` and optional current
efficiency. Platform resources use comparable `capacity` and `allocated`
quantities and optional efficiency.

Platform aggregation reads the configured ClusterQueues only. `capacity` sums
nominal quota; `allocated` sums `flavorsUsage.resources[].total`; borrowed
quota is not added separately. User and Community source semantics are defined
by ADR-0010. Resource names are open-ended and capacity/allocated units match
for each resource name.

Redis snapshots use the fixed User 2/3/5-minute, Community 5/10/15-minute,
and Platform 5/30/60-minute windows. `Cache-Control: no-store`, `Age`, `Last-Modified`, and
`Cache-Status` describe internal snapshot handling. Error responses use a
sanitized Kubernetes `Status` envelope and never expose upstream exception
text, credentials, URLs, or query strings.

Prometheus/Mimir is an optional source for fixed server-owned current
efficiency queries. It is not a public PromQL proxy.
