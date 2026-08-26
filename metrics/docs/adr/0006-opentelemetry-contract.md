# ADR-0006: Optional application-state OTLP metrics

## Status

Accepted. The telemetry scope is simplified by
[ADR-0010](0010-simple-kueue-metrics-service.md).

## Decision

Metrics may export application-state metrics over OTLP/HTTP to one external
endpoint. The signals cover request duration, cache hits/misses, Kueue source
outcomes, optional PromQL outcomes, fill coordination, and readiness. Metrics
does not export OTLP traces or logs and does not own the receiver or a metrics
storage backend.

Telemetry is disabled unless explicitly configured with an endpoint. Subject
values, raw Kubernetes selectors, PromQL, credentials, and full backend URLs do
not become metric attributes. Attributes remain bounded and low-cardinality;
subject-specific detail belongs in sanitized logs or operational diagnostics,
not in the exported metric label set.

## Consequences

- The production Helm chart contains no Collector or Alloy deployment.
- A deployment may route OTLP to its existing Collector, Alloy, or compatible
  metrics receiver.
- Disposable integration fixtures may receive OTLP metrics for assertions without
  changing production ownership.
