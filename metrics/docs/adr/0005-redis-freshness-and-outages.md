# ADR-0005: Redis freshness, single-flight, and outages

## Status

Accepted.

## Decision

Redis is the shared external cache for every Metrics replica and read surface.
It stores authenticated, versioned snapshots and short-lived distributed
leases. Subject-bearing key segments are HMAC-protected digests. The production
chart references Redis but does not provision or operate it.

Fresh, serviceable-stale, and retained windows are fixed per surface (see
[`../specs.md`](../specs.md)). Fresh snapshots return immediately. A stale
serviceable request may return the stale snapshot while one lease winner
performs a **request-triggered** refresh; there is no periodic refresh worker.
A cold or unserviceable miss has one fill winner and cross-replica followers
wait for the same publication.

If the primary source fails, a complete serviceable snapshot may be served
with stale cache provenance (`Ready=False`/`StaleData`). Otherwise the API
returns sanitized HTTP 503. Optional PromQL or Session usage failure does not
erase primary data: HTTP 200 with `Ready=False`/`PartialData`.

Redis failure does not trigger an uncoordinated source read per request. A
bounded process-local L1 copy may serve a previously known serviceable
snapshot under the same windows; it is not a second shared cache and never
extends the serviceable window.
