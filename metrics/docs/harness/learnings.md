# Harness learnings

This file records durable lessons about the harness itself. Product and
repository implementation lessons belong in `docs/learnings.md`.

## Purpose

Prevent repeated process mistakes without mixing product facts into reusable
policy.

## Trigger

Update after harness incidents, arbitration findings, or validated process
improvements. Remove an entry only when the lesson is superseded or codified
in a schema and a test.

## Inputs

Date, context, lesson, evidence, and action taken.

## Required outputs

A concise harness-level learning, or removal of a stale learning with a note
pointing at the codifying schema or test.

## Examples

Good: record that adapter policy must mirror canonical YAML. Bad: record a
product endpoint decision here.

## Non-goals

This file does not store product architecture, runtime behavior facts, or
task-level execution logs.

## Archive policy

Keep this file focused. It is a rolling log, not a historical archive.

- Keep at most **10** entries in the "Current entries" section.
- When adding an eleventh entry, move the oldest to
`docs/harness/review-logs/learnings/<year>.md` in the same change.
- Remove an entry here once it is codified in a schema plus test and
record the codifying reference in the archived copy.
- Never delete an entry without archiving it; evidence chain matters.

## Current entries

- Date: 2026-04-16
- Context: Harness bootstrap.
- Lesson: Keep process policy centralized in `docs/harness/` and route from
`AGENTS.md` only.
- Evidence: Initial harness rollout created canonical modules and thin
adapters.
- Action taken: Added modular rule docs and adapter wrappers; enforced
routing-only `AGENTS.md` via contract test.
- Date: 2026-04-16
- Context: Multi-reviewer arbitration for harness hardening.
- Lesson: Risk checks must be evaluated by severity, not declaration order,
or allow-rules can mask dangerous compound commands such as
`cat X && rm -rf Y`.
- Evidence: Review finding REL-001 plus failing hook fixture tests.
- Action taken: Implemented risk-priority hook evaluation and control-operator
guardrails for allow-rules in `.harness/harness/contracts.py`.
- Date: 2026-04-16
- Context: Cross-tool adapter portability.
- Lesson: Absolute paths in routing docs and adapters break environment
agnosticism and inflate tokens.
- Evidence: Architecture and token reviewer findings ARC-001 and TOK-003.
- Action taken: Replaced absolute paths with repository-relative references
and codified the rule via `check_no_absolute_paths`.
- Date: 2026-04-17
- Context: Harness v2 convergence review.
- Lesson: Module contract headings (purpose/trigger/inputs/outputs/examples/
non-goals) alone do not guarantee substantive policy; a module can pass the
heading check while losing its operational content.
- Evidence: First v2 implementation shipped modules that passed
`validate_module_contract` but removed engineering rules, operational
controls, SLAs, and reviewer schema detail.
- Action taken: Added `check_module_substance` with per-module required
sub-headings, line-count floors, and required policy tokens (for example
WIP cap, 3-of-4 quorum, risk-priority actions).
- Date: 2026-04-17
- Context: Adapter manifest runtime wiring.
- Lesson: Mirroring the canonical hook policy inside adapter files satisfies
schema parity but does not cause the tool runtime to invoke the harness.
- Evidence: Codex and Cursor runtimes expect event-to-command bindings, not
a declarative `checks[]` block. The mirror-only manifest never fired at
runtime.
- Action taken: Introduced `harness.hooks.bridge` as the tool-agnostic
evaluator and reshaped `.codex/hooks.json` and `.cursor/hooks.json` to
bind tool-runtime events to the bridge CLI with a `--tool` flag. Added
`check_adapter_wiring` to enforce the binding.
- Date: 2026-04-17
- Context: Metric vocabulary drift between routing and schema.
- Lesson: When routing policy, prose, and metric schema each carry their
own vocabulary, names drift silently and dashboards measure nothing.
- Evidence: `router-policy.yaml` tracked `review_turnaround` while
`metrics-schema.yaml` declared `reviewer_sla` and `review_to_decision`;
the architecture and token reviewers both flagged the mismatch.
- Action taken: Added `check_metrics_vocabulary` to assert every metric
named in `router-policy.yaml:metrics.track` is declared in
`metrics-schema.yaml`, renamed the conflicting keys, and bumped both
schema versions.
- Date: 2026-04-17
- Context: Hook bridge behavior on empty or malformed payloads.
- Lesson: Empty payloads on high-risk events must fail safe; silent
default-allow erodes the hook surface precisely when a tool is
malfunctioning and emitting nothing useful.
- Evidence: Reliability review finding REL-8 and a new fixture covering
`shell_command_start` with `{}`.
- Action taken: Added `_empty_payload_decision` in
`.harness/harness/hooks/bridge.py` so `shell_command_start` and
`pre_file_write` emit `EMPTY-PAYLOAD-WARN` (`advisory_warn`) when the
payload is empty or unreadable; low-risk events keep `advisory_allow`.
Every tool shape also emits the matched `check_id` in the human-facing
message for auditability.
