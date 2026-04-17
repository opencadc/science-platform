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
