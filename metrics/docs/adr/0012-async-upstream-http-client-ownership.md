# ADR-0012: Async upstream HTTP client ownership

## Status

Accepted (M4)

## Context

Providers previously mixed sync startup, ad hoc clients, and optional HTTP/2
dependencies. Lifecycle and connection limits must be centralized.

## Decision

- Provider constructors stay synchronous and network-free.
- One long-lived `httpx.AsyncClient` is created per **active** upstream and
  injected into its provider for startup checks and request-time reads.
- ADR-0022 supersedes the original runtime-ownership part of this decision:
  ownership transfers to the provider, which closes the client.
- Inactive providers remain absent from configuration and therefore open no
  clients or startup checks.
- Upstream clients use HTTP/1.1; HTTP/2 is not a supported setting or dependency.

## Consequences

- Parallel ClusterQueue GETs reuse the shared client pool configured via provider
  `http.*` settings.
- Enabling a provider requires a complete registry builder and transfers its
  client ownership to that provider.

## References

- [`0005-metrics-runtime-composition-root.md`](0005-metrics-runtime-composition-root.md)
