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

- Date: July 31, 2026
- Context: kind-smoke crashloop after the kr8s migration (PR #1161).
- Lesson: Validate the exact access pattern production RBAC allows, not just
  the library: kr8s resolves named objects with a LIST plus
  `metadata.name` field selector, so `api.get(cls, name)` needs the `list`
  verb, which the get-only ClusterQueue RBAC denies. The staging validation
  passed only because that service account also had `list`.
- Evidence: `src/metrics/providers/kueue.py::fetch_cluster_queue_docs`,
  `scripts/validate-kr8s-kueue.py` (named-GET check), kr8s `_api.py`
  `_async_get_single`, and the metrics-kind-smoke failure on PR #1161.
- Action taken: Named reads use `api.call_api` GETs
  (`.../clusterqueues/{name}`); ADR-0001 records the constraint and the
  validation workflow now exercises the get-only path explicitly.

- Date: July 31, 2026
- Context: Kubernetes client selection for the Kueue provider (and future
  scopes).
- Lesson: Validate a client library in the target cluster before adopting it:
  a throwaway in-cluster workflow (`scripts/validate-kr8s-kueue.py`) proved
  kr8s handles service-account auth and the self-signed cluster CA with zero
  configuration, which desk research alone could not settle.
- Evidence: `scripts/validate-kr8s-kueue.py`, `src/metrics/providers/kueue.py`,
  and `docs/adr/0001-runtime-architecture.md` (client survey summarized there;
  full research note removed, in git history).
- Action taken: Migrated the provider to kr8s, deleted the hand-rolled
  token/TLS/URL plumbing and its settings, and recorded kr8s as the client of
  choice in ADR-0001.


- Date: July 31, 2026
- Context: Provider/runtime ownership and application lifecycle convergence.
- Lesson: One owner per live resource removes bundle types and special test
  lifespans without requiring a generic plugin framework.
- Evidence: `src/metrics/core/registry.py`,
  `src/metrics/core/runtime.py`, `src/metrics/providers/kueue.py`,
  `src/metrics/core/factory.py`, and ADR-0001.
- Action taken: The registry returns one Kueue provider owning its Kubernetes
  access; `MetricsRuntime` owns provider/cache lifecycle, and all applications
  use one lifespan.

- Date: July 31, 2026
- Context: Platform provider capability simplification.
- Lesson: Capability metadata can drift from behavior; one provider method is
  a more reliable contract than a scope enum plus an intermediate delegate.
- Evidence: `src/metrics/providers/base.py`,
  `src/metrics/core/registry.py`, `src/metrics/providers/kueue.py`,
  and `tests/test_registry.py`.
- Action taken: `KueueProvider` now implements `PlatformMetrics` directly, and
  the binder rejects selected providers without that capability.

- Date: July 31, 2026 (superseded same day by ADR-0001)
- Context: Kueue platform quantity correctness.
- Lesson: Permissive fallback turns corrupt quantities into false zeros, and
  storage overflow must be checked in Kubernetes base units before Gi
  presentation. Bit-exact `Decimal` arithmetic, however, was more numeric code
  than the 6-decimal API contract needed.
- Evidence: `src/metrics/providers/kueue.py` and `tests/test_kueue_platform.py`.
- Action taken: Quantities parse via quantiphy with fail-closed guards
  (ADR-0001); the bespoke `metrics.quantity` module was deleted.

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
- Evidence: `scripts/kind-smoke.sh`, `scripts/kind-values.yaml`,
  `src/metrics/dev/cli.py`, `docs/dev-setup.md`, and
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
- Context: M4 provider runtime — single `MetricsRuntime` composition root.
- Lesson: Inactive providers should stay out of configuration and the HTTP
  client graph so startup and dependency surfaces match what operators actually
  use; upstream clients should stay on HTTP/1.1 unless an HTTP/2 requirement and
  dependency are deliberately introduced.
- Evidence: `src/metrics/core/runtime.py`, `src/metrics/core/registry.py`,
  and `docs/adr/0001-runtime-architecture.md`.
- Action taken: Documented in `docs/architecture.md` and ADR-0001.

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
