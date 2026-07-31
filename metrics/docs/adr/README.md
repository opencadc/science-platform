# Metrics ADR index

Architectural decisions for the Metrics service. **ADRs are the canonical
decision log** for durable design choices.

Consolidated 2026-07-31: same-theme ADRs were merged into the files below,
each keeping the lowest number of its group and listing what it consolidates
in its Status section. Retired numbers (0003, 0006–0009, 0011–0012, 0014,
0016–0018, 0021–0022) are absorbed there; their originals remain in git
history.

## Accepted

| ADR | Title | Milestone |
| --- | --- | --- |
| [0001](0001-kubernetes-first-environment.md) | Kubernetes-first environment contract | M1/M11 |
| [0002](0002-platform-metrics-contract.md) | Platform metrics contract (Kueue ClusterQueues) | M2–M4 |
| [0004](0004-http-caching-and-cache-scopes.md) | HTTP caching via headers; shared platform, private user scopes | M2/M4 |
| [0005](0005-runtime-composition-and-provider-lifecycle.md) | Runtime composition, provider lifecycle, and fail-fast startup | M2–M4 |
| [0010](0010-configuration-precedence-and-yaml-contract.md) | Configuration precedence and YAML contract | M3–M4 |
| [0013](0013-public-api-surface-and-sanitized-errors.md) | Public API surface — progressive routes, sanitized errors | M4+ |
| [0019](0019-opentelemetry-metrics-contract.md) | OpenTelemetry metrics contract | M8 |
| [0023](0023-kr8s-kubernetes-client.md) | kr8s is the Kubernetes client of choice | — |
| [0024](0024-quantiphy-resource-quantities.md) | Resource quantities parse via quantiphy | — |

## Proposed (planned milestones)

| ADR | Title | Milestone |
| --- | --- | --- |
| [0015](0015-interactive-quota-contract.md) | Interactive quota — scope, contract, list-on-request reads | M5 |
| [0020](0020-user-and-session-metrics-contracts.md) | UserMetrics and SessionMetrics contracts | M6/M7 |

System-wide ADRs: [`../../../docs/adr/`](../../../docs/adr/).
