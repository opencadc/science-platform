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
   `PLAN_M<id>_<topic>.md`. Use follow-on milestone ids such as `M2b` when you
   need a narrow extension of an existing milestone rather than a new major
   phase.
2. Include objective, scope, constraints, and acceptance criteria.
3. Define execution phases with concrete validation steps and any required
   review checkpoints.
4. Reference gate ids from `project-gates.yaml`.
5. Update status and outcomes after completion.

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
