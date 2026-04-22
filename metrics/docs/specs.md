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
- Current runtime provider-mode contract is `static` or `kueue` until M3
  implementation is complete.
- Startup must fail fast when required source dependencies are unavailable.
- Cache behavior is communicated via HTTP headers (`Cache-Control`, `Date`,
  `Expires`, and `Last-Modified`) for cacheable routes.

## Milestone linkage

- Architecture realignment and provider cleanup: `docs/plans/PLAN_M3_app_structure_and_platform_sources.md`
- Kube-metrics runtime implementation: `docs/plans/PLAN_M4_kube_metrics_mode_platform_release.md`

## Planned target behavior (roadmap)

- After M3 cutover, supported platform sources are Kueue, Prometheus, and
  kube-metrics.
- Static and node provider runtime behavior is removed as part of M3
  implementation.
