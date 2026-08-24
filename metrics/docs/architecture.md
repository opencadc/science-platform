# Architecture reference

This file stores repository-specific architecture facts only.

## Ownership

- Record stable boundaries and module responsibilities.
- Document architecture invariants that are verifiable in code.
- Remove claims that are not currently implemented.

**Contributors:** when adding a provider or scope, follow
[`docs/adr/0001-runtime-architecture.md`](adr/0001-runtime-architecture.md).

## Current state

- Deployment and environment naming contracts for Metrics are summarized in
  `environment-contracts.md` (same directory as this file).
- `src/metrics/` is the Python package root. `create_app` in
  `src/metrics/core/factory.py` registers FastAPI lifespan hooks; typed settings
  live in `src/metrics/core/settings.py` (environment-only, `METRICS_*`).
  `src/metrics/core/runtime.py` (`MetricsRuntime`) is the composition root: it
  constructs the Kueue provider, owns provider lifecycle and cache resources,
  and builds the platform cache key and `MetricsService`. The provider owns a lazily built kr8s API handle
  (ADR-0001).
- Active platform metrics come only from the **Kueue** source
  (`providers/kueue.py`): `KueueProvider.read_platform` returns a transport-neutral
  `PlatformObservation`. Routes call `MetricsService.get(subject)` only.
  Configuration rejects inactive or unknown providers.
- Runtime dependencies are defined in `pyproject.toml`.
- Test dependencies are in the `dev` dependency group.

## Layered package map

- `api/v1/`: versioned HTTP routes.
- `core/`: `Settings`, `MetricsRuntime`, and `create_app`.
- `schemas/`: Pydantic API and internal transfer models (`schemas/metrics.py`).
- `services/`: `MetricsService.get(subject)` plus transport-neutral models/sources.
- `providers/`: `KueueProvider` reads ClusterQueues through kr8s named
  `call_api` GETs (get-only RBAC, ADR-0001).
- `providers/kueue.py` includes nominal-quota parsing from ``spec.resourceGroups``
  alongside kr8s access and aggregation. Quantities parse via quantiphy
  (ADR-0001); malformed values fail the provider read.

## Architecture invariants

- Runtime contracts are 12-factor and environment-driven.
- Runtime models and API schemas use Pydantic models (no dataclass contracts for
  settings or wire payloads). Small internal service wrappers may still use
  `@dataclass` where they are not part of the public contract.
- Startup validation remains fail-fast for required source dependencies.
- Provider boundaries stay explicit and avoid fallback indirection to removed
  legacy providers.
- Provider construction is synchronous and network-free. `MetricsRuntime`
  starts/stops one Kueue provider, and `KueueProvider.shutdown()` releases its
  kr8s handle (the kr8s session is process-shared).
- The public API exposes only `GET /api/v1/metrics/platform` and `GET /healthz`
  in M4; per-user and session routes are removed until full provider contracts
  return.

## Update rules

- Keep content implementation-backed.
- Prefer short, direct explanations with concrete paths.
