# ADR-0002: Platform API contract — aggregation, caching, errors, telemetry

## Status

Partially superseded by ADR-0004 and ADR-0005. Its Kueue aggregation semantics
remain active; its route, wire envelope, error shape, and downstream-cache
policy do not. Originally accepted 2026-07-31 and consolidated former ADRs 0002 (itself
0002/0003/0006/0007/0008), 0004 (itself 0004/0017), 0013 (itself 0013/0014),
and 0019; originals in git history.

## Context

Skaha and Science Portal compare `capacity` and `allocated` without
conversion; Kueue exposes usage-like fields that invite double counting.
Cache metadata in JSON bodies, stub routes, and leaked upstream error text
have all previously burned clients.

## Decision

- **Aggregation** (`GET /api/v1/metrics/platform`, `kind: PlatformMetrics`):
  - Configured ClusterQueues only (`METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES`);
    Cohorts are out.
  - `data.capacity`/`data.allocated` are open string-keyed maps by Kubernetes
    resource name; clients tolerate unknown keys; a new resource name needs no
    API revision.
  - Unit parity per resource name in both maps: CPU decimal cores,
    memory/ephemeral-storage `Gi`, extended resources base units.
  - `allocated` sums `status.flavorsUsage.resources[].total` only — `total`
    already includes borrowed quota (never add `borrowed`), and
    `flavorsReservation` is not allocation.
  - Invalid quantities fail the read (502/503), never become zero.
- **Caching via HTTP headers** (`Cache-Control`, `Date`, `Expires`,
  `Last-Modified`); JSON bodies carry no TTL or cache metadata. Platform scope
  is shared (`public`, `cache.ttl_seconds`, 300s typical); `max-age` is
  remaining freshness; TTL 0 sends `no-store`. Future user-scoped responses
  are `private` with short TTLs and hashed-user cache keys.
- **Progressive routes.** Only complete contracts ship: today
  `/api/v1/metrics/platform` and `/healthz`. No stubs, no hidden aliases.
  Envelope: `version`, `kind`, `metadata.created`, `status`, then `data` or
  `error`.
- **Sanitized errors.** Error envelopes carry a stable code and short message
  (`{"code", "message"}`) — never upstream exception text or URLs. Detail goes
  to server logs.
- **Telemetry** (when `METRICS_OTEL_METRICS_ENABLED=true`): custom meters
  `canfar.metrics.provider.duration`, `canfar.metrics.cache.lookups`,
  `canfar.metrics.compute.duration`; HTTP request metrics come from FastAPI
  auto-instrumentation (`http.server.*`). OTLP HTTP push only; charts map
  `telemetry.otlpEndpoint` to `otel_exporter_otlp_endpoint`.

## Consequences

- Double-counting borrowed quota or reading reservation fields is a defect.
- Tests assert headers and envelope shape, not JSON cache fields or upstream
  error text.
- Consumers must not depend on routes before their milestone lands.

## References

- [`../specs.md`](../specs.md)
- `src/metrics/providers/kueue.py`, `src/metrics/telemetry.py`
