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

- Date: 2026-04-17
- Context: M1 delivery foundation (CI pathways, Helm, release-please, Minikube smoke).
- Lesson: Path-based workflow filters (`paths` / `paths-ignore`) and tag-prefix guards (`metric-v*`) are the primary levers for keeping Skaha and Metrics pipelines independent in a shared monorepo.
- Evidence: `.github/workflows/ci.*.yml`, `cd.skaha.release.yml`, `release-please-config.json`.
- Action taken: Documented in `docs/plans/PLAN_M1_outcomes.md` and `metrics/README.md`.
