# ADR-0005: Redis freshness, single-flight, and outages

## Status

Accepted. Amended: snapshots have two stages (fresh and stale) and the
retained stage is removed; one atomic Redis read decides the stage and the
single refresh owner.

## Decision

Redis is the shared external cache for every Metrics replica and read surface.
It stores authenticated, versioned snapshots and short-lived leases. Subject
values never appear in key names: keys carry an HMAC digest of the subject
identity, wrapped in a hash tag so a subject's value and lease share one Redis
Cluster slot. The production chart references Redis but does not provision or
operate it.

A snapshot has exactly two stages, fresh and stale, with fixed per-surface
windows (see [`../specs.md`](../specs.md)). It is written with a Redis TTL
equal to its stale window, measured from the start of its fill, and its stage
is derived from the remaining TTL. No replica or source clock is compared, and
nothing outlives the stale window.

One Lua script reads the value, its remaining TTL, and the lease, and claims
the lease when the snapshot is stale or absent and the lease is free. That
makes the refresh decision atomic across replicas:

- A fresh snapshot is served.
- A stale snapshot is served immediately by every request. The one request
  that claimed the lease refreshes it in the background; there is no periodic
  refresh worker.
- An absent snapshot has one fill owner. Requests on the same replica share its
  result; other replicas poll Redis for the publication.

A second Lua script is the owner's only exit: publish, failure cooldown, or
release, each fenced on the lease token so a stalled owner cannot overwrite its
successor. The lease covers one fill plus two Redis commands and a margin, and
must end before a cold waiter's deadline so a crashed owner is replaced. A
failed fill leaves a short cooldown marker in the lease key; while it lasts no
replica calls the source, stale snapshots keep being served, and cold requests
fail fast with the same sanitized error the owner saw (503 for a source
failure, 500 for an internal one). A subject-level not-found is published for the
fresh window.

Optional PromQL, Session usage, or Pod-list failure does not erase primary
data: HTTP 200 with `Ready=False`/`PartialData`.

Redis failure never triggers an uncoordinated source read. A bounded
process-local copy of snapshots this replica already saw is served during an
outage with its real stage, until the snapshot's Redis TTL would have ended.
Otherwise the API returns 503 with `Retry-After: 1`. The copy is not a second
shared cache and never extends a window.

A change to the stored payload or its meaning bumps the cache schema revision,
which is part of every key, so mixed versions during a rollout never read each
other's snapshots and a rollback needs no cleanup.

## Consequences

- Stale traffic never waits for a source, and each stale or absent snapshot
  causes at most one source read across all replicas until a publish or a
  cooldown ends.
- Operators see at most one failed-fill log line per cooldown per subject.
- Readiness does not follow Redis health after startup; see
  [ADR-0012](0012-latched-readiness.md).
