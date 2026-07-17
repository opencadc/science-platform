# ADR-0019: OpenTelemetry metrics contract

## Status

Accepted (M8)

## Context

Production rollout requires observable request, cache, provider, and compute
signals without coupling product metrics to a specific collector implementation.

## Decision

- When **`METRICS_OTEL_METRICS_ENABLED=true`** (and related settings) enable OTel,
  emit custom application metrics via `OpenTelemetryMetricsRecorder`:
  - `canfar.metrics.http.requests` — scope, HTTP status, cache hit
  - `canfar.metrics.provider.duration` — provider name, scope, status
  - `canfar.metrics.cache.lookups` — backend, hit/miss, scope
  - `canfar.metrics.compute.duration` — scope, status
- Settings fields (nested env `METRICS_*` + `__`): `otel_metrics_enabled`,
  `otel_service_name`, `otel_exporter_otlp_endpoint`, `otel_export_interval_millis`.
- Disabled mode uses `NoopMetricsRecorder`.
- FastAPI and httpx **auto-instrumentation** (spans/metrics from OTel
  instrumentors in `factory.py`) may run alongside these custom meters; v1 contract
  documents the **`canfar.metrics.*`** meters above for rollout checklists.
- Charts consume **`telemetry.otlpEndpoint`** from GitOps (maps to
  `otel_exporter_otlp_endpoint`); ops owns the collector—Metrics pushes OTLP HTTP
  metrics only in v1 (not traces/logs product contract).

## Consequences

- M9 production debug loop uses OTEL checklist items alongside HTTP header checks.
- Skaha OTEL is a separate rollout (shared chart values pattern in deployments).

## References

- `src/metrics/telemetry.py`
