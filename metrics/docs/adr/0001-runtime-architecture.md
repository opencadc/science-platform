# ADR-0001: Runtime architecture and Kueue client boundary

## Status

Accepted. The User/Community source boundary in this ADR is superseded by
[ADR-0010](0010-simple-kueue-metrics-service.md).

## Decision

Metrics is Kubernetes-first and environment-configured. The runtime is one
FastAPI process per Pod. FastAPI owns inbound HTTP; `kr8s` owns Kubernetes API
discovery and access; Redis is the shared deployed cache; optional PromQL and
OTLP metrics transports are external asynchronous clients.

Configuration comes from `METRICS_*` environment variables and Secret-backed
values. Nested settings use `__`; list settings are JSON arrays. The required
Kueue lists are `METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES` and
`METRICS_PROVIDERS__KUEUE__NAMESPACES`. The Kueue API is pinned to
`kueue.x-k8s.io/v1beta2`.

Kueue reads are deliberately narrow: LocalQueues are listed only in the
configured namespaces, and ClusterQueues are selected from the configured
cluster-scoped list. Constructors do not perform network I/O; the runtime
composes and closes providers and cache resources during the FastAPI lifespan.
Required primary-source configuration and access failures fail closed. The
service does not own a background producer or a second runtime role.

## Consequences

- RBAC must allow LocalQueue reads in every configured namespace and
  ClusterQueue reads for the configured names.
- The configured ClusterQueue list is the complete Platform boundary.
- A deployment can scale API replicas horizontally because Redis leases
  coordinate cache fills across replicas.
- Prometheus/Mimir and OTLP metrics infrastructure remain external deployment
  responsibilities.

## Historical boundary

The earlier Pod-inventory and lifetime-accounting proposal is not a current
source contract. Its accounting ADR is superseded by ADR-0010.
