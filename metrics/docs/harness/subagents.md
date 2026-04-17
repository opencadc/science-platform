# Subagent strategy

Use subagents to accelerate bounded work without polluting context. Delegation
must preserve ownership, evidence, and non-overlap.

## Purpose

Scale work safely across parallel agents while keeping the primary context
compact and the work-claim table unambiguous.

## Trigger

Use before launching or assigning parallel agents or reviewers, and whenever
the primary agent considers handing off work that could be done locally.

## Inputs

Task scope, path claims, dependencies, token pressure, overlap risk, host
capacity, and the `router-policy.yaml` capability class for the stage.

## Required outputs

Work-claim table, bounded prompt, evidence-bearing handoff per
`handoff-templates.md`, and released claim on completion or failure.

## Examples

Good: split non-overlapping docs and tests between two workers while the
lead implementer proceeds on the critical path. Bad: assign two workers to
overlapping globs under `.codex/` and `.cursor/` at the same time.

## Non-goals

Subagents do not own critical-path decisions without supervision. They do
not replace human review for ambiguous requirements.

## Delegate when

- The task is well-scoped, with a clear input and a clear output contract.
- Work can run in parallel without blocking immediate progress.
- A specialist perspective (reviewer, scale, token-efficiency) improves
  outcome quality.
- The parent context is growing unmanageable and a focused subcontext will
  reduce retries.

## Do not delegate when

- The next local step is blocked on that result and local execution is
  faster than the dispatch round-trip.
- Requirements are ambiguous and need first-party clarification before any
  worker can act without retry.
- The write scope overlaps with another active claim or mirror set.
- The task requires cross-file reasoning the subagent will not inherit.

## Binary dispatch checklist

Apply before every dispatch. All answers must match the expected column.

| # | Question | Expected |
| - | -------- | -------- |
| 1 | Input contract stated? | yes |
| 2 | Output contract stated? | yes |
| 3 | Any open clarifying question? | no |
| 4 | Any active claim overlap (including mirror sets)? | no |
| 5 | Estimated wall time > **3 minutes** of dispatch round-trip? | yes |
| 6 | Does the worker context fit within **16k tokens** of input? | yes |

If any answer disagrees, resolve before dispatch or run the slice locally.
The 3-minute wall-time threshold and 16k-token context ceiling are the
defaults; raise them deliberately in the dispatch record when a specific
route in `router-policy.yaml` justifies a larger budget.

## Adaptive parallelism budget

Compute worker capacity in two steps.

1. `raw_cap = 1 + queue_size + host_capacity - overlap_penalty - token_pressure`
2. `cap = max(1, min(6, raw_cap))`

Inputs (integer ranges):

- `queue_size`: 0-3 (0 = empty, 1 = small, 2 = medium, 3 = large independent
  backlog).
- `host_capacity`: 0-2 (0 = constrained, 1 = standard, 2 = high-capacity).
- `overlap_penalty`: 0-2 (none, moderate, high write-overlap risk).
- `token_pressure`: 0-2 (normal, elevated, near budget cap).

With these ranges `cap` can reach the full `[1, 6]` clamp.

### Reserved scale-reviewer slot

Any process, architecture, or routing change requires a scale reviewer. Compute
the reservation after capacity:

`implementer_cap = max(0, cap - reserved_reviewer_slots)`

`reserved_reviewer_slots` is `1` when the active slice can degrade at scale,
otherwise `0`. When `implementer_cap == 0`, defer dispatch rather than run
without a scale reviewer.

## Work-claim table

Claims are recorded as append-only JSONL at `.harness/claims.jsonl`
(gitignored). Every dispatch must (1) read the file, (2) verify no active
claim overlaps the new `path_globs` or mirror set, (3) append a new claim,
(4) append a `released` record on completion or failure. Do not dispatch
without writing the claim first.

Use `python -m harness claim acquire` to create claims, and use
`python -m harness claim heartbeat`, `python -m harness claim release`, and
`python -m harness claim reclaim` for lifecycle transitions. The CLI enforces
mirror-set overlap, recursive-glob rejection, stale-claim detection, and
append-only writes.

### Claim record schema

- `owner`: agent identifier.
- `claim_id`: stable identifier emitted by `python -m harness claim acquire`
  and reused for lifecycle transitions.
- `path_globs`: explicit globs the worker may write (no implicit recursion).
- `mirror_sets`: list of mirror-set ids (see "Mirror sets" below) that
  this claim extends into.
- `status`: one of `claimed`, `in-progress`, `released`, `reclaimed`.
- `created_at`: ISO-8601 timestamp.
- `expires_at`: `created_at + 30 minutes` by default; must match the review
  SLA in `review-arbitration.md`.
- `heartbeat_at`: ISO-8601 timestamp; the worker must renew every 10 minutes.

### Expiry and reclaim rules

- If `heartbeat_at` lags `now` by more than 10 minutes, the claim is stale.
- If `expires_at` is reached without renewal, the claim is expired and may
  be reclaimed. Reclaim writes a `reclaimed` record; the original worker
  must detect the release before writing and abort.
- Concurrent writes on the same globs after reclaim are a contract
  violation and must be reported as a reliability finding.

### Mirror sets

Files that must stay in lockstep are declared as mirror sets so syntactic
path-glob non-overlap does not mask semantic drift.

- `adapter-hooks`: `.codex/hooks.json`, `.cursor/hooks.json`,
  `.claude/hooks.json`.
- `adapter-agents`: `.codex/agents/*.md`, `.cursor/agents/*.md`,
  `.claude/agents/*.md`.
- `routing-index`: `AGENTS.md`, `docs/harness/index.md`.

A claim that touches any leg of a mirror set implicitly claims every leg.
Two workers claiming different legs of the same set is an overlap even when
their `path_globs` are disjoint.

## Context-isolation policy

- Give each worker only task-relevant context: the acceptance criteria, the
  canonical schema reference, the relevant paths, and the current gate
  status.
- Provide canonical schema references rather than pasted rules.
- Require changed-files list and explicit assumptions in worker outputs so
  the parent can integrate without replaying the worker's history.

## Token budget policy

- Prefer file references over pasted content.
- Include only the excerpts a reviewer needs to reach the finding, never a
  full module when a range suffices.
- Prune unrelated chat history before launching additional workers; a fresh
  subagent should not inherit irrelevant prior turns.
- Treat token cost as a first-class operational metric; see
  `token-efficiency.md` and `metrics-schema.yaml`.
