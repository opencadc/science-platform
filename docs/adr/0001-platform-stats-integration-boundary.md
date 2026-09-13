# ADR-0001: Platform stats integration boundary

## Status

Accepted. The Metrics route and readiness acceptance below are the current
implementation boundary; the original platform-only route is historical.

## Context

Science Portal and legacy clients consume cluster-wide figures through Skaha
`GET /v1/session?view=stats`. After the Metrics migration, Skaha must not derive
cluster totals from node listing or pod aggregation.

## Decision

- Skaha **platform stats** sources **platform capacity** and **platform
  allocation** exclusively from the co-deployed Metrics API at
  `GET /apis/canfar.net/v1alpha1/metrics/platform/{platform}` via the base URL
  in `SKAHA_METRICS_BACKEND_URL`. The `{platform}` value is the configured
  `METRICS_PLATFORM_NAME` (and Skaha's matching optional platform-name
  setting), defaulting to `canfar`.
- Skaha performs **no in-process cache** for platform stats; Metrics owns TTL
  and snapshot freshness.
- Skaha accepts a platform response only when it is a usable, fresh
  `canfar.net/v1alpha1` `Metrics` envelope with a well-formed
  `status.conditions` array containing exactly one `Ready` and exactly one
  `Cached` condition, with no unknown or duplicate types. Each condition must
  use an allowed status/reason pair: `Ready=True/Available`,
  `Ready=False/{PartialData,StaleData}`,
  `Cached=True/{FreshHit,StaleHit}`, `Cached=False/Refreshed`, or
  `Cached=Unknown/RedisUnavailable`. Each condition also needs a
  timezone-aware RFC3339 `lastTransitionTime` no later than
  `status.observedAt`; `Ready` must be `True`/`Available` for Skaha
  acceptance. Metrics marks stale or incomplete data non-ready; Skaha does not
  reinterpret it as current platform stats.
- The Platform response may include `status.reservingWorkloads` and
  per-resource `efficiency`. Skaha deliberately ignores those optional fields
  and consumes only `capacity` and `allocated`, preserving their Kubernetes
  resource-quantity conversion into the existing session-stats shape.
- On successful platform stats, `lastUpdate` reflects Metrics
  `status.observedAt`, not Skaha assembly time.
- When Metrics is unreachable, its response is not `Ready=True`/`Available`,
  or session ceilings cannot be loaded, platform stats returns **HTTP 503**
  (fail closed) with stable client messages: **"Platform statistics
  unavailable"** (Metrics) and **"Session resource limits unavailable"**
  (LimitRange). No partial stats.
- Instantiate `MetricsDAO` lazily on the stats path only so a missing
  `SKAHA_METRICS_BACKEND_URL` does not break unrelated session GET routes.

## Consequences

- Metrics availability directly affects platform stats only; other Skaha
  endpoints continue.
- Metrics owns Kueue queue aggregation, freshness, optional PromQL efficiency,
  and OTLP application telemetry. Skaha is only a consumer of the Platform
  report and does not implement accounting, Cohort aggregation, or a second
  metrics endpoint.
- Session lifecycle tests must not depend on Metrics except where platform stats
  is under test.
- The former unreleased `/api/v1/metrics/platform` and `PlatformMetrics` shape
  is not a compatibility route. The current hard cutover is recorded in
  Metrics ADR-0004 and the execution state remains in CADC-16077.

## References

- [`../../skaha/CONTEXT.md`](../../skaha/CONTEXT.md)
- [`../../metrics/CONTEXT.md`](../../metrics/CONTEXT.md)
- [`../../skaha/docs/adr/0002-platform-stats-fail-closed.md`](../../skaha/docs/adr/0002-platform-stats-fail-closed.md)
