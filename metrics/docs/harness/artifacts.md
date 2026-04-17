# Handoff artifacts

Artifacts preserve the research, plan, implementation, verification, review, and arbitration chain. Canonical fields live in `artifact-schema.yaml`.

## Purpose

Make substantial work auditable without long conversation replay.

## Trigger

Use for feature work, bugs, refactors, process changes, and verification handoffs.

## Inputs

Stage, goal, constraints, evidence, open questions, and next action.

## Required outputs

Schema-compliant stage artifact with concrete evidence.

## Examples

Good: record tests run and gate result. Bad: hand off with only “looks good.”

## Non-goals

Artifacts do not duplicate full diffs or raw logs.

## Stage linkage

Every stage named here maps to a `stage_specific` block in
`artifact-schema.yaml`: `research`, `plan`, `implementation`,
`verification`, `review`, `arbitration`, and `subagent_dispatch`. New
stages must land in the schema first, with a version bump and a matching
entry in `CHANGELOG.md`, before prose references them.

## Required evidence

- Reproducible commands run and their exit status (not prose summaries).
- File references, not pasted file content, per
  `token-efficiency.md#efficiency-rules`.
- Gate decisions anchored to `project-gates.yaml` ids.
- Blocker and remediation notes when a gate was skipped. A skipped gate
  without an explicit blocker is a contract violation.
