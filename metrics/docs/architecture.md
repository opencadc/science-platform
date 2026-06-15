# Architecture reference

This file stores repository-specific architecture facts only.

## Ownership

- Record stable boundaries and module responsibilities.
- Document architecture invariants that are verifiable in code.
- Remove claims that are not currently implemented.

**Contributors:** when adding a provider or scope, follow ADR-0011 and
[`docs/adr/0005-metrics-runtime-composition-root.md`](adr/0005-metrics-runtime-composition-root.md).

## Current state

- Deployment and environment naming contracts for Metrics are summarized in
  `environment-contracts.md` (same directory as this file).
- `src/metrics/` is the Python package root. `create_app` in
  `src/metrics/core/factory.py` registers FastAPI lifespan hooks; typed settings
  live in `src/metrics/core/settings.py`, optional YAML under
  `core/yaml_config.py` (file `/etc/canfar/metrics/config.yaml` by default).
  `src/metrics/core/runtime.py` (`MetricsRuntime`) is the composition root: it
  selects the active platform source via `core/provider_registry.py`, builds
  long-lived `httpx` clients, cache backends, platform cache keys, and the
  `PlatformMetricsService` loader.
- Active platform metrics come only from the **Kueue** source
  (`providers/kueue.py`): one module owns URLs, startup checks, and platform
  aggregation. **Prometheus** and **kube** provider packages exist for
  configuration and M5+ scopes; M4 does not open unused upstream HTTP clients.
- Runtime dependencies are defined in `pyproject.toml`.
- Test dependencies are in the `dev` dependency group.

## Layered package map

- `api/v1/`: versioned HTTP routes.
- `core/`: `Settings`, `MetricsRuntime`, `provider_registry`, `create_app`, and
  YAML/env precedence.
- `schemas/`: Pydantic API and internal transfer models (`schemas/metrics.py`).
- `services/`: `PlatformMetricsService` and cache-aware orchestration.
- `providers/`: `kueue`, `prometheus`, `kube`, `base` (scope protocol), and
  `kube_http` (Kubernetes GETs with an injected `httpx.AsyncClient`).
- `providers/kueue.py` includes nominal-quota parsing from ``spec.resourceGroups``
  alongside HTTP and aggregation; `cache.py` and `quantity.py` are shared
  supporting modules.

## Architecture invariants

- Runtime contracts are 12-factor and environment-driven.
- Runtime models and API schemas use Pydantic models (no dataclass contracts for
  settings or wire payloads). Small internal service wrappers may still use
  `@dataclass` where they are not part of the public contract.
- Startup validation remains fail-fast for required source dependencies.
- Provider boundaries stay explicit and avoid fallback indirection to removed
  legacy providers.
- The public API exposes only `GET /api/v1/metrics/platform` and `GET /healthz`
  in M4; per-user and session routes are removed until full provider contracts
  return.

## Update rules

- Keep content implementation-backed.
- Prefer short, direct explanations with concrete paths.
