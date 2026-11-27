# ADR-0010: Simple Kueue Metrics service

## Status

Accepted.

Amended: Session requests and counts come from active Jobs only. Each Job's
request is its effective pod request.

Amended: each Community is exactly one configured ClusterQueue. A repeated
`canfar.net/community` label on the configured set is invalid. User reports
read LocalQueues only and do not GET ClusterQueues on that path.

## Decision

Metrics is one asynchronous FastAPI service. It uses one shared external Redis
cache and one public `canfar.net/v1alpha1` `Metrics` kind. Four subject routes
select User, Community, Platform, or Session.

User, Community, and Platform primary sources are Kueue only:

- User: LocalQueues with exact `canfar.net/username` in the configured
  namespaces. Each matching LocalQueue must name a configured ClusterQueue.
  The User path does not read ClusterQueues.
- Community: the one configured ClusterQueue with exact
  `canfar.net/community`. No two configured ClusterQueues share that label.
- Platform: every configured ClusterQueue.

Session primary source is `batch/v1` Jobs with exact `canfar.net/id`, including
desktop-app children that share the id.

Public aggregation uses `flavorsReservation` and `reservingWorkloads` for User
and Community. Platform uses nominal quota and `flavorsUsage` for capacity and
allocation. Session uses active Jobs only (not `Complete`, not `Failed`, not
suspended) and each Job's effective pod request as Kubernetes and Kueue compute
it. A finished session reports nothing reserved.

Optional instant PromQL efficiency covers User, Community, and Platform.
Session may add optional `metrics.k8s.io` usage and duration PromQL efficiency.

Excluded: Cohorts, Running-Pod inventory as a primary source, separate
quota/session CR kinds, lifetime accounting, producers, checkpoints, and
usage-hours.

Cache windows, failure matrices, PromQL shapes, and OpenAPI status semantics
live in [`../specs.md`](../specs.md). Session's addition to this boundary is
also indexed as [ADR-0011](0011-session-metrics-surface.md) for historical
cites.
