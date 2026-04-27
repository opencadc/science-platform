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

- The API exposes `GET /api/v1/metrics/platform` and `GET /healthz` (M4).
- Runtime configuration is environment-driven through `METRICS_*` settings and
  validated through Pydantic `Settings` (`providers`, `sources`, `cache`)
  against optional YAML; see `environment-contracts.md`.
- Startup must fail fast when required source dependencies are unavailable
  for the active platform provider (Kueue in M4). Inactive config-only providers
  do not trigger upstream HTTP at startup.
- Cache behavior is communicated via HTTP headers (`Cache-Control`, `Date`,
  `Expires`, and `Last-Modified`) for platform responses. Per-scope TTLs are
  typed in `CacheConfig` (`cache.scope_ttl_seconds`); the platform scope can
  override the default TTL.
- For `GET /api/v1/metrics/platform`, each key present in `data.capacity` is
  also present in `data.allocated`, and the **same resource name must use the
  same unit in both maps** (CPU as decimal core counts, memory as `Gi` binary
  quantities, other resources with the same formatting rules in both). Callers
  can compare the two without converting between millicores and cores.
- Platform `data.allocated` is summed from
  `status.flavorsUsage.resources[].total`; do not add `borrowed` separately
  because Kueue total already includes borrowed quota.

## Milestone linkage

- Architecture realignment and provider cleanup: `docs/plans/PLAN_M3_app_structure_and_platform_sources.md`
- Provider runtime architecture: `docs/plans/PLAN_M4_provider_runtime_architecture.md`
- Interactive quota release: `docs/plans/PLAN_M5_interactive_quota_release.md`

## Planned target behavior (roadmap)

- Interactive quota becomes an active source once M5 implements runtime depth
  behind the configured `kube` provider and `sources.quotas.interactive`.
