# AGENTS routing index

This file is routing-only. Keep detailed policy in `docs/harness/`.

## Canonical precedence

Follow `docs/harness/index.md` for precedence and conflict handling.

## Required reading

Start with `docs/harness/index.md`. It routes to every canonical harness
module by concern and keeps the module map in one place so this file stays
a thin pointer. All canonical modules live under `docs/harness/`:

- `docs/harness/index.md`
- `docs/harness/loop.md`
- `docs/harness/rules-core.md`
- `docs/harness/routing.md`
- `docs/harness/hooks.md`
- `docs/harness/verification.md`
- `docs/harness/artifacts.md`
- `docs/harness/subagents.md`
- `docs/harness/handoff-templates.md`
- `docs/harness/review-arbitration.md`
- `docs/harness/token-efficiency.md`
- `docs/harness/doc-gardening.md`
- `docs/harness/metrics.md`
- `docs/harness/learnings.md`

## Machine-readable policy

- `docs/harness/router-policy.yaml`
- `docs/harness/artifact-schema.yaml`
- `docs/harness/hook-policy.yaml`
- `docs/harness/metrics-schema.yaml`
- `project-gates.yaml`

## Repository-specific docs

Use these for project facts and behavior only.

- `docs/architecture.md`
- `docs/design.md`
- `docs/specs.md`
- `docs/learnings.md`
- `docs/plans/index.md`
- `docs/plans/milestone-process.md`

## Adapter locations

- Canonical reviewer personas: `docs/harness/personas/`
- Codex: `.codex/hooks.json`, `.codex/agents/`
- Cursor: `.cursor/hooks.json`, `.cursor/rules/`, `.cursor/agents/`
- Claude: `.claude/hooks.json`, `.claude/agents/`

All adapters invoke the shared bridge `python -m harness.hooks.bridge <event>
--tool <tool>` so canonical policy lives in `docs/harness/hook-policy.yaml`.

## Ownership boundaries

- Reusable process policy: `docs/harness/`
- Harness runtime code: `.harness/harness/`
- Project implementation facts: `docs/`
- Delivery and milestones: `docs/plans/`

## Learned User Preferences

- Write git commits using Conventional Commits (`type(scope): subject`, with optional body and footer).
- When the user requests a staged-only commit (for example from a diff-tab flow), treat their staged file list as authoritative: commit only what is already staged and do not stage additional files.
- Before local cluster-backed or kubectl-driven checks, confirm the intended
  Kubernetes context is selected (for example
  `kubectl config use-context kind-metrics`) and that kind, Helm, and kubectl
  are installed.
- For substantial milestone or feature work, run multiple reviewer personas or aspect-focused reviews, synthesize a consensus, incorporate the feedback, and ask for human arbitration if reviewer disagreement deadlocks; when the work materially changes behavior or structure, update `docs/architecture.md`, `docs/design.md`, `docs/specs.md`, and `docs/learnings.md` so those documents match the delivered system.
- When implementing approved restructuring plans, relocate or consolidate modules as the plan describes instead of relying on thin re-export shims or extra boilerplate that keeps obsolete import surfaces alive.
- For local kind workflows, use the existing cluster when possible (typically
  `metrics`); do not create extra disposable clusters unless the user asks for
  them.
- When executing an attached implementation plan, carry out the steps but do not edit the plan file itself unless the user explicitly requests plan updates.
- For user-facing HTTP APIs, avoid internal implementation details in JSON bodies; prefer standard HTTP caching headers (`Cache-Control`, `Expires`, `Date`, `Last-Modified`, and related headers) over embedding cache metadata in JSON for shared cacheable resources.
- In GitHub Actions workflows for this repository, do not pin `step-security/harden-runner` to a commit SHA; use a floating major tag (for example `step-security/harden-runner@v2`) unless the user specifies otherwise.
- For platform metrics responses, keep `capacity` and `allocated` in consistent, comparable units (the user treats mixed or mismatched unit presentation in that API as an unacceptable defect).
- After substantive changes to Metrics CI workflows or `metrics/scripts` automation, run `pre-commit run --all-files` at the science-platform repository root to verify hooks still pass.
- For `metrics` Python, use Google-style docstrings on modules, classes, and functions; avoid single-letter names for configuration or domain parameters; Ruff pydocstyle (`D`, Google convention) is configured in `metrics/pyproject.toml` and applies to `src` (tests may ignore `D` per Ruff per-file config).

## Learned Workspace Facts

- Product and implementation conventions belong in `docs/learnings.md`; harness-wide notes belong in `docs/harness/learnings.md`.
- The Metrics API Helm chart lives under `metrics/helm/metrics-api`.
- Local Kubernetes integration and CI smoke: `metrics/scripts/kind-smoke.sh`
  (Kueue, `scripts/test-setup.yaml`, Docker build/load, integration tests) and
  `metrics/scripts/kind-values.yaml`. Shared bash helpers:
  `metrics/scripts/lib-kind-smoke.sh` (port-forward state parsing). A local
  success may leave a background port-forward; stop with
  `metrics/scripts/kind-smoke-teardown.sh` (optional `--all` for Kubernetes
  teardown).
  With `pullPolicy: Never`, use unique image tags to avoid stale layers. Short
  `METRICS_CACHE_TTL_SECONDS` in `kind-values.yaml` keeps integration snapshots
  fresh. If port-forward fails, change `PORT_FORWARD_PORT`. If Kueue
  allocation is wrong, try Redis `FLUSHDB` or wait for cache TTL.
- In CI (`KIND_SMOKE_CI=1`), the smoke script assumes the one-node kind
  cluster already exists and focuses on Kueue install, fixture apply, image
  load, chart deploy, and integration checks.
- In **`dev`**, the supported workflow is Kubernetes-first: use kind, Helm,
  and `kubectl` to deploy Metrics and Redis into the cluster. Docker
  Compose is not part of the active environment contract. See
  `docs/environment-contracts.md` and `README.md`.
- Canonical `METRICS_ENVIRONMENT` values are `dev`, `integration`, `staging`, and `production`; legacy `int` and `prod` inputs are still accepted and normalized during settings validation.
- Kueue manifests and clients should target the **v1beta2** API; **v1beta1** is deprecated.
- End-to-end local dev with Kueue: `docs/dev-setup.md` and
  `bash scripts/kind-smoke.sh` (or apply `scripts/test-setup.yaml` and run
  Helm deploy steps by hand). `METRICS_CLUSTER_NAME` defaults to
  `kind-metrics` in `kind-values.yaml`.
- `METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES` in nested env form must be a JSON array string (for example `'[\"cq-proton\",\"cq-neutron\"]'`), not a comma-separated plain string; pydantic-settings parses that field with JSON and will fail startup otherwise.
- Release-please (science-platform root `release-please-config.json`) uses component `metrics`, so git tags are `metrics-v*`. The `cd.metrics.release.build.yml` workflow strips the `metrics-` prefix for the OCI image tag so published images use `v*` (for example `v0.1.2`).
- Root pre-commit `check-yaml` skips `metrics/helm/` and `metrics/scripts/` (Helm charts, template-rendered YAML, and cluster setup manifests are not validated as plain YAML).
- Java copy-paste detection via the removed `cpd` pre-commit hook is not used; Skaha Java checks run through `./gradlew clean check`.
- Repository-wide PR commit-check subject line length is configured with commit-check environment variables (for example `CCHK_SUBJECT_MAX_LENGTH`) in `.github/workflows/ci.commit.check.yml` at the science-platform repository root; the upstream default is 80 characters.
