# Metrics ADR index

ADRs below hold the durable Metrics decisions. ADRs 0001–0003 were consolidated 2026-07-31 from the
former 0001–0024 set (twice-collapsed); each file lists what it absorbed, and
originals live in git history.

| ADR | Title | Status |
| --- | --- | --- |
| [0001](0001-runtime-architecture.md) | Runtime architecture — Kubernetes-first, env-only config, kr8s, quantiphy | Accepted |
| [0002](0002-platform-api-contract.md) | Platform API contract — aggregation, caching, errors, telemetry | Accepted |
| [0003](0003-proposed-scopes.md) | Proposed scopes — interactive quota, user and session metrics | Superseded by ADR-0004 |
| [0004](0004-canfar-metrics-resource.md) | Metrics unified subject resource in the CANFAR API group | Accepted |
| [0005](0005-redis-freshness-and-outages.md) | Redis freshness, staleness, and outage policy | Accepted |
| [0006](0006-opentelemetry-contract.md) | OpenTelemetry signals and telemetry boundary | Accepted |
| [0007](0007-resource-time-accounting.md) | Active-workload lifetime usage and efficiency accounting | Accepted |
| [0008](0008-api-only-async-core.md) | API-only async core and two-loop development | Accepted |
| [0009](0009-incremental-module-architecture.md) | Incremental deepening of the existing Python package | Accepted |

System-wide ADRs: [`../../../docs/adr/`](../../../docs/adr/).
