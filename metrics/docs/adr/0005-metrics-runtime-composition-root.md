# ADR-0005: MetricsRuntime composition root

## Status

Accepted

## Context

M4 introduced multiple provider types and cache backends. Startup surfaces must
match what operators actually enable.

## Decision

- **`MetricsRuntime`** is the composition root: active platform provider from
  `core/provider_registry.py`, long-lived `httpx` clients, cache backend, and
  `PlatformMetricsService`.
- **Inactive** provider packages (Prometheus, kube until their milestones) stay
  out of the HTTP client graph — no unused upstream clients at startup.
- Settings use nested `METRICS_*` env keys and optional YAML at
  `/etc/canfar/metrics/config.yaml`; list-like nested env values must be JSON
  arrays (not comma-separated strings).
- Optional HTTP/2 stays off by default to avoid an implicit `h2` dependency.

## Consequences

- Adding a provider requires registry wiring, startup checks, and tests before
  routes expose it.

## References

- [`../architecture.md`](../architecture.md)
- [`../design.md`](../design.md)
