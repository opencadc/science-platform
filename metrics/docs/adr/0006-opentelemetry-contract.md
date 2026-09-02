# ADR-0006: Optional application-state OTLP metrics

## Status

Accepted.

## Decision

Metrics may export application-state metrics over OTLP/HTTP to one external
endpoint. Export is enabled only when both
`METRICS_OTEL__METRICS_ENABLED=true` and
`METRICS_OTEL__EXPORTER_OTLP_ENDPOINT` are set. The signals cover request
duration, cache hits/misses, provider outcomes, fill coordination, Redis
health, lifecycle, and readiness. Metrics does not export OTLP traces or logs
and does not own the receiver.

Subject values, raw selectors, PromQL, credentials, and full backend URLs do
not become metric attributes. Scope and provider allowlists are
`platform|user|community|other` and `kueue|promql|other`, so Session traffic
and the `session` / `kubemetrics` providers export as `other`. The production
Helm chart contains no Collector or Alloy deployment. Instrument detail lives
in [`../specs.md`](../specs.md).
