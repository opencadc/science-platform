# Kueue platform metrics (developer guide)

This document explains **why** the Kueue integration is structured the way it
is and **which modules** participate. It complements
[`docs/adr/README.md`](adr/README.md) and operator-facing notes in
[`environment-contracts.md`](environment-contracts.md). For the extension pattern
(config, registry, provider, scopes), see
[`adr/0005-metrics-runtime-composition-root.md`](adr/0005-metrics-runtime-composition-root.md)
and ADR-0011.

## Goals

- **Single Kueue seam:** Platform maps come only from `providers/kueue.py`
  (URLs, startup checks, nominal-quota parsing, and aggregation).
- **Fail fast:** Misconfiguration or a missing API is detected at **startup**
  when the active platform provider runs `startup()` during app lifespan.
- **Honest aggregation:** Platform capacity and allocation are derived from the
  configured ClusterQueue set only. `allocated` reflects admitted usage from
  `status.flavorsUsage.resources[].total`.
- **Stable response contract:** Borrowed/lending response-field expansion is out
  of scope for this delivery; the platform API remains `capacity` and
  `allocated` maps only.

## Responsibility split (M4)

- The **Kueue provider** (`metrics.providers.kueue`) runs startup HTTP checks
  against the Kubernetes API and performs platform capacity/allocated
  aggregation.
- **`MetricsRuntime`** owns the cache backend, `httpx` client lifecycle, and
  which provider is active for `sources.platform`.
- **Startup vs request:** validation for required upstreams runs during
  `startup()` in lifespan; the `PlatformMetricsService` path serves cached
  results and maps request-time failures to HTTP/telemetry (without exposing raw
  upstream error strings in response bodies where security review disallows it).

## Module map

| Module | Role |
| --- | --- |
| `metrics.core.runtime` | `MetricsRuntime`: registry-driven provider wiring, cache backend, platform cache keys, `get_platform_metrics`. |
| `metrics.core.provider_registry` | Maps `sources.platform` to a concrete provider + HTTP client bundle. |
| `metrics.providers.kube_http` | Shared TLS/token handling and parallel `httpx` GET helper (injected client). |
| `metrics.providers.kueue` | URLs, startup, `sum_nominal_quotas_by_resource`, platform aggregation, fingerprinting. |
| `metrics.core.factory` | FastAPI `create_app`, lifespan, telemetry hooks. |
| `metrics.services.platform_metrics` | TTL cache, telemetry, and error mapping for `/platform`. |

## Request flow

1. **Startup:** Lifespan builds `MetricsRuntime.from_settings`, then `await runtime.start()`.
2. **HTTP GET** `/api/v1/metrics/platform`: route depends on `MetricsRuntime`;
   `runtime.get_platform_metrics()` delegates to `PlatformMetricsService`.
3. **Miss:** Service calls the bound loader → `KueueMetrics.platform()`
   parallel-fetches configured queues, sums nominal quota and usage `total`
   fields, and formats strings.
4. **Response:** `PlatformMetricsData` carries `capacity` / `allocated` dicts;
   HTTP caching uses `Cache-Control`, `Date`, `Expires`, and `Last-Modified`
   (see `metrics.http_cache`). Keys in `allocated` match those in `capacity`.
5. **Shutdown:** `runtime.shutdown()` closes the active platform provider, the
   runtime-owned `httpx` client, and the async Redis client when the cache
   backend is Redis.

## Fixtures and local testing

Use `scripts/kind-smoke.sh` and `docs/dev-setup.md` for cluster-backed runs;
unit tests patch `kube_parallel_get_json` and token resolution as in
`tests/test_kueue_platform.py`.
