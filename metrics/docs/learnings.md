# Project learnings

This file stores product and repository implementation lessons only. Harness
process lessons belong in `docs/harness/learnings.md`.

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
- Context: P1 review fixes for Kueue allocation and user/session metrics cache
  isolation.
- Lesson: Kueue `status.flavorsUsage.resources[].total` already includes
  borrowed quota, so adding `borrowed` separately inflates allocated metrics.
  Cache keys for external identifiers must use collision-resistant tokens rather
  than lossy string replacement.
- Evidence: `src/metrics/providers/kueue_platform.py`,
  `src/metrics/services/platform_metrics.py`, `tests/test_kueue_platform.py`,
  and `tests/test_service.py`.
- Action taken: Allocated aggregation now uses `total` only, and user/session
  cache tokens use SHA-256 over the exact identifier value.

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

- Date: April 22, 2026
- Context: M3 nested `pydantic-settings` with legacy flat env merge.
- Lesson: A single `model_validator(mode="after")` that returns
  `self.model_copy(...)` is not reliable under `BaseSettings.__init__`; use
  `mode="before"` on a plain dict (including `model_dump()` for nested
  `BaseModel` fragments) when folding operator aliases from `os.environ`.
- Evidence: `src/metrics/core/settings.py` (`_merge_legacy_environment`,
  `_as_dict`), pytest warnings before the fix.
- Action taken: Documented here; implementation uses `before` merge only.

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
- Action taken: Documented in `docs/plans/PLAN_M1_outcomes.md` and `metrics/README.md`.

- Date: April 22, 2026
- Context: M3 documentation realignment and roadmap cleanup.
- Lesson: Milestones stay readable and executable when roadmap stages are
  strictly incremental (`PLAN_M<n>_<topic>`), and inserting a stage requires
  immediate renumbering of later plan files and references.
- Evidence: `docs/plans/milestone-process.md`, `docs/plans/index.md`, and
  plans renamed to M3-M10 during this update.
- Action taken: Added milestone naming rules and updated all roadmap filenames
  and cross-links.

- Date: April 22, 2026
- Context: Environment contract review after roadmap realignment.
- Lesson: Metrics service development must be Kubernetes-first; Docker Compose
  introduces drift from real runtime dependencies and is no longer a supported
  contract.
- Evidence: `docs/environment-contracts.md`, `README.md`,
  `docs/dev-setup.md`, and `AGENTS.md`.
- Action taken: Updated documentation to require Minikube + Helm + kubectl in
  `dev` and clarified higher-environment cluster ownership.
