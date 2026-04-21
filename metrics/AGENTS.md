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
- Before local cluster-backed or kubectl-driven checks (for example Minikube integration), confirm the intended Kubernetes context is selected (such as `kubectl config use-context minikube` when using the default Minikube profile) and that Minikube, Helm, and kubectl are installed.
- For substantial milestone or feature work, run multiple reviewer personas or aspect-focused reviews, synthesize a consensus, incorporate the feedback, and ask for human arbitration if reviewer disagreement deadlocks.

## Learned Workspace Facts

- Product and implementation conventions belong in `docs/learnings.md`; harness-wide notes belong in `docs/harness/learnings.md`.
- The Metrics API Helm chart lives under `metrics/helm/metrics-api`.
- Local Kubernetes integration and CI smoke tests use Minikube (Kind-based scripts were removed); use `metrics/scripts/run-minikube-integration.sh` and `metrics/scripts/minikube-values.yaml`, and enable the metrics-server addon (CI does this; default in the local script) for work that depends on cluster resource metrics.
- In **`dev`**, `metrics/compose.yaml` runs the Metrics API and Redis; cluster-backed data sources and Helm smoke validation use Minikube (or another cluster) separately. See `docs/environment-contracts.md` and `README.md`.
- Canonical `METRICS_ENVIRONMENT` values are `dev`, `integration`, `staging`, and `production`; legacy `int` and `prod` inputs are still accepted and normalized during settings validation.
- Root pre-commit `check-yaml` excludes `metrics/helm/*/templates/` because Helm templates embed Go syntax and are not plain YAML.
- Java copy-paste detection via the removed `cpd` pre-commit hook is not used; Skaha Java checks run through `./gradlew clean check`.
