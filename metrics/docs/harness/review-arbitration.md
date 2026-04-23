# Review and arbitration

This module defines the reviewer findings schema, the required reviewer set,
quorum and latency rules, and the arbitration process for resolving conflicts.

## Purpose

Turn reviewer output into an accepted, adjusted, or waived finding set with a
decision log that is auditable without replaying the conversation.

## Trigger

Use for peer review, harness changes, architecture changes, milestone closures,
and any change that touches `docs/harness/`, `.harness/`, or adapter files.

## Inputs

Reviewer findings, active diff, relevant plans, milestone context, gate
outcomes, and prior review logs under `docs/harness/review-logs/`.

## Required outputs

Accepted finding set, waiver rationale when applicable, final decision log,
and updates to `docs/harness/learnings.md` when a durable lesson is proven.

## Examples

Good: merge duplicate findings by root cause and risk, then mark each as
`accepted`, `accepted-with-adjustment`, or `waived-with-rationale`. Bad:
accept a style-only comment as a blocker.

## Non-goals

Arbitration does not remove implementation ownership. It does not re-scope
the active milestone; scope changes flow through `milestone-process.md`.

## Finding schema

Every finding must include:

- `id`: stable identifier (for example `ARC-001`, `REL-002`, `TOK-003`,
  `SCL-004`).
- `scope`: path, subsystem, or component affected.
- `category`: one of `architecture`, `reliability`, `token`, `scale`.
- `severity`: one of `P0`, `P1`, `P2`, `P3`.
- `confidence`: float in `[0.0, 1.0]`.
- `root_cause`: the technical source of the issue.
- `evidence`: concrete, repo-verifiable observations.
- `risk`: the impact if unresolved.
- `action`: minimal change required.

Confidence anchors:

- `0.9` — directly proven defect with reproducible evidence.
- `0.7` — strongly evidenced risk with a likely but not certain failure mode.
- `0.5` — plausible risk with incomplete evidence.
- `< 0.5` — optional question or soft suggestion only.

## Required reviewer set

For any change that touches harness contracts, adapter files, or hook policy,
run all four reviewers (each reviewer is anchored to its policy module):

- Architecture reviewer — policy: `rules-core.md`, `index.md`.
- Reliability and security reviewer — policy: `hooks.md`,
  `verification.md`.
- Scale reviewer — policy: `subagents.md`, `routing.md`.
- Token-efficiency reviewer — policy: `token-efficiency.md`,
  `router-policy.yaml`.

For repo-specific feature work outside the harness, the architecture and
reliability reviewers are always required; scale and token-efficiency are
required when process or routing is affected.

Canonical reviewer personas live in `docs/harness/personas/`. Adapter copies
under `.codex/agents/`, `.cursor/agents/`, and `.claude/agents/` must hash-match
those harness-owned files; adapters are mirrors, not sources of truth.

## Review latency and quorum

- Each required reviewer has a **30-minute SLA** from request to output.
- For **feature work outside the harness**, quorum is reached when at
  least **3 of 4** required reviewers return within SLA. On quorum,
  arbitration proceeds. The missing reviewer's output is still accepted if
  it arrives before the decision log is published; otherwise log the gap as
  "late or missing" and move on.
- For changes that touch **harness contracts, adapter files, hook policy,
  or reviewer personas**, missing any required reviewer is a **hard stop gate**,
  not a quorum exception. Extend the SLA, retry the dispatch, and
  defer arbitration until all four outputs are available. Quorum does not
  apply.
- Arbitration itself must complete within **2 hours** of the final reviewer
  output. A slower arbitration is a signal to narrow scope or increase
  reviewer bandwidth, not to proceed with an incomplete reviewer set.

## Arbitration process

1. Collect findings from all returning reviewers.
2. Merge duplicates by `(scope, root_cause, risk)`.
3. Resolve conflicts using this precedence:
   - Safety and correctness over convenience.
   - Invariant preservation over flexibility.
   - Simplicity and clarity over speculative extensibility.
4. Tie-break owner is the lead implementer for the active slice.
5. Mark each finding as one of:
   - `accepted`
   - `accepted-with-adjustment`
   - `waived-with-rationale`
6. Apply `accepted` and `accepted-with-adjustment` fixes, rerun affected
   gates, and update evidence.
7. Publish the decision log at
   `docs/harness/review-logs/<date>-<slice>.md` with accepted ids, waivers,
   blocker status, and the reviewer set that participated.

## Publishing the decision log

Each log entry must include:

- Inputs: reviewer finding ids and the reviewer files that produced them.
- Decisions: status per finding.
- Waivers: rationale for each waived finding.
- Blocking status: whether any previously blocking finding remains open.
- Next action: remediation owner and due date if applicable.
