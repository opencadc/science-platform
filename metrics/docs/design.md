# Metrics service design

Metrics has one deep asynchronous use case: get one report for one subject.
The route supplies the subject; the service owns source selection, aggregation,
cache freshness, optional efficiency, and the public conditions.

## Request flow

```text
GET route
  -> validate subject/path
  -> read shared Redis snapshot
  -> fresh: return
  -> stale: one Redis lease refreshes, other requests return stale
  -> cold/unserviceable: one Redis lease fills, followers await publication
  -> collect Kueue source
  -> optionally collect fixed PromQL efficiency
  -> assemble Metrics response and publish snapshot
```

The route never accepts a Kubernetes selector, PromQL expression, query URL, or
backend header. The subject is the only caller input used for aggregation.

## Kueue aggregation

The Kueue provider has three explicit read methods. They share normalization
and resource arithmetic but do not share source semantics.

### User report

1. List LocalQueues in every configured namespace.
2. Select exact `canfar.net/username=<username>` labels.
3. Verify each queue's referenced ClusterQueue is configured.
4. Add `flavorsReservation.resources[].total` for every selected queue.
5. Add `reservingWorkloads` for every selected queue.

All configured namespaces are required for a complete User total. A namespace
failure invalidates a cold fill; a previous serviceable complete snapshot may
be served instead. No Pod list is needed to calculate User requests.

### Community report

1. Start with the configured ClusterQueue names.
2. Select exact `canfar.net/community=<community>` labels.
3. Return 404 if no configured ClusterQueue matches.
4. Add each matching ClusterQueue's reservation resources and
   `reservingWorkloads`.

The Community boundary is the ClusterQueue label. Metrics does not derive it
from User LocalQueues or Cohort membership.

### Platform report

1. Read every configured ClusterQueue.
2. Add nominal quotas into `capacity`.
3. Add `flavorsUsage.resources[].total` into `allocated`.
4. Add `reservingWorkloads`.

Configured ClusterQueues are the complete Platform boundary. Cohorts are not
listed, traversed, or numerically added. The same resource name uses the same
public unit in `capacity` and `allocated`.

## Optional PromQL

The PromQL provider is disabled when
`METRICS_PROVIDERS__PROMQL__BASE_URL` is absent. Supplying that endpoint is the
only activation switch; there is no boolean enable setting. When the endpoint
is present, the provider selects a server-owned query by surface and resource.
Queries use the Prometheus/Mimir label names supplied by the exporter:

```text
User:      label_canfar_net_username=<subject>
Community: label_canfar_net_community=<subject>
Platform:  configured platform population
```

Every query requires the Pod to be Running. CPU efficiency is the aggregate
current CPU usage rate divided by aggregate CPU requests. Memory efficiency is
the aggregate current memory working set divided by aggregate memory requests.
The service omits a ratio when its denominator is zero or the result is not a
complete, valid instant vector. Query names, joins, and backend paths are
versioned inside Metrics; they are not a public query API.

Kueue requests and PromQL efficiency intentionally describe different current
populations: queue reservations include workloads holding quota, while
efficiency is restricted to Running Pods. The response labels only the values;
it does not pretend they are the same measurement.

## Single-flight and Redis

The durable identity is `(surface, subject, source revision)`. Redis stores an
authenticated versioned snapshot for that identity and a short-lived lease for
the current fill. A unique owner token is checked atomically on lease release.

Within one process, an in-flight task may be shared by requests with the same
identity. Across replicas, a lease winner performs the source read and
publishes the result; losers do not issue another Kueue or Prometheus request.
Different identities have independent leases and run concurrently. There is
no global request semaphore.

The fixed windows are:

| Surface | Fresh | Serviceable stale | Retained |
| --- | ---: | ---: | ---: |
| User | 2m | 3m | 5m |
| Community | 5m | 10m | 15m |
| Platform | 5m | 30m | 60m |

An expired retained snapshot is a recovery artifact, not an API result. If a
cold fill fails, the request returns 503 unless a complete serviceable
snapshot remains available.

## Public result and failure semantics

Every result has one subject, `status.observedAt`, `status.reservingWorkloads`,
resource values, and exactly one `Ready` plus one `Cached` condition.

| Situation | HTTP | Public result |
| --- | ---: | --- |
| Fresh or successful fill | 200 | Complete Kueue values; optional efficiency when available |
| Optional PromQL/Mimir failure | 200 | Kueue values, efficiency omitted, `Ready=False`/`PartialData` |
| User has no matching LocalQueue | 404 | Kubernetes Status error |
| Community has no matching configured ClusterQueue | 404 | Kubernetes Status error |
| Primary Kueue failure with serviceable snapshot | 200 | Previous complete snapshot with cache provenance |
| Primary Kueue failure without serviceable snapshot | 503 | Kubernetes Status error |

Raw upstream exception text, URLs, credentials, and query strings stay out of
the response. A missing quantity or metric is not converted into zero.

## Lifecycle and telemetry

FastAPI owns inbound requests and one application lifespan. Kubernetes and
Redis clients, optional PromQL HTTP transport, and OTLP metrics export are
closed by that lifespan. The app may export request, cache, provider, and
readiness metrics to an external OTLP/HTTP endpoint. It does not export OTLP
traces or logs. The production chart does not install an OTLP Collector or any
metrics backend.
