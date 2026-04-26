# Architecture reference

This file stores repository-specific architecture facts only. Harness process
policy belongs in `docs/harness/`.

## Ownership

- Record stable boundaries and module responsibilities.
- Document architecture invariants that are verifiable in code.
- Remove claims that are not currently implemented.

## Current state

- Deployment and environment naming contracts for Metrics are summarized in
  `environment-contracts.md` (same directory as this file).
- `src/metrics/` is the Python package root. The composition root is
  `src/metrics/core/factory.py` (`create_app`), with nested settings in
  `src/metrics/core/settings.py` and startup validation in
  `src/metrics/core/startup.py`.
- Platform metrics are composed from **Kueue** (`providers/kueue_platform.py`)
  and **Prometheus** (`providers/prometheus.py`). A **kube-metrics** settings
  subtree exists for M5 but rejects `enabled=true` until that milestone ships.
- Runtime dependencies are defined in `pyproject.toml`.
- Test dependencies are in the `dev` dependency group.

## Layered package map (M3)

- `api/v1/`: versioned HTTP routes.
- `core/`: `Settings`, `create_app`, and startup validation.
- `schemas/`: Pydantic API and internal transfer models (`schemas/metrics.py`).
- `services/`: `PlatformMetricsService` and cache-aware orchestration.
- `providers/`: Kueue and Prometheus adapters plus shared `kube_http` helpers.
- Adjacent modules (`cache.py`, `errors.py`, `http_cache.py`, `quantity.py`,
  `telemetry.py`, `kueue_api.py`, `kueue_spec.py`) remain at the package root
  until a later milestone chooses to fold them into `core/` or `providers/`.

## Architecture invariants

- Runtime contracts are 12-factor and environment-driven.
- Runtime models and API schemas use Pydantic models (no dataclass contracts for
  settings or wire payloads). Small internal service wrappers may still use
  `@dataclass` where they are not part of the public contract.
- Startup validation remains fail-fast for required source dependencies.
- Provider boundaries stay explicit and avoid fallback indirection to removed
  legacy providers.
- Cache keys for externally supplied user and session identifiers preserve the
  exact identifier value through collision-resistant tokens.

## Update rules

- Keep content implementation-backed.
- Prefer short, direct explanations with concrete paths.
