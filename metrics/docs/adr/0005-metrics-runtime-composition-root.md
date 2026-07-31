# ADR-0005: MetricsRuntime composition root

## Status

Accepted

## Context

M4 introduced a provider composition root and multiple cache backends. Startup
surfaces must match what operators actually enable.

## Decision

- **`MetricsRuntime`** is the composition root: active platform provider from
  `core/provider_registry.py`, provider lifecycle, cache backend, and
  `PlatformMetricsService`. ADR-0022 supersedes the original client-ownership
  part of this decision: the provider owns its injected `httpx` client.
- Only complete, active providers belong in configuration and the HTTP client
  graph. Kueue is the only active provider.
- Settings use nested `METRICS_*` env keys and optional YAML at
  `/etc/canfar/metrics/config.yaml`; list-like nested env values must be JSON
  arrays (not comma-separated strings).
- Upstream clients use HTTP/1.1; HTTP/2 is not a supported setting.

## Consequences

- Adding a provider requires registry wiring, startup checks, and tests before
  routes expose it.

## References

- [`../architecture.md`](../architecture.md)
- [`../design.md`](../design.md)
