# Doc-gardening

Doc-gardening removes stale policy, duplicated guidance, and broken references after material changes.

## Purpose

Control entropy in the harness and repository docs.

## Trigger

Run after milestones, process incidents, major features, or quarterly maintenance.

## Inputs

Changed files, review findings, learnings, and active milestone plans.

## Required outputs

Updated docs, removed duplication, refreshed links, and recorded lessons.

## Examples

Good: move repeated adapter policy into `docs/harness/`. Bad: add a second source of truth to fix drift.

## Non-goals

Doc-gardening does not rewrite stable product facts without evidence.

## When to run

- After any milestone closes or reviewers surface duplication.
- After a harness `CHANGELOG.md` entry moves or renames policy.
- Quarterly, even without incidents, as a scheduled entropy sweep.
- Whenever a contract test reveals that two docs now restate the same
  fact in different language.

## Entropy signals

Watch for these signals and budget a gardening slice when two or more
appear at once.

- The same numeric target (SLA, retry rate, coverage floor) appears in
  three or more docs in free text rather than a single cited source.
- A reviewer finding cites "could be trimmed" or "duplicated" across
  modules.
- `docs/harness/learnings.md` exceeds its rolling cap and needs archival
  under `docs/harness/review-logs/learnings/`.
- Adapter manifests grow prose sections beyond event wiring.
- An `AGENTS.md` or `index.md` reader needs more than two hops to reach
  the right module.
