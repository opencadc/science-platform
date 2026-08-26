# ADR-0008: API-only asynchronous core

## Status

Accepted. The source and dependency boundary is refined by
[ADR-0010](0010-simple-kueue-metrics-service.md).

## Decision

The initial runtime is one FastAPI API process. External I/O and cache
coordination are asynchronous; pure resource normalization and aggregation are
synchronous. Dependencies are explicit constructor arguments composed by the
runtime. There is no operator, background refresh worker, generic service
locator, or second application process in the Metrics runtime.

The service uses one FastAPI lifespan to start and close Kubernetes, Redis,
optional PromQL, and optional OTLP metrics resources. Redis leases provide
cross-replica single-flight. Different subjects may fill concurrently.

Development has a fast host/test loop and a Kubernetes-first Helm/kind smoke
loop. Disposable integration dependencies may be installed by the test
harness, but they are not production chart components.

## Consequences

- Scaling is horizontal API replication, not a producer/consumer topology.
- A source read is request-triggered and bounded.
- A future background role must be proposed as a separate decision and must
  reuse the service boundaries rather than import FastAPI routes.
