# ADR-0009: Incremental Metrics package architecture

## Status

Accepted. The obsolete provider roles are superseded by
[ADR-0010](0010-simple-kueue-metrics-service.md).

## Decision

Evolve the existing `metrics/src/metrics` package in place. Keep the explicit
`api`, `core`, `providers`, `schemas`, `services`, `cache`, and `telemetry`
seams. The runtime composes one deep `get(subject)` use case that hides source
selection, Kueue aggregation, cache freshness, optional efficiency, and
condition derivation.

Providers normalize external documents into service-owned models. FastAPI
types stay in the API/schema boundary; Redis, Kubernetes client, HTTP client,
and OTLP metrics SDK types do not leak through the service interface. Do not
introduce a generic provider registry, DAO/manager layer, service locator, or
parallel domain/application/ports hierarchy.

The current provider boundary is intentionally small: Kueue LocalQueue and
ClusterQueue reads, optional fixed PromQL, and optional OTLP metrics export.
There is no Pod-inventory provider, lifetime-accounting package, Cohort
aggregation adapter, producer, or Metrics-owned monitoring stack.

## Consequences

- The public service can remain simple while source transport details stay
  behind narrow interfaces.
- Cache coordination remains a deep module: callers do not construct Redis
  keys, leases, or freshness states.
- A future source requires an explicit ADR and a complete contract update
  before it appears in a route.
