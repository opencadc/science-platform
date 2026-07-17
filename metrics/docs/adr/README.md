# Metrics ADR index

Architectural decisions for the Metrics service. **ADRs are the canonical
decision log** for durable design choices.

## Accepted

| ADR | Title | Milestone |
| --- | --- | --- |
| [0001](0001-kubernetes-first-environment.md) | Kubernetes-first environment contract | M1/M11 |
| [0002](0002-kueue-allocated-uses-flavors-total-only.md) | Kueue allocated uses flavors total only | M2 |
| [0003](0003-platform-capacity-allocated-unit-parity.md) | Platform capacity/allocated unit parity | M2 |
| [0004](0004-http-caching-via-headers.md) | HTTP caching via response headers | M2/M4 |
| [0005](0005-metrics-runtime-composition-root.md) | MetricsRuntime composition root | M4 |
| [0006](0006-clusterqueue-only-platform-metrics.md) | ClusterQueue-only platform metrics | M2/M4 |
| [0007](0007-open-ended-platform-resource-maps.md) | Open-ended platform resource maps | M2 |
| [0008](0008-platform-allocated-from-flavors-usage.md) | Platform allocated from flavorsUsage | M2 |
| [0009](0009-fail-fast-startup-without-provider-fallback.md) | Fail-fast startup without fallback | M2–M4 |
| [0010](0010-configuration-precedence-and-yaml-contract.md) | Configuration precedence and YAML contract | M3–M4 |
| [0011](0011-complete-provider-metrics-without-composition.md) | Complete provider metrics without composition | M4 |
| [0012](0012-async-upstream-http-client-ownership.md) | Async upstream HTTP client ownership | M4 |
| [0013](0013-sanitized-client-facing-error-responses.md) | Sanitized client-facing error responses | M4 |
| [0014](0014-progressive-public-route-surface.md) | Progressive public route surface | M4+ |
| [0019](0019-opentelemetry-metrics-contract.md) | OpenTelemetry metrics contract | M8 |

## Proposed (planned milestones)

| ADR | Title | Milestone |
| --- | --- | --- |
| [0015](0015-interactive-quota-scope-and-kube-provider-role.md) | Interactive quota scope and kube provider | M5 |
| [0016](0016-interactive-quota-api-contract.md) | InteractiveQuota API contract | M5 |
| [0017](0017-private-cache-for-user-scoped-metrics.md) | Private cache for user-scoped metrics | M5 |
| [0018](0018-list-on-request-kubernetes-reads-for-quota.md) | List-on-request Kubernetes reads for quota | M5 |
| [0020](0020-user-metrics-attribution-contract.md) | UserMetrics contract | M6 |
| [0021](0021-session-metrics-identity-contract.md) | SessionMetrics contract | M7 |

System-wide ADRs: [`../../../docs/adr/`](../../../docs/adr/).
