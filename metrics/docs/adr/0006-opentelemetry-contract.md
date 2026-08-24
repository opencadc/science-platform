# ADR-0006: OpenTelemetry operational contract

## Status

Accepted.

## Decision

Metrics provides first-class OpenTelemetry metrics and traces over OTLP plus
structured JSON stdout logs correlated with trace and span IDs. Applications
send telemetry to an in-cluster Alloy or OpenTelemetry Collector rather than
holding direct backend credentials. W3C Trace Context propagates across the
upstream proxy or client, Metrics, and downstream HTTP calls.

All signals share bounded resource identity including service name and
namespace, release version, deployment environment, cluster, Kubernetes
namespace, and Pod UID as the service instance. Usernames, community or subject
identifiers, raw selectors, PromQL, and full query URLs are forbidden in metric
attributes, span names/attributes, and ordinary logs. Metrics and traces are
enabled by default in staging and production with independent signal controls;
the Collector forwards correlated logs to the configured log backend.

Trace sampling is 100% in development and staging. Production begins at 100%
while traffic is low so operational behavior can be characterized. If volume
later requires sampling, the application continues to emit without
subject-dependent rules and the Collector applies tail sampling, retaining all
errors and slow traces. Sampling policy must never depend on User or Community
identity.

The local Collector uses its debug exporter for human inspection. The smoke
profile additionally enables a pinned file exporter writing ephemeral OTLP
JSON so automated assertions can prove required spans and metrics and reject
subject identifiers, selectors, PromQL, and concrete paths. The file is test
evidence only: production enables neither the debug nor file exporter.

## Consequences

- The current metrics-only SDK setup, exact-endpoint handling, chart wiring,
  resource attributes, and dashboard metric-name drift must be corrected.
- Cache hit/miss/error/stale/refresh behavior and downstream query outcomes
  require bounded telemetry, without subject cardinality.
