# CANFAR metrics initial production debug loop

This runbook defines the debug and stabilization loop immediately after the
first production deployment. The goal is to validate platform metrics behavior
before enabling additional feature slices.

## Entry criteria

Start this loop after:

- image build and push complete,
- Helm values are prepared for the target environment, and
- smoke tests pass against the deployed endpoint.

## Stabilization steps

Use this sequence for every deployment candidate.

1. Verify health and route readiness.
2. Verify `Cache-Control` and `X-Metrics-Cached` headers.
3. Verify OTel request and provider signals are emitting.
4. Compare payload values against expected source snapshots.
5. Review latency and error rate trends for the first rollout window.
6. Tune TTL and timeout settings only if observed behavior requires changes.

## Operational checks

Track these checks throughout the stabilization window.

- **Health check:** `GET /healthz` returns `200`.
- **Platform payload:** `GET /api/v1/metrics/platform` returns a valid
  `PlatformMetrics` envelope.
- **Source provenance:** `data.sources` records capacity and usage provider
  source path.
- **Mode alignment:** confirm the deployed configuration matches the intended
  single-mode contract from the active milestone plan, including startup
  validation behavior and the absence of unintended fallback paths.
- **Cache semantics:** first request misses cache and subsequent request hits
  cache within TTL window.
- **TTL policy:** confirm `Cache-Control` max-age and JSON metadata TTL match the
  active milestone default and the configured `METRICS_CACHE_TTL_SECONDS` value.
- **Error semantics:** provider failures map to deterministic API errors.

## Exit criteria

Close the loop when all checks remain green for the agreed rollout window.

- No unexplained spikes in response errors.
- No sustained cache miss anomaly.
- No unresolved provider timeout regressions.
- Platform payload remains contract-stable.
