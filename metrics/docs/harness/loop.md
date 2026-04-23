# Agentic execution loop

This loop is the default operating model for software work in this repository.
It keeps work scoped, testable, and auditable across tools.

## Purpose

Define the smallest reliable path from request to verified handoff.

## Trigger

Use for coding, docs, process, and review tasks unless the user only asks a
narrow factual question that can be answered from the current context.

## Inputs

Objective, constraints, acceptance criteria, gate definitions from
`project-gates.yaml`, and the relevant harness and repository docs.

## Required outputs

Scope, plan, execute, verify, learn, and record evidence. The handoff must
include the gate decisions and the artifacts named in
`docs/harness/artifact-schema.yaml`.

## Examples

Good: fix a missing prerequisite before adding a feature that depends on it.
Bad: bypass a failed invariant to finish faster.

## Non-goals

This loop does not replace project-specific gates in `project-gates.yaml`, and
does not define reviewer output shape; see `review-arbitration.md`.

## Loop steps

1. **Scope.** Define the objective, constraints, prerequisites, and
   acceptance criteria. Identify what counts as finished.
2. **Plan.** Choose the smallest coherent slice and the routing class from
   `router-policy.yaml`. Decide whether subagents are appropriate per
   `subagents.md`. Apply the retrieval hierarchy in
   `token-efficiency.md#retrieval-hierarchy` when assembling context.
3. **Execute.** Apply minimal, high-signal edits. Preserve the invariants
   declared in `rules-core.md`. Follow the efficiency rules in
   `token-efficiency.md#efficiency-rules` so context stays bounded.
4. **Verify.** Run the gates listed in `project-gates.yaml`. Collect concrete
   evidence for each acceptance criterion, not just "tests pass".
5. **Learn.** Update `docs/harness/learnings.md` for harness lessons and
   `docs/learnings.md` for project lessons. Keep detailed product facts in
   `docs/architecture.md`, `docs/design.md`, and `docs/specs.md`.

## Operational controls

Track these per active milestone and surface them at handoff:

- WIP cap per owner: **2** active tasks. Dispatched subagents carry their
  own `owner` field in the work-claim table and count toward *that* owner's
  WIP independently. The parent agent counts each supervised dispatch as
  **0.5 WIP** (rounded up to an integer) so a parent supervising two
  subagents is at its **WIP cap of 2** and must not start a new local
  slice until one dispatch releases.
- Per-reviewer SLA: **30 minutes** (see
  `review-arbitration.md#review-latency-and-quorum`).
- End-to-end review-plus-arbitration target: **under 4 hours** (30-minute
  reviewer SLA plus arbitration under 2 hours plus scheduling buffer).
- Arbitration latency target: **under 2 hours** after quorum is reached.
- Retry rate target: **under 10%** across a milestone, defined as
  `metrics-schema.yaml:retry_rate` (retry_count divided by accepted
  outcomes per milestone).

When a control is missed, record the cause and the mitigation in the milestone
verification handoff. Repeated misses are a signal to adjust routing, reviewer
set, or scope, not to quietly relax the target.

## Stop/go gates

- **Stop** if prerequisites are missing and cannot be safely inferred from
  current evidence.
- **Stop** if a required permission is blocked; request it explicitly and
  state the blocker.
- **Stop** when a required gate in `project-gates.yaml` cannot run; document
  the blocker, impact, and remediation rather than proceeding without it.
- **Go** when the next step has clear acceptance criteria and the
  prerequisite is in scope for the current slice.
- **Go to release** only when every required gate has passed and peer review
  is resolved per `review-arbitration.md`.

## Example

- Goal: add a new webhook endpoint.
- Prerequisite: the auth middleware invariant is currently missing.
- Correct action: fix the auth invariant as a standalone slice, record it as
  evidence, then add the endpoint in a second slice.
- Incorrect action: add the endpoint and patch around the missing invariant
  with ad-hoc checks at the route level.
