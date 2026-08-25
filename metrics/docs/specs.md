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

- The metrics API exposes only
  `GET /apis/canfar.net/v1alpha1/metrics/platform/canfar`; probes remain
  available at `/healthz`, `/livez`, and `/readyz`.
- Runtime configuration is environment-driven through `METRICS_*` settings;
  see `environment-contracts.md`.
- Startup must fail fast when required source dependencies are unavailable
  for the active platform provider (Kueue in M4). Inactive provider
  configuration is rejected.
- The Kueue provider implements asynchronous `read_platform()` and returns a
  transport-neutral observation to `MetricsService`.
- Every metrics response uses `Cache-Control: no-store`. `Last-Modified`, `Age`,
  and RFC 9211 `Cache-Status` describe the internal snapshot.
- For the platform route, each named `status.resources` entry contains both
  `capacity` and `allocated`, and the **same resource name must use the
  same unit in both maps** (CPU as decimal core counts, memory as `Gi` binary
  quantities, other resources with the same formatting rules in both). Callers
  can compare the two without converting between millicores and cores.
- Platform `allocated` is summed from
  `status.flavorsUsage.resources[].total`; do not add `borrowed` separately
  because Kueue total already includes borrowed quota.
- Kueue resource quantities must be strings matching Kubernetes decimal SI,
  binary SI through `Ei`, or signed-exponent syntax. Parsing uses quantiphy;
  values agree to the 6-decimal API formatting (ADR-0001). Missing,
  whitespace-padded, malformed, negative, non-finite, or base-unit-overflowing
  values fail the provider read; an absent allocation for a capacity key
  remains a same-unit zero.
- Successful platform responses use `apiVersion: canfar.net/v1alpha1`,
  `kind: Metrics`, `metadata.name: platform-canfar`, `spec.platform: canfar`,
  deterministic named resources, `status.observedAt`, and exactly `Ready` and
  `Cached` conditions.
- Request-time source failures map to HTTP 503. Errors use Kubernetes
  `apiVersion: v1`, `kind: Status`, `status: Failure` payloads without raw URLs,
  tokens, quantity payloads, exception text, or class names.
- Cache keys contain platform scope, schema version, cluster, and the
  non-secret provider fingerprint. Memory and Redis backends preserve the same
  freshness and JSON snapshot semantics. Stale-serviceable reports retain their
  original observation time; unserviceable reads return 503.
- Custom telemetry keeps the accepted `canfar.metrics.provider.duration`,
  `canfar.metrics.cache.lookups`, and `canfar.metrics.compute.duration`
  instruments and their bounded attributes; HTTP request metrics come from
  FastAPI auto-instrumentation (ADR-0002).

## Decision linkage

Canonical decisions: [`docs/adr/README.md`](adr/README.md).

## Proposed decisions

ADR-0003 remains proposed. Nothing in it is accepted runtime configuration or
a shipped route.
