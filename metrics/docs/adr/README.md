# Metrics ADR index

These ADRs record durable Metrics decisions (the *why*). The wire contract,
source rules, cache windows, and failure matrix live in
[`../specs.md`](../specs.md) (implementation-of-record). The
[Confluence API Contract](https://herzberg.atlassian.net/wiki/spaces/C/pages/2690809875/API+Contract)
remains product-design authority; git specs may lead Confluence when they
diverge.

| ADR | Title | Status |
| --- | --- | --- |
| [0001](0001-runtime-architecture.md) | Runtime architecture and Kueue client boundary | Accepted |
| [0005](0005-redis-freshness-and-outages.md) | Redis freshness and outage policy | Accepted |
| [0006](0006-opentelemetry-contract.md) | Application-state OTLP metrics | Accepted |
| [0009](0009-incremental-module-architecture.md) | Incremental Python package architecture | Accepted |
| [0010](0010-simple-kueue-metrics-service.md) | Simple Kueue Metrics service (product boundary) | Accepted |
| [0011](0011-session-metrics-surface.md) | Session Metrics surface (pointer to 0010 / specs) | Accepted |

Former ADRs 0002, 0003, 0004, 0007, and 0008 were removed after their durable
content was folded into 0001, 0010, or specs. Numbers are not reused.
Accounting, Cohort, and Pod-as-primary contracts remain excluded.

## External authority

- [Confluence API Contract](https://herzberg.atlassian.net/wiki/spaces/C/pages/2690809875/API+Contract)
- [Confluence Technical Design](https://herzberg.atlassian.net/wiki/spaces/C/pages/2690809867/Technical+Design)
- [Confluence Implementation Specifications](https://herzberg.atlassian.net/wiki/spaces/C/pages/2690449417/Implementation+Specifications)
- [CADC-16077](https://herzberg.atlassian.net/browse/CADC-16077) for execution state
