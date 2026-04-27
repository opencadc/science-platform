# Milestone roadmap process

This file defines how to create and maintain milestone plans for this
repository.

## Purpose

Use milestone plans to ship scoped increments with self-contained implementer
handoffs.

## Milestone lifecycle

Use this lifecycle whenever you add or revise a milestone plan in
`docs/plans/`.

1. Create a new file in `docs/plans/` using the repository naming pattern
  `PLAN_M<id>_<topic>.md` with a monotonic milestone number.
2. Include objective, scope, constraints, and acceptance criteria.
3. Define execution phases with concrete validation steps and any required
  review checkpoints.
4. Reference gate ids from `project-gates.yaml`.
5. If milestone ids or filenames change, update `docs/plans/index.md` and all
  `docs/**/*.md` references in the same change.
6. Update status and outcomes after completion.

## Milestone naming rules

Use these naming rules for every new roadmap stage.

- Milestones are incremental stages and must use `PLAN_M<n>_<topic>.md`.
- The primary delivery sequence (`M1`, `M2`, `M3`, ...) uses increasing numeric
ids with no duplicates.
- If you insert a new stage, renumber all later milestone files and update
`docs/plans/index.md` plus all `docs/**/*.md` references in the same change.
- Use a numbered milestone id for deferred/supporting stages; do not keep
permanent `PLAN_post_*` filenames.
- Closure checklists are a separate plan class and may reuse their parent
milestone id with a `_post_...` suffix, such as
`PLAN_M2_post_review_feedback.md`. These files are not separate primary
delivery milestones.

## Required sections

Each active milestone must include summary, in scope, out of scope,
dependencies, implementation phases, validation plan, risks, operational
controls, and an implementer handoff checklist.

When a milestone introduces or changes runtime modes, environment behavior, or
cluster dependencies, also include:

- the environment model (`dev`, `integration`, `staging`, `production`);
- runtime dependency and version constraints;
- startup validation and shutdown behavior;
- fixture ownership and test layout expectations; and
- milestone review checkpoints for any critical design decisions.

When a milestone describes behavior that is not yet true in the repository, add
a short **repository snapshot** section that separates current code facts from
planned deliverables. This keeps milestone documents honest when reviews find
drift between docs, gates, and implementation.

When roadmap language uses different tokens than runtime settings, document the
mapping explicitly and assign reconciliation to a specific milestone rather
than leaving the mismatch implicit.
