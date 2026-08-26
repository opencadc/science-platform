# Metrics ADR index

These ADRs record durable Metrics decisions. The approved product design is
canonical in the [Confluence API Contract](https://herzberg.atlassian.net/wiki/spaces/C/pages/2690809875/API+Contract);
the Git ADRs record the implementation boundary and its deliberate exclusions.

| ADR | Title | Status |
| --- | --- | --- |
| [0001](0001-runtime-architecture.md) | Runtime architecture and Kueue client boundary | Accepted; source details superseded by 0010 |
| [0002](0002-platform-api-contract.md) | API contract, aggregation, and response policy | Partially superseded by 0010 |
| [0003](0003-proposed-scopes.md) | Proposed future scopes | Superseded by 0004 and 0010 |
| [0004](0004-canfar-metrics-resource.md) | Unified Metrics resource | Accepted; source details superseded by 0010 |
| [0005](0005-redis-freshness-and-outages.md) | Redis freshness and outage policy | Accepted |
| [0006](0006-opentelemetry-contract.md) | Application-state OTLP metrics | Accepted |
| [0007](0007-resource-time-accounting.md) | Resource-time accounting | Superseded by 0010 |
| [0008](0008-api-only-async-core.md) | API-only asynchronous core | Accepted; Pod/accounting details superseded by 0010 |
| [0009](0009-incremental-module-architecture.md) | Incremental Python package architecture | Accepted; obsolete provider details superseded by 0010 |
| [0010](0010-simple-kueue-metrics-service.md) | Simple Kueue Metrics service | Accepted |

ADR-0010 is the current source and scope boundary. Older conflicting text is
retained only where it explains a historical choice; it must not be read as a
current accounting, Cohort, or Pod-source contract.

## External authority

- [Confluence API Contract](https://herzberg.atlassian.net/wiki/spaces/C/pages/2690809875/API+Contract)
- [Confluence Technical Design](https://herzberg.atlassian.net/wiki/spaces/C/pages/2690809867/Technical+Design)
- [Confluence Implementation Specifications](https://herzberg.atlassian.net/wiki/spaces/C/pages/2690449417/Implementation+Specifications)
- [CADC-16077](https://herzberg.atlassian.net/browse/CADC-16077) for execution state
