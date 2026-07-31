# ADR-0005: MetricsRuntime composition root

## Status

Accepted

## Context

M4 introduced a provider composition root and multiple cache backends. Startup
surfaces must match what operators actually enable.

## Decision

- **`MetricsRuntime`** is the composition root: active platform provider from
  `core/provider_registry.py`, long-lived `httpx` clients, cache backend, and
  `PlatformMetricsService`.
- Only complete, active providers belong in configuration and the HTTP client
  graph. Kueue is the only active provider.
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
