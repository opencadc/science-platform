# Kueue platform metrics (developer guide)

This document explains **why** the Kueue integration is structured the way it
is and **which modules** participate. It complements
[`docs/adr/README.md`](adr/README.md) and operator-facing notes in
[`environment-contracts.md`](environment-contracts.md). For the extension pattern
(config, provider lifecycle), see
[`adr/0001-runtime-architecture.md`](adr/0001-runtime-architecture.md)
and the client standard in [`adr/0001-runtime-architecture.md`](adr/0001-runtime-architecture.md).

## Goals

- **Single Kueue seam:** Platform maps come only from `providers/kueue.py`
  (kr8s reads, startup checks, nominal-quota parsing, and aggregation).
- **Fail fast:** Misconfiguration or a missing API is detected at **startup**
  when the active platform provider runs `startup()` during app lifespan.
- **Honest aggregation:** Platform capacity and allocation are derived from the
  configured ClusterQueue set only. `allocated` reflects admitted usage from
  `status.flavorsUsage.resources[].total`.
- **Stable response contract:** Borrowed/lending response-field expansion is out
  of scope for this delivery; the platform API remains `capacity` and
  `allocated` maps only.

## Responsibility split (M4)

- The **Kueue provider** (`metrics.providers.kueue`) runs startup checks
  against the Kubernetes API and performs platform capacity/allocated
  aggregation. It owns a lazily built kr8s API handle (ADR-0001); endpoint,
  credentials, and CA trust are discovered by kr8s, not configured.
- **`MetricsRuntime`** owns the active provider lifecycle, cache backend, and
  platform service for `sources.platform`.
- **Startup vs request:** validation for required upstreams runs during
  `startup()` in lifespan; the `PlatformMetricsService` path serves cached
  results and maps request-time failures to HTTP/telemetry (without exposing raw
  upstream error strings in response bodies where security review disallows it).

## Module map

| Module | Role |
| --- | --- |
| `metrics.core.runtime` | `MetricsRuntime`: provider construction and lifecycle, cache backend, platform cache keys. |
| `metrics.providers.kueue` | kr8s ClusterQueue reads, startup checks, quantity parsing/formatting (quantiphy, ADR-0001), platform aggregation, and fingerprinting. |
| `metrics.core.factory` | FastAPI `create_app`, lifespan, telemetry hooks. |
| `metrics.services.platform` | TTL cache, telemetry, and error mapping for `/platform`. |

## Request flow

1. **Startup:** Lifespan builds `MetricsRuntime.from_settings`, then `await runtime.start()`.
2. **HTTP GET** `/api/v1/metrics/platform`: route depends on `MetricsRuntime`;
   `runtime.platform_service.get_platform_metrics()` serves the read.
3. **Miss:** Concurrent misses coalesce onto one in-flight load
   (single-flight); the loader → `KueueProvider.platform()` fetches configured
   queues via kr8s with bounded concurrency, sums nominal quota and usage
   `total` fields, and formats strings.
4. **Response:** `PlatformMetricsData` carries `capacity` / `allocated` dicts;
   HTTP caching uses `Cache-Control`, `Date`, `Expires`, and `Last-Modified`
   (see `metrics.http_cache`). Keys in `allocated` match those in `capacity`.
5. **Shutdown:** `runtime.shutdown()` stops the active platform provider
   (which releases its kr8s handle; the kr8s session is process-shared) and
   closes the async Redis client when the cache backend is Redis.

## Fixtures and local testing

Use `scripts/kind-smoke.sh` and `docs/dev-setup.md` for cluster-backed runs;
unit tests inject `FakeKueueApi` (see `tests/fakes.py`) as in
`tests/test_kueue_platform.py`.
