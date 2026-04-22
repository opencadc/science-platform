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
- `src/metrics/` is the Python package root and current composition root lives
  in `src/metrics/app.py`.
- Current provider wiring supports Kueue plus Prometheus usage, and still
  carries legacy static-mode paths pending M3 implementation.
- Runtime dependencies are defined in `pyproject.toml`.
- Test dependencies are in the `dev` dependency group.

## Roadmap architecture direction (M3 onward)

M3 defines the target package boundaries for maintainability and lower
complexity. The codebase moves from a flat package to layered ownership:

- `api/`: versioned route modules and API composition.
- `core/`: settings, app factory, startup checks, and runtime wiring.
- `schemas/`: Pydantic request/response and internal transfer models.
- `services/`: orchestration and cache-aware computation.
- `providers/`: adapters for Kueue, Prometheus, and kube-metrics.

The architecture target removes static and node providers and treats platform
metrics as source-composed behavior from supported providers only.

## Architecture invariants

- Runtime contracts are 12-factor and environment-driven.
- Runtime models and schemas use Pydantic models (no dataclass contracts).
- Startup validation remains fail-fast for required source dependencies.
- Provider boundaries stay explicit and avoid fallback indirection to removed
  legacy providers.

## Update rules

- Keep content implementation-backed.
- Prefer short, direct explanations with concrete paths.
