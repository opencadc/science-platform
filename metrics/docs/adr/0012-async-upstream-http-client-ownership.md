# ADR-0012: Async upstream HTTP client ownership

## Status

Accepted (M4)

## Context

Providers previously mixed sync startup, ad hoc clients, and optional HTTP/2
dependencies. Lifecycle and connection limits must be centralized.

## Decision

- Provider constructors stay synchronous and network-free.
- `MetricsRuntime` owns one long-lived `httpx.AsyncClient` per **active**
  upstream, injected into providers for startup checks and request-time reads.
- Inactive configured providers must not open clients or run startup checks.
- Optional HTTP/2 stays **off by default** to avoid an implicit `h2` install.

## Consequences

- Parallel ClusterQueue GETs reuse the shared client pool configured via provider
  `http.*` settings.
- Enabling a new provider in config implies client creation in the runtime graph.

## References

- [`0005-metrics-runtime-composition-root.md`](0005-metrics-runtime-composition-root.md)
