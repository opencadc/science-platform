# ADR-0006: Optional application-state OTLP metrics

## Status

Accepted. Amended: Session is a first-class scope and provider, and the
resource carries the service version.

## Decision

Metrics may export application-state metrics over OTLP/HTTP to one external
endpoint. Export is enabled only when both
`METRICS_OTEL__METRICS_ENABLED=true` and
`METRICS_OTEL__EXPORTER_OTLP_ENDPOINT` are set; enabling export without an
endpoint is a startup error, and startup logs whether export is on. The signals cover request duration, cache lookups and snapshot age, lease
outcomes (including failure cooldowns), fill outcomes, provider outcomes,
Redis command duration and health, lifecycle, and readiness. The resource
carries `service.version` and `service.instance.id`. Metrics does not export
OTLP traces or logs and does not own the receiver.

Subject values, raw selectors, PromQL, credentials, and full backend URLs do
not become metric attributes. Every attribute is drawn from a fixed allowlist:
scopes are `platform|user|community|session`, providers are
`kueue|session|promql`, and anything else is recorded as `other`. The
production Helm chart contains no Collector or Alloy deployment. Instrument
detail lives in [`../specs.md`](../specs.md).
