# Specifications reference

This file stores repository-specific behavioral specifications.

## Ownership

- Define expected behavior as testable outcomes.
- Keep each spec tied to observable API or module behavior.
- Defer delivery sequencing details to `docs/plans/`.

## Update rules

- Ensure every factual claim is verifiable from code.
- Use concrete examples and acceptance checks.

## Service behavior specifications

- The API exposes platform, user, and session routes under `/api/v1/metrics`
  with health at `/healthz`.
- Runtime configuration is environment-driven through `METRICS_*` settings and
  validated through Pydantic models.
- Runtime configuration uses nested platform and user settings; legacy flat
  `METRICS_*` keys are merged when nested values are unset (see
  `environment-contracts.md`).
- Startup must fail fast when required source dependencies are unavailable.
- Cache behavior is communicated via HTTP headers (`Cache-Control`, `Date`,
  `Expires`, and `Last-Modified`) for cacheable routes.
- For `GET /api/v1/metrics/platform`, each key present in `data.capacity` is
  also present in `data.allocated`, and the **same resource name must use the
  same unit in both maps** (CPU as decimal core counts, memory as `Gi` binary
  quantities, other resources with the same formatting rules in both). Callers
  can compare the two without converting between millicores and cores.

## Milestone linkage

- Architecture realignment and provider cleanup: `docs/plans/PLAN_M3_app_structure_and_platform_sources.md`
- Kube-metrics runtime implementation: `docs/plans/PLAN_M4_kube_metrics_mode_platform_release.md`

## Planned target behavior (roadmap)

- Kube-metrics becomes an active source once M4 implements runtime depth behind
  `platform.kube_metrics`.
