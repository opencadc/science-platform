# ADR-0001: Runtime architecture and Kueue client boundary

## Status

Accepted.

## Decision

Metrics runs as one FastAPI process per Pod. FastAPI owns inbound HTTP;
external I/O and cache coordination are asynchronous; pure resource
normalization is synchronous. The FastAPI lifespan starts and closes
Kubernetes (`kr8s`), Redis, optional PromQL, and optional OTLP clients.
Constructors do not perform network I/O. There is no operator, periodic
refresh worker, generic service locator, or second application process.

Configuration comes from `METRICS_*` environment variables. Nested settings
use `__`; list settings are JSON arrays. The required Kueue lists are
`METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES` and
`METRICS_PROVIDERS__KUEUE__NAMESPACES`. The Kueue API is pinned to
`kueue.x-k8s.io/v1beta2`. LocalQueues are listed only in the configured
namespaces; ClusterQueues are selected only from the configured list.

Horizontal scale relies on Redis leases across replicas. Prometheus/Mimir and
OTLP receivers remain external deployment responsibilities. The wire contract
and package layout live in [`../specs.md`](../specs.md).
