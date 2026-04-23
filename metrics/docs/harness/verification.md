# Verification gates

Verification gates define what evidence is required before handoff. The harness defines gate shape; `project-gates.yaml` defines repository commands.

## Purpose

Separate reusable gate semantics from repo-specific validation commands.

## Trigger

Use before final handoff and when milestone plans reference validation.

## Inputs

Gate id, command, expected evidence, failure handling, and required flag.

## Required outputs

Gate outcome with command, result, evidence, and blocker if skipped.

## Examples

Good: run the gate command from `project-gates.yaml`. Bad: invent a different coverage threshold in a handoff.

## Non-goals

This module does not hardcode product test commands.

## Gate contract

Gates are declared in `project-gates.yaml`. Every gate must carry:

- `id` — stable, unique identifier used in handoffs and review logs.
- `command` — fully reproducible invocation; no tool-specific flags.
- `evidence` — the exact artifacts expected when the command succeeds.
- `required` — whether the gate blocks handoff.
- `failure_handling` — what to do when the gate fails, including when it
  is acceptable to document a blocker rather than proceed.

Coverage floor is declared as a top-level field in `project-gates.yaml`
(`min_coverage`) so the harness runtime stays repository-agnostic.

## Retrieval hierarchy link

Collect evidence using the retrieval order defined in
`token-efficiency.md#retrieval-hierarchy`. Cite file paths and command
ids; do not paste full outputs. Large logs belong in the review log, not
the handoff.
