# ADR-0010: Simple Kueue Metrics service

## Status

Accepted.

## Decision

Metrics is one asynchronous FastAPI service with one shared external Redis
cache and one public `canfar.net/v1alpha1` `Metrics` kind. Four subject routes
select User, Community, Platform, or Session. User, Community, and Platform
primary sources are Kueue only: LocalQueues by exact `canfar.net/username` in
configured namespaces (restricted to configured ClusterQueues), ClusterQueues
by exact `canfar.net/community`, and all configured ClusterQueues for
Platform. Session primary source is `batch/v1` Jobs by exact `canfar.net/id`,
including desktop-app children that share the id.

Public aggregation uses `flavorsReservation` / `reservingWorkloads` for User
and Community, nominal quota and `flavorsUsage` for Platform capacity and
allocation, and Job template requests plus Job count for Session. Optional
instant PromQL efficiency covers User, Community, and Platform; Session may
add optional `metrics.k8s.io` usage and duration PromQL efficiency. Cohorts,
Running-Pod inventory as a primary source, separate quota/session CR kinds,
lifetime accounting, producers, checkpoints, and usage-hours are excluded.

Cache windows, failure matrices, PromQL shapes, and OpenAPI status semantics
live in [`../specs.md`](../specs.md). Session's addition to this boundary is
also indexed as [ADR-0011](0011-session-metrics-surface.md) for historical
cites.
