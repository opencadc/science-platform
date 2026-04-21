# Changelog

## [Unreleased]

### Breaking changes

- `METRICS_PROVIDER_MODE` accepts only `static` and `kueue`. The former `live`
  value is normalized to `kueue` at settings load for one upgrade-friendly
  release cycle; remove `live` from charts when convenient.
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
