# Core engineering rules

These rules define default coding behavior and architectural discipline. They
apply to source, tests, docs, schemas, and adapters. They are intentionally
small in number, composable, and actionable.

## Purpose

Keep implementation simple, explicit, and invariant-preserving without turning
agents into lint robots.

## Trigger

Use whenever code, tests, configuration, schemas, or docs are changed, and
whenever a reviewer evaluates an architecture or design decision.

## Inputs

Current implementation, acceptance criteria, known invariants, known risks,
and the active milestone plan.

## Required outputs

Minimal coherent changes with explicit invariants and evidence, plus a short
justification when a rule is intentionally bent.

## Examples

Good: inline a one-use helper to reduce indirection. Bad: add three helper
layers to avoid two repeated lines.

## Non-goals

This module does not prescribe language-specific formatting rules, naming
conventions beyond clarity, or tool-specific lint configuration.

## Rules

1. **Keep it simple.** Minimize concept count. Prefer one coherent flow over
   two clever ones.
2. **Avoid duplication, but do not abstract without reuse value.** Two is a
   coincidence; three is a pattern worth extracting.
3. **When KISS and DRY conflict, redesign the flow.** Do not compound
   indirection to preserve duplication-free code.
4. **Make invariants explicit.** State and document the conditions that must
   hold across a module or boundary. Enforce them in types, asserts, or tests.
5. **Fix prerequisites cleanly.** When a defect blocks progress, repair the
   root cause. Do not layer tactical hacks that entrench the defect.
6. **Keep contracts stable.** Adapter wire-shape, API payloads, schema files,
   and handoff fields evolve through versioned migrations, never silently.
7. **Prefer diff-first edits.** Small, reviewable changes over wholesale
   rewrites. Rewrites require explicit justification.
8. **No fire-and-forget async.** Every async call must have defined error
   handling and cancellation semantics. Dropping `await` is forbidden.
9. **Test behavior, not implementation.** Assert on externally observable
   contract outcomes. Brittle tests against private helpers are noise.
10. **Separate process policy from product facts.** Harness-level rules live in
    `docs/harness/`; product-specific decisions live in `docs/` and
    `project-gates.yaml`.
11. **Comment intent, not mechanics.** Explain why a non-obvious choice was
    made. Do not narrate what the code already says.
12. **Fail loudly at boundaries.** Validate inputs at public entry points with
    typed models; reject malformed data with a clear message rather than
    silently coercing.

## Invariant policy

- **Code invariants** are documented assumptions about shared data, state
  transitions, and concurrency. Every non-trivial change must preserve or
  improve them.
- **Architecture invariants** are the boundary rules between modules or
  subsystems (direction of imports, ownership of state, synchrony model).
  Breaking one requires explicit discussion and, if accepted, a migration
  plan.
- **Contract invariants** are the wire-level promises of any externally
  visible surface (HTTP payloads, YAML schemas, hook outputs, handoff
  fields). Changes to contract invariants require schema-version bumps and a
  `docs/harness/CHANGELOG.md` entry when they touch the harness.

## Rule application

- Apply rules in order when they conflict: safety and correctness first,
  invariant preservation second, simplicity and clarity third.
- A rule may be bent intentionally. Document the reason in the handoff
  `evidence` field. Repeated bending of the same rule is a signal to update
  the rule or its scope, not to keep bending.
