# Changelog

## [Unreleased]

### Breaking changes

- **M3:** `METRICS_PROVIDER_MODE`, static capacity/usage providers, node-based
  capacity, and `FallbackCapacityProvider` are removed. The service always
  composes platform metrics from Kueue (`KueuePlatformEngine`) plus Prometheus
  usage adapters; configure nested `METRICS_PLATFORM__*` env vars (or legacy
  flat `METRICS_KUBE_*`, `METRICS_KUEUE_*`, `METRICS_PROMETHEUS_URL`, and
  related keys) as documented in `docs/environment-contracts.md`.
- Lifespan startup now requires a reachable **Prometheus** URL in addition to
  valid Kueue/Kubernetes settings (fail-fast before traffic).
- `METRICS_PLATFORM__KUBE_METRICS__ENABLED=true` is rejected until milestone M4
  ships kube-metrics runtime depth.
- `GET /api/v1/metrics/platform` JSON uses open string maps `capacity` and
  `allocated` (Kubernetes quantity strings). Clients that expected the older
  nested `usage` / typed snapshot shape must update.
- Success responses no longer include a `data.sources` field (platform, user,
  and session metrics).
- Success JSON no longer includes `metadata.cached` or `metadata.ttl`. Use HTTP
  response headers (`Cache-Control`, `Date`, `Expires`, `Last-Modified`) for
  freshness and cache visibility; platform routes may use `public` when
  configured, while user/session routes use `private`.

### Documentation

- Operator env precedence for `KUEUE_METRICS_*` vs `METRICS_*` is documented in
  `docs/environment-contracts.md`.

## 0.1.0 (2026-04-17)

### Features

- Initial Metrics API service and delivery foundation milestone.
