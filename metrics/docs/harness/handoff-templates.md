# Handoff templates

Handoffs standardize communication between implementers, reviewers, verifiers,
and arbiters. Machine-required fields live in `artifact-schema.yaml`; this
module owns the reader-friendly templates.

## Purpose

Keep outputs compact, actionable, and evidence-backed so the next owner can
proceed without replaying conversation history.

## Trigger

Use when handing off work between stages, agents, or reviewers, and at
milestone closure.

## Inputs

Stage, goal, constraints, evidence, open questions, next action, and the
stage-specific fields defined in `artifact-schema.yaml`.

## Required outputs

A schema-compliant handoff with commands, files, risks, and decisions where
applicable. Findings must use the review schema in `review-arbitration.md`.

## Examples

Good: include the failed command, the blocker, and the smallest repro. Bad:
omit assumptions and ask the next owner to infer them.

## Non-goals

Templates do not replace issue trackers or long-form design docs. They do
not substitute for the decision log in `docs/harness/review-logs/`.

## Implementer template

```text
Stage: Implementation
Goal:
Constraints:
Evidence:
Open questions:
Next action:
Files changed:
Behavior delta:
Risks:
Commands run:
Assumptions:
```

## Verifier template

```text
Stage: Verification
Goal:
Constraints:
Evidence:
Open questions:
Next action:
Tests run:
Coverage result:
Peer review status:
Gate decision:
```

## Reviewer template

```text
Stage: Review
Goal:
Constraints:
Evidence:
Open questions:
Next action:
Findings:
- id:
  scope:
  category:
  severity:
  confidence:
  root_cause:
  evidence:
  risk:
  action:
```

## Scale-reviewer template

```text
Stage: Review
Role: Scale reviewer
Goal:
Constraints:
Evidence:
Open questions:
Next action:
Scalability findings:
- id:
  scope:
  category: scale
  severity:
  confidence:
  root_cause:
  evidence:
  risk:
  action:
Parallelism notes:
- work-claim overlap observed:
- worker cap computed:
- reserved scale-reviewer slot:
```

## Subagent dispatch template

```text
Stage: Subagent dispatch
Owner:
Path globs:
Mirror sets claimed:
Acceptance criteria:
Input file references:
Expires at:
Heartbeat cadence:
Reviewer requirement:
Token budget:
```

Subagent dispatches must cite input files by path (not pasted content),
register the claim in `.harness/claims.jsonl` before launch, and pass every
question in the binary dispatch checklist in `subagents.md`.

## Arbitration template

```text
Stage: Arbitration
Goal:
Constraints:
Evidence:
Open questions:
Next action:
Accepted findings:
Waivers:
Decision log path:
Final status:
```

## Required output quality

- Findings must be actionable and tied to repository evidence.
- Commands run must be reproducible from the handoff alone.
- Assumptions must be listed explicitly; silent assumptions create rework.
- Cite input files by path, not pasted content, per
  `token-efficiency.md#retrieval-hierarchy`.
