# ADR-0005: Runtime composition, provider lifecycle, and fail-fast startup

## Status

Accepted (M2–M4).
Consolidates ADR-0005 (composition root), ADR-0009 (fail-fast startup without
fallback), ADR-0011 (complete provider metrics without composition), ADR-0012
(async upstream client ownership), and ADR-0022 (provider-owned client
lifecycle). Client transport decisions from 0012/0022 are superseded by
ADR-0023 (kr8s).

## Context

M4 introduced a provider composition root, multiple cache backends, and a
history of partial provider fragments being stitched into responses. Startup
surfaces must match what operators actually enable, and silent degradation
hides misconfiguration.

## Decision

- **Composition root.** `MetricsRuntime.from_settings` constructs the active
  provider directly (Kueue is the only one; `sources.platform` is a
  `Literal["kueue"]`, so a registry indirection earned nothing — adding a
  provider means extending the Literal and branching here). It wires provider
  lifecycle, the cache backend, and `PlatformMetricsService`. Settings use
  nested `METRICS_*` env keys only (ADR-0010); list-like nested env values are
  JSON arrays.
- **One complete provider per scope.** Each metric scope is served by one
  provider returning a complete model. The runtime never stitches partial
  capacity/usage fragments across providers. Adding a scope requires config
  (`sources.*`), provider method, route, cache TTL, schemas, and tests
  together; removing a route removes its provider method and binding.
- **Active providers only.** Only complete, active providers belong in
  configuration and the client graph. Kueue is the only active provider;
  inactive providers open no clients and run no startup checks.
- **Fail-fast startup.** Active source dependencies are validated during
  FastAPI lifespan `startup()`. Misconfigured or unreachable upstreams make
  the process refuse to serve — no fallback provider, no partial data.
  Request-time failures map to HTTP errors and telemetry.
- **Provider-owned clients.** Provider constructors stay synchronous and
  network-free. The provider owns its Kubernetes access handle — since
  ADR-0023 a lazily built kr8s API (tests inject fakes) rather than an
  injected `httpx.AsyncClient`. `MetricsRuntime` starts and stops the
  provider once; cleanup continues after individual shutdown failures, and
  unexpected startup errors become a sanitized `RuntimeStartupError`.
- **Secret-free fingerprints.** Cache fingerprints cover provider identity
  (name, API version, sorted queue membership) and exclude secrets, CA
  material, transport tuning, and telemetry settings.

## Consequences

- Operators detect bad queue lists or RBAC at deploy time.
- Adding a provider requires `sources.*` wiring, startup checks, and tests
  before routes expose it.
- Proposed scopes in ADR-0015 and ADR-0020 are not runtime configuration
  until a complete provider implementation ships.

## References

- [`../architecture.md`](../architecture.md)
- [`../design.md`](../design.md)
- [`0023-kr8s-kubernetes-client.md`](0023-kr8s-kubernetes-client.md)
