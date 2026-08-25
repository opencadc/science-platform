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
  constructs the Kueue and Kubernetes providers, owns their lifecycle and
  per-surface cache resources, and builds `MetricsService`. Providers own
  lazily built kr8s API handles (ADR-0001).
- Active platform metrics come only from the **Kueue** source
  (`providers/kueue.py`): `KueueProvider.read_platform` returns a transport-neutral
  `PlatformObservation`. Routes call `MetricsService.get(subject)` only.
  Configuration rejects inactive or unknown providers.
- User phase-1 metrics come from namespaced Pod LISTs in
  `providers/kubernetes.py`. The provider applies fixed Skaha/CANFAR provenance,
  exact username, and Running-phase selectors, then computes scheduler-effective
  requests.
- Runtime dependencies are defined in `pyproject.toml`.
- Test dependencies are in the `dev` dependency group.

## Layered package map

- `api/v1alpha1/`: versioned HTTP routes.
- `core/`: `Settings`, `MetricsRuntime`, and `create_app`.
- `schemas/`: Pydantic API and internal transfer models (`schemas/metrics.py`).
- `services/`: `MetricsService.get(subject)` plus transport-neutral models/sources.
- `providers/`: `KueueProvider` reads named ClusterQueues and
  `KubernetesProvider` lists Pods only in configured workload namespaces.
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
  starts/stops both providers and validates every configured workload namespace.
- The public API exposes Platform and
  `GET /apis/canfar.net/v1alpha1/metrics/user/{user}` through the shared
  `Metrics` kind. User snapshots use 2/10/15-minute freshness and HMAC-protected
  subject cache keys. The legacy `/api/v1` route is absent.
- Snapshot freshness is exposed only through `Last-Modified`, `Age`, and
  `Cache-Status`; successful and error responses use `Cache-Control: no-store`.

## Update rules

- Keep content implementation-backed.
- Prefer short, direct explanations with concrete paths.
