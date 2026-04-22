# CANFAR metrics initial production debug loop (M8 support plan)

This runbook defines the debug and stabilization loop immediately after first
production deployment of the M1-M6 milestone set.

## Entry criteria

Start this loop after:

- image build and push complete,
- Helm values are prepared for the target environment, and
- smoke tests pass against the deployed endpoint.

## Stabilization steps

1. Verify health and route readiness.
2. Verify `Cache-Control`, `Date`, `Expires`, and `Last-Modified` headers.
3. Verify OTel request and provider signals.
4. Compare payload values against expected source snapshots.
5. Review latency and error trends during the first rollout window.
6. Tune TTL and timeout settings only when observed behavior requires changes.

## Operational checks

- `GET /healthz` returns `200`.
- `GET /api/v1/metrics/platform` returns a valid `PlatformMetrics` envelope.
- `data.sources` reflects configured source paths.
- Runtime configuration matches the intended milestone contract.
- Cache semantics and TTL match configured values.
- Provider failures map to deterministic API errors.

## Exit criteria

Close the loop when all checks stay green for the agreed stabilization window.

- No unexplained response error spikes.
- No sustained cache miss anomalies.
- No unresolved provider timeout regressions.
- Platform payload stays contract-stable.
