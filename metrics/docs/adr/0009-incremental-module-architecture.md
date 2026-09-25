# ADR-0009: Incremental Metrics package architecture

## Status

Accepted. Amended: shared Kubernetes reads and snapshot assembly each have one
module.

## Decision

Evolve `metrics/src/metrics` in place. Keep the explicit `api`, `core`,
`providers`, `schemas`, `services`, `cache`, and `telemetry` seams, plus
top-level `http_cache`, `errors`, `names`, and `dev` helpers. The runtime composes one
deep `get(subject)` use case that hides source selection, aggregation, cache
freshness, optional efficiency or usage, and condition inputs.

Providers normalize external documents into service-owned models. Every
Kubernetes read goes through `providers/kube`, which owns status mapping,
credential refresh, paging, and fan-out, so Kueue, Session, and usage
providers share one failure model. `services/snapshots` assembles each
surface's snapshot: the primary read, then optional enrichment bounded by the
time left in the fill. `services/metrics` maps cache outcomes to reports and
sanitized errors. FastAPI types stay in the API/schema boundary; Redis,
Kubernetes client, HTTP client, and OTLP SDK types do not leak through the
service interface. Do not introduce a generic provider registry, DAO/manager
layer, service locator, or parallel domain/application/ports hierarchy.

The provider boundary is Kueue LocalQueue and ClusterQueue reads, Session Job
aggregation, optional `metrics.k8s.io` Session usage, optional fixed PromQL,
and optional OTLP export. There is no Pod-inventory-as-primary provider,
lifetime-accounting package, Cohort adapter, producer, or Metrics-owned
monitoring stack. The package table lives in [`../specs.md`](../specs.md).
