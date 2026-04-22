# Kueue platform metrics (developer guide)

This document explains **why** the Kueue integration is structured the way it
is and **which modules** participate. It complements milestone plans under
`docs/plans/` and operator-facing notes in `docs/environment-contracts.md`.

## Goals (M2 + M3)

- **Composed platform sources:** Platform maps always come from
  `KueuePlatformEngine` (Kueue API reads). There is no static or node fallback.
- **Fail fast:** Misconfiguration or a missing API is detected at **startup**
  (`metrics.core.startup.validate_application_startup`), including Prometheus URL
  requirements, so the deployment never serves contradictory metrics.
- **Honest aggregation:** Per-queue nominal quota is summed, cohort nominal
  quota is added **once**, and ``allocated`` reflects admitted usage from
  ``status.flavorsUsage`` (see milestone outcomes for the semantic choice).

## Module map

| Module | Role |
| --- | --- |
| `metrics.kueue_api` | Build Kubernetes URLs for `ClusterQueue` list/get and `Cohort` get. |
| `metrics.providers.kube_http` | Shared TLS/token handling and parallel `httpx` GET helper. |
| `metrics.kueue_spec` | Parse `spec.resourceGroups` nominal quotas into numeric maps. |
| `metrics.core.startup` | Lifespan validation before traffic. |
| `metrics.providers.kueue_platform` | Platform map aggregation for the API contract. |
| `metrics.providers.kueue` | Narrow CPU/memory `CapacityReading` for user/session scopes. |
| `metrics.core.factory` | Wire settings → engine + fingerprint + service. |
| `metrics.services.platform_metrics` | TTL cache, telemetry, and error mapping for `/platform`. |

## Request flow

1. **Startup:** `create_app` registers a lifespan that awaits
   `validate_application_startup` (Kueue plus Prometheus checks).
2. **HTTP GET** `/api/v1/metrics/platform`:
   `PlatformMetricsService.get_platform_metrics` checks Redis/memory cache.
3. **Miss:** `KueuePlatformEngine.collect` parallel-fetches all configured
   queues plus the cohort document, sums nominal and usage fields, formats
   strings, and returns `PlatformResourceMaps`.
4. **Response:** `PlatformMetricsData` carries `capacity` / `allocated` dicts;
   JSON metadata includes `created` (snapshot time). HTTP caching uses
   `Cache-Control`, `Date`, `Expires`, and `Last-Modified` (see `metrics.http_cache`).
   Keys in `allocated` match those in `capacity` (zeros are filled when Kueue has no
   `flavorsUsage` yet).

## Fixtures and local testing

Cluster-scoped manifests live under `tests/fixtures/kueue/`. The helper
`scripts/install-kueue-minikube.sh` installs Kueue with Helm; apply fixtures with
`kubectl apply -f tests/fixtures/kueue/`. The Minikube script
`scripts/run-minikube-integration.sh` runs that path end-to-end and deploys Helm
values from `scripts/minikube-values.yaml` so CI and developers exercise the
same contract. For a **manual** dev loop (port-forward, no teardown), see
`docs/dev-kueue-cluster-setup.md`.

## Related reading

- `docs/plans/PLAN_M2_platform_metrics_initial_release.md` — milestone scope.
- `docs/plans/PLAN_M2_outcomes.md` — closure evidence and review notes.
- `docs/plans/PLAN_M3_app_structure_and_platform_sources.md` — package layout.
- `docs/environment-contracts.md` — environment variables and alias precedence.
