# ADR-0005: Redis freshness, single-flight, and outages

## Status

Accepted. The surface source boundary is defined by
[ADR-0010](0010-simple-kueue-metrics-service.md).

## Decision

Redis is one shared external cache for all Metrics replicas and read surfaces.
It stores authenticated, versioned snapshots and short-lived distributed
leases. Raw User and Community values are protected in key identities. The
production chart references Redis but does not provision, persist, replicate,
back up, or operate it.

Fresh/serviceable/retained windows are fixed:

| Surface | Fresh | Serviceable stale | Retained, not served |
| --- | ---: | ---: | ---: |
| User | 2m | 3m | 5m |
| Community | 5m | 10m | 15m |
| Platform | 5m | 30m | 60m |

Fresh snapshots return immediately. A stale-serviceable request may return
the stale snapshot while one lease winner refreshes it. A cold or
unserviceable request has one fill winner and cross-replica followers wait for
the same publication. Leases have bounded lifetimes and owner-checked
release. There is no background refresh worker.

If the primary Kueue source fails, a complete serviceable snapshot may be
served with stale cache provenance. If no serviceable snapshot exists, the API
returns sanitized HTTP 503. An optional Prometheus/Mimir failure does not
erase Kueue data: the API returns HTTP 200, omits efficiency, and marks
`Ready=False`/`PartialData`.

Redis failure does not trigger an uncoordinated source read per request. A
bounded process-local copy may serve a previously known serviceable snapshot
according to the same policy, but it is not a second shared cache and it never
extends the serviceable window.

## Consequences

- Horizontal API replicas share one cache-coordination boundary.
- Freshness is deterministic and differs by surface.
- Redis operations and source fills require bounded timeouts.
- Operators restore external Redis without flushing snapshots as a first step;
  cache state is recovery evidence.
