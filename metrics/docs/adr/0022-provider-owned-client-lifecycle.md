# ADR-0022: Provider-owned client lifecycle

## Status

Accepted

## Context

The runtime previously owned both a provider and the HTTP client injected into
that provider. This split ownership required a bundle type and made shutdown
responsibility ambiguous.

## Decision

- The closed registry maps the `kueue` name to a synchronous, network-free
  builder that returns one provider.
- The builder injects one configured asynchronous HTTP client into
  `KueueProvider` and transfers ownership of that client to the provider.
- `MetricsRuntime` owns the provider and cache resources. It starts and stops
  the provider once. Cleanup continues after an individual shutdown failure.
- Startup failure cleans up resources already constructed. Unexpected failures
  become a sanitized `RuntimeStartupError`.
- Kueue fingerprints include provider name, Kubernetes endpoint,
  ClusterQueue resource path, and sorted queue membership. Secrets, CA contents,
  transport tuning, and telemetry settings are excluded.

This supersedes only the client-ownership statements in ADR-0005 and ADR-0012.
Their network-free construction, active-provider-only, configuration, and HTTP
protocol decisions remain in force.

## Consequences

- `KueueProvider.shutdown()` is the only owner that closes its HTTP client.
- The runtime no longer carries a platform client field or a client/provider
  bundle.

## References

- [`0005-metrics-runtime-composition-root.md`](0005-metrics-runtime-composition-root.md)
- [`0012-async-upstream-http-client-ownership.md`](0012-async-upstream-http-client-ownership.md)
