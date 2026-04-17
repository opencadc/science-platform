# Capability routing

Routing chooses the smallest capable model or agent role for each stage. Canonical machine policy lives in `router-policy.yaml`.

## Purpose

Keep expensive reasoning off the hot path while preserving quality.

## Trigger

Use before delegating, escalating, or assigning reviewer roles.

## Inputs

Task stage, ambiguity level, verification state, and context budget.

## Required outputs

Selected route, effort level, escalation reason if any, and compact context set.

## Examples

Good: use verifier route for regression checks. Bad: send a raw repository dump to arbitration.

## Non-goals

Routing does not override explicit user instructions or safety gates.

## Escalation triggers

Stay on `router-policy.yaml.escalation.start_class` until one of the
declared triggers fires. Record which trigger is active in the handoff so
escalation is auditable.

- `ambiguity` — acceptance criteria or scope are unclear after one pass.
- `repeated_retries` — the same stage has retried twice with the same
  failure mode; do not spend a third try before escalating.
- `inconsistent_outputs` — two adjacent runs disagree on structure or
  substance for the same inputs.
- `high_stakes_architecture` — the change touches invariants, adapter
  surfaces, or hook policy.
- `repeated_verification_failure` — a required gate in
  `project-gates.yaml` fails twice for unrelated reasons.

## Retrieval hierarchy link

Before widening context for any route, apply the retrieval hierarchy in
`token-efficiency.md#retrieval-hierarchy` and prefer the machine policy in
`router-policy.yaml` over rereading prose. Forbid raw full-repo context on
the expensive route per
`router-policy.yaml.affordances.context.forbid_raw_repo_to_expensive_models`.
