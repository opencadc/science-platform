# Token-efficiency policy

Token efficiency keeps agent work high signal and cost aware without
sacrificing correctness. Treat it as a design constraint, not a post-hoc
optimization.

## Purpose

Limit context growth and expensive-model usage while preserving the
reasoning quality the task actually requires.

## Trigger

Use during retrieval, delegation, escalation, long-running work, and when a
review finding flags excessive context or repeated rework.

## Inputs

Relevant paths, task state, open questions, token pressure, and the
capability class selected from `router-policy.yaml`.

## Required outputs

A compact context set, reproducible evidence, and a record of what was
deliberately left out of context with a brief justification.

## Examples

Good: cite `docs/harness/rules-core.md` and enforce a specific rule number.
Bad: paste entire unchanged docs into every subagent prompt.

## Non-goals

This policy does not minimize tokens at the expense of correctness. It does
not override safety gates or mandatory evidence requirements.

## Retrieval hierarchy

Fetch context in this order, escalating only when the lower tier is
insufficient:

1. Local source files and canonical harness docs.
2. Repository-specific docs (`docs/architecture.md`, `docs/design.md`,
   `docs/specs.md`, `docs/learnings.md`, `docs/plans/`).
3. Compacted artifact chain (research → plan → verification) for brownfield
   work.
4. External references, fetched deliberately with explicit cost awareness.

## Efficiency rules

1. **Start narrow.** Select files by name or symbol first; widen only on
   evidence of insufficient context.
2. **Prefer path references over pasted content.** A citation is cheaper
   and stays fresh.
3. **Reuse canonical excerpts.** Use shared templates from
   `handoff-templates.md` rather than re-deriving prompts.
4. **Do not repeat unchanged requirements across turns.** Link back to the
   acceptance criteria rather than restating them.
5. **Compact before escalation.** When escalating to a higher capability
   class, compress task state into goals, constraints, relevant files,
   diffs, open questions, and acceptance criteria.
6. **Forbid raw full-repo context to expensive models.** Declared in
   `router-policy.yaml.affordances.context.forbid_raw_repo_to_expensive_models`
   and enforced by (a) the token-efficiency reviewer per
   `review-arbitration.md#required-reviewer-set` and (b) the escalation
   trigger evidence requirement in
   `router-policy.yaml.escalation.triggers`.

## Context budget

- Keep the active working set to directly relevant files for the current
  slice.
- Summarize long outputs; preserve only actionable lines and failures.
- Split large tasks into independent subagent slices per `subagents.md`.
- Track `tool_calls`, `retry_count`, and `cost_per_completed_task` from
  `metrics-schema.yaml` over time; they are the feedback signal for tuning
  this policy.

## Applying the policy during review

- A reviewer may emit a `token` finding when context growth or duplication
  is the root cause of a cost or latency regression.
- Findings must cite concrete evidence (pasted blocks, redundant prompts,
  or unnecessary retrieval) rather than a general concern about "too much
  context".
