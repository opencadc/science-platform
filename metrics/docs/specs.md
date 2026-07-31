# Specifications reference

This file stores repository-specific behavioral specifications.

## Ownership

- Define expected behavior as testable outcomes.
- Keep each spec tied to observable API or module behavior.
- Durable decisions live in [`docs/adr/README.md`](adr/README.md).

## Update rules

- Ensure every factual claim is verifiable from code.
- Use concrete examples and acceptance checks.

## Service behavior specifications

- The API exposes `GET /api/v1/metrics/platform` and `GET /healthz` (M4).
- Runtime configuration is environment-driven through `METRICS_*` settings and
  validated through Pydantic `Settings` (`providers`, `sources`, `cache`)
  against optional YAML; see `environment-contracts.md`.
- Startup must fail fast when required source dependencies are unavailable
  for the active platform provider (Kueue in M4). Inactive provider
  configuration is rejected.
- A provider selected for `sources.platform` must implement the asynchronous
  `PlatformMetrics.platform()` read. Binding fails during runtime construction
  when that observable capability is absent; no separate scope declaration can
  override the provider's behavior.
- Cache behavior is communicated via HTTP headers (`Cache-Control`, `Date`,
  `Expires`, and `Last-Modified`) for platform responses. Per-scope TTLs are
  typed in `CacheConfig` (`cache.scope_ttl_seconds`); the platform scope can
  override the default TTL.
- For `GET /api/v1/metrics/platform`, each key present in `data.capacity` is
  also present in `data.allocated`, and the **same resource name must use the
  same unit in both maps** (CPU as decimal core counts, memory as `Gi` binary
  quantities, other resources with the same formatting rules in both). Callers
  can compare the two without converting between millicores and cores.
- Platform `data.allocated` is summed from
  `status.flavorsUsage.resources[].total`; do not add `borrowed` separately
  because Kueue total already includes borrowed quota.
- Kueue resource quantities must be strings matching Kubernetes decimal SI,
  binary SI through `Ei`, or signed-exponent syntax. Parsing and accumulation
  use exact decimal arithmetic. Missing, whitespace-padded, malformed,
  negative, non-finite, or base-unit-overflowing values fail the provider read;
  an absent allocation for a capacity key remains a same-unit zero.
- Successful platform responses retain the versioned envelope
  (`version`, `kind: PlatformMetrics`, `metadata.created`, `status`, `data`),
  open resource-name maps, deterministic resource ordering, and snapshot
  timestamp reuse across cache hits.
- Request-time provider unavailability maps to HTTP 503; provider execution
  failure maps to HTTP 502. Error envelopes use `kind: Status`,
  `status: Error`, and `Cache-Control: no-store`, without raw URLs, tokens,
  quantity payloads, exception text, or class names.
- Cache keys contain platform scope, schema version, cluster, and the
  non-secret provider fingerprint. Memory and Redis backends preserve the same
  TTL and JSON snapshot semantics. The current service has no stale-response
  fallback: an expired/missing snapshot requires a successful provider read.
- Custom telemetry keeps the accepted `canfar.metrics.http.requests`,
  `canfar.metrics.provider.duration`, `canfar.metrics.cache.lookups`, and
  `canfar.metrics.compute.duration` instruments and their bounded attributes.

## Decision linkage

Canonical decisions: [`docs/adr/README.md`](adr/README.md).

## Proposed decisions

ADRs 0015–0018 and 0020–0021 remain proposed. Nothing in those ADRs is accepted
runtime configuration or a shipped route.
