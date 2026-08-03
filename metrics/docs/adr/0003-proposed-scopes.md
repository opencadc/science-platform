# ADR-0003: Proposed scopes — interactive quota, user and session metrics

## Status

Proposed (M5 quota; M6 UserMetrics; M7 SessionMetrics). Consolidates former
ADRs 0015 (itself 0015/0016/0018) and 0020 (itself 0020/0021); originals in
git history. Nothing here is runtime configuration until a complete provider
ships (ADR-0001).

## Decision

- **InteractiveQuota (M5).** `sources.quotas.interactive: kube` with a
  complete kube provider (`interactive_quota(user)`); platform stays
  Kueue-backed. Route `GET /api/v1/metrics/users/{user}/quotas/interactive`,
  `kind: InteractiveQuota`, standard envelope, **fixed**/**flexible** buckets:
  logical session count (distinct values of the single session-type label per
  Pod; Pods with zero or multiple session labels are skipped, not
  double-counted) plus summed container `requests`/`limits` as open resource
  maps. `{user}` maps to the configured user label; namespaces stay out of the
  API. Reads are namespace-scoped Pod LIST + label selectors per request
  behind a private ~2s cache; watch/informer indexes are deferred and must not
  change the contract.
- **UserMetrics (M6) / SessionMetrics (M7).** Routes
  `GET /api/v1/metrics/users/{user}` and
  `GET /api/v1/metrics/users/{user}/sessions/{uuid}`, kinds `UserMetrics` /
  `SessionMetrics`, standard envelope. `sources.users` / `sources.sessions`
  select **one** provider each (kube or prometheus) returning the complete
  model — no stitching. Attribution and `{uuid}` semantics land with M6/M7
  specs; wire fields inside `data` are locked when schemas ship. Skaha's
  `MetricsDAO` switches by configuration only (system ADR-0001).
- All three scopes use private cache rules and hashed-user cache keys
  (ADR-0002).

## Consequences

- M5 RBAC adds Pod list/get in configured namespaces, separate from the
  get-only ClusterQueue access.
- Skaha must label Pods with user, allocation class, and exactly one
  session-type label; malformed Pods surface in telemetry, not responses.

## References

- [`0001-runtime-architecture.md`](0001-runtime-architecture.md)
- [`0002-platform-api-contract.md`](0002-platform-api-contract.md)
- [`../../../docs/adr/0001-platform-stats-integration-boundary.md`](../../../docs/adr/0001-platform-stats-integration-boundary.md)
