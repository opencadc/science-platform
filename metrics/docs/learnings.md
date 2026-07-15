# Project learnings

This file stores product and repository implementation lessons only. Durable
decisions are also recorded under `docs/adr/`.

## Ownership

- Capture lessons discovered during product implementation and verification.
- Keep entries concise, actionable, and evidence-based.
- Remove duplicates once guidance is codified elsewhere.

## Entry format

- Date:
- Context:
- Lesson:
- Evidence:
- Action taken:

## Current entries

- Date: April 24, 2026
- Context: P1 review fixes for Kueue allocated aggregation and (at the time)
  user/session cache isolation.
- Lesson: Kueue `status.flavorsUsage.resources[].total` already includes
  borrowed quota, so adding `borrowed` separately inflates allocated metrics.
  If user/session scope returns, cache keys for external identifiers should use
  collision-resistant tokens rather than lossy string replacement.
- Evidence: `src/metrics/providers/kueue.py`,
  `src/metrics/services/platform_metrics.py`, `tests/test_kueue_platform.py`,
  and `tests/test_service.py`.
- Action taken: Allocated aggregation uses `total` only. M4 removed user/session
  routes; any future reintroduction should revisit cache-key rules with fresh
  specs.

- Date: April 23, 2026
- Context: M11 local and CI smoke workflow simplification follow-up.
- Lesson: A one-node kind cluster plus Helm and direct Docker build/load
  provides a smaller and easier smoke path than the previous Minikube plus
  Skaffold flow, while keeping the Kueue fixture and chart deployment contracts
  unchanged.
- Evidence: `scripts/kind-smoke.sh`, `scripts/kind-smoke-teardown.sh`,
  `scripts/kind-values.yaml`, `docs/dev-setup.md`, and
  `.github/workflows/ci.metrics.yml`.
- Action taken: Switched the active local/CI smoke workflow to kind and removed
  Minikube/Skaffold smoke-path artifacts.

- Date: April 22, 2026 (M3; superseded by M4 for env surface)
- Context: M3 nested `pydantic-settings` with ad hoc legacy env folding.
- Lesson: Complex `BaseSettings` subclasses need predictable merge order and
  validation timing; M4 moved to a stable `Settings` tree (`providers`,
  `sources`, `cache`) and dropped one-off `METRICS_KUEUE_*` / `KUEUE_METRICS_*`
  style aliases. List-like fields in nested env must be JSON (for example
  `cluster_queues` as a JSON array string) so parsing stays explicit.
- Evidence: `src/metrics/core/settings.py`, `src/metrics/core/yaml_config.py`
  (YAML shape and `metrics:` contract), and `docs/environment-contracts.md`.
- Action taken: M4 uses nested `METRICS_` + `__` only for provider inputs;
  stricter JSON for lists and for `cache.scope_ttl_seconds` via env.

- Date: 2026-04-17
- Context: Git history and release tooling.
- Lesson: Always write commit messages using the Conventional Commits
  standard (`type(scope): subject` with optional body and footer). Types
  include `feat`, `fix`, `docs`, `style`, `refactor`, `perf`, `test`,
  `build`, `ci`, `chore`, and `revert`. This keeps history readable and
  works with changelog generators and release automation.
- Evidence: https://www.conventionalcommits.org/
- Action taken: Recorded here as a standing project convention.

- Date: 2026-04-17
- Context: M1 delivery foundation (CI pathways, Helm, release-please, Minikube smoke).
- Lesson: Path-based workflow filters (`paths` / `paths-ignore`) and tag-prefix guards (`metrics-v*`) are the primary levers for keeping Skaha and Metrics pipelines independent in a shared monorepo.
- Evidence: `.github/workflows/ci.*.yml`, `cd.platform.release.yml`, `release-please-config.json`.
- Action taken: Documented in `metrics/README.md` and ADR-0001.

- Date: April 26, 2026
- Context: M4 provider runtime — single `MetricsRuntime` composition root and
  inactive Prometheus/kube providers.
- Lesson: Inactive provider packages should stay out of the HTTP client graph so
  startup and dependency surfaces match what operators actually use; optional
  HTTP/2 should stay off by default to avoid an implicit `h2` install.
- Evidence: `src/metrics/core/runtime.py`, `src/metrics/core/provider_registry.py`,
  and `docs/adr/0005-metrics-runtime-composition-root.md`.
- Action taken: Documented in `docs/architecture.md` and ADR-0005.

- Date: April 22, 2026 (superseded June 2026)
- Context: M3 documentation realignment and roadmap cleanup.
- Lesson: Milestone delivery stayed readable when stages were strictly
  incremental; inserting a stage required immediate renumbering of later files
  and cross-links.
- Evidence: `docs/adr/README.md` and `docs/design.md`.
- Action taken: Milestone plan files were distilled into ADRs and removed in
  June 2026; ADRs and code are the authoritative delivery record.

- Date: April 22, 2026
- Context: Environment contract review after roadmap realignment.
- Lesson: Metrics service development must be Kubernetes-first; Docker Compose
  introduces drift from real runtime dependencies and is no longer a supported
  contract.
- Evidence: `docs/environment-contracts.md`, `README.md`,
  `docs/dev-setup.md`, and `AGENTS.md`.
- Action taken: Updated documentation to require a Kubernetes-first local
  cluster (kind) with Helm and `kubectl` in `dev` and clarified higher-environment
  cluster ownership.

- Date: June 2026
- Context: Milestone plans distilled into ADRs and CONTEXT updates.
- Lesson: When plan text conflicts with ADRs (for example M2 cohort/borrowed
  aggregation), treat plans as historical; ADRs and code are authoritative.
- Evidence: `metrics/docs/adr/README.md`.
- Action taken: Added Metrics ADRs 0007–0021 and system ADRs 0003–0004; expanded
  `metrics/CONTEXT.md` glossary; removed superseded `docs/plans/` tree.
