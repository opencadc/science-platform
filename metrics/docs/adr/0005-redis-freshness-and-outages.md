# ADR-0005: Redis freshness and outage policy

## Status

Accepted.

## Decision

Redis is required in deployed Metrics environments; the in-memory backend is
for development and tests. The chart's embedded ephemeral Redis is enabled
only by local values. Production disables it by default and obtains
`METRICS_REDIS_URL` from a Kubernetes Secret pointing to an externally managed
Redis 7-compatible deployment. Metrics does not claim production Redis
persistence, replication, backup, or upgrade ownership.

Subject-bearing Redis key segments are HMAC-SHA256 digests over subject type
and canonical value, using a required deployed `METRICS_CACHE__KEY_SECRET` of
at least 32 bytes. The local stack generates an ephemeral secret. Rotation
deliberately makes old subject keys unreachable; they expire normally within
the bounded retention policy rather than requiring a dual-key migration.

Snapshots are UTF-8 JSON validated through strict, versioned Pydantic internal
models. Pickle, MessagePack, and compression are not used initially. Unknown or
invalid revisions are cache misses with bounded telemetry, never partially
decoded data.

Cached observations have four explicit states:

| Subject | Fresh | Stale and serviceable | Expired but retained | Purged |
| --- | --- | --- | --- | --- |
| Platform | 0–5 minutes | 5–30 minutes | 30–60 minutes | after 60 minutes |
| User | 0–2 minutes | 2–10 minutes | 10–15 minutes | after 15 minutes |
| Community | 0–2 minutes | 2–10 minutes | 10–15 minutes | after 15 minutes |

The API may serve fresh or stale-serviceable observations. An expired
snapshot remains available only for refresh recovery and diagnostics; it must
not be returned as data and the API returns a Kubernetes `Status` 503 when no
requested section remains serviceable.

Refresh work is request-triggered and source-oriented. A GET reads Redis first;
an unavailable entry starts a bounded cache fill. Cross-replica coalescing
allows at most one fill winner for a source, cluster, and refresh bucket, so
concurrent API traffic cannot multiply Kubernetes observations or
Prometheus/Mimir instant queries. There is no background refresh worker in the
initial runtime. Redis stores normalized, schema-versioned source snapshots,
not raw upstream response envelopes. `Metrics` reports are assembled from
those snapshots. Raw User and Community IDs do not appear in Redis key paths;
subject indexes use a stable keyed digest.

Request behavior is deterministic for each cache state:

- a fresh snapshot is returned immediately;
- for a stale-serviceable snapshot, one lease winner awaits a bounded source
  fill and returns the new snapshot when it succeeds; concurrent lease losers
  return the stale snapshot immediately, and the winner also returns that
  stale snapshot when its fill fails;
- for a cold cache or a snapshot beyond its serviceable deadline, one lease
  winner performs the bounded fill while concurrent requests wait for the
  shared result; and
- if that fill times out or fails and no serviceable snapshot exists, the API
  returns a Kubernetes `Status` response with HTTP 503.

"Awaits" above describes asynchronous request coordination; it does not permit
blocking source I/O on the event loop or an unbounded request wait.

Each source and refresh bucket has a Redis-backed distributed lease. The lease
uses a unique owner token and atomic ownership check on release. A lease winner
refreshes the immutable snapshot and atomically advances a small latest-snapshot
pointer. Other replicas serve the prior serviceable snapshot. Expiration
boundaries and retry backoff include jitter, and source concurrency is bounded.
Same-process cold-cache waiters share an `asyncio.Event`; cross-replica waiters
poll the durable latest pointer with bounded jitter until the fill or request
deadline. Redis Pub/Sub and keyspace notifications are not required for
correctness. Redis keys include the API schema, source-contract, and
query-catalog revisions so an old payload cannot be decoded under a new
contract.

Initial configurable timeout defaults are 500 milliseconds per Redis
operation, 5 seconds per Kubernetes or PromQL request, 10 seconds for a
complete Kubernetes fill, and 12 seconds for a cold GET. At most one transient
source retry is allowed, and only when the remaining outer budget can contain
it. Every timeout is finite; stale lease losers return immediately.

If a cold request cannot obtain bounded fill capacity before its deadline, the
API returns a Kubernetes `Status` 503 with `Retry-After`. The initial API does
not use 429 because it has no caller rate-limit contract. Fresh and
stale-serviceable reads do not wait for source-fill capacity.

User and Community fills are complete only when every configured workload
namespace succeeds. A partial namespace result is not cached or returned as a
total. Metrics instead serves a prior complete serviceable snapshot, omits the
unavailable section with `Ready=False`, or returns 503 when no useful section
remains.

Metrics fails startup and readiness when Redis is unavailable. A running
replica may serve an in-process last-known report only until its hard expiry,
retaining the original observation time and reporting `Ready=False` with
reason `StaleData`. During a Redis outage, the API never bypasses Redis by
querying Kubernetes, Prometheus, or Mimir for each request. It serves only a
still-serviceable bounded in-process snapshot; after that snapshot's hard
expiry, requests fail with a Kubernetes `Status` 503.
Liveness describes process health and does not fail solely because Redis or a
metrics source is unavailable.

The response uses one conservative report `observedAt`, exactly `Ready` and
`Cached` conditions, and `Last-Modified`, `Age`, and RFC 9211 `Cache-Status`
headers. It does not serialize source records, Redis deadlines, or internal
completeness flags. Source-level collection times and outcomes remain OTel
telemetry. The response is `Cache-Control: no-store`; the headers describe
Metrics' internal cache handling and do not authorize intermediary storage.
Exact stale-service, expired-retention, and purge deadlines remain server
policy.

Missing or expired data is never replaced with zero. If one requested section
is serviceable and another is not, the API returns the useful partial report
with `Ready=False`; it returns 503 only when none of the requested sections is
usable.
