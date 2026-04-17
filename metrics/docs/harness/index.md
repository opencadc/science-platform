# Harness v2 index

This directory is the canonical, tool-agnostic coding harness for this
repository. It defines reusable process contracts and machine-readable policy
for Codex, Cursor, Claude, and any future adapter registered in
`.harness/harness/checks.py::ADAPTER_DIRS`.

## Purpose

Route agents and tools to canonical harness modules without duplicating policy.

## Trigger

Read this first for coding tasks, adapter updates, or harness changes.

## Inputs

User request, repository docs, harness modules, adapter files.

## Required outputs

Correct precedence, module selection, and adapter routing.

## Examples

Good: update `docs/harness/hook-policy.yaml` and regenerate adapter mirrors. Bad: place new safety policy only in `.codex/`.

## Non-goals

This file does not define project-specific product behavior.


## Precedence

Apply rules in this order when policy conflicts occur.

1. User request in the current session.
2. Repository-specific constraints in `docs/` and `project-gates.yaml`.
3. Canonical harness modules and YAML schemas in `docs/harness/`.
4. Tool adapters under `.codex/`, `.cursor/`, `.claude/`, and any future
   adapter directory registered in `.harness/harness/checks.py::ADAPTER_DIRS`.

## Change control

Keep each module under 500 lines. Update YAML schemas, adapter mirrors, fixture
scenarios, and contract tests in the same change when behavior changes.

## Module map

Navigate from here to the right module by concern. Every module owns a
single slice of the harness contract.

| Concern                   | Module                            | Machine policy                 |
| ------------------------- | --------------------------------- | ------------------------------ |
| Execution loop            | `loop.md`                         | —                              |
| Core engineering rules    | `rules-core.md`                   | —                              |
| Capability routing        | `routing.md`                      | `router-policy.yaml`           |
| Hook policy + semantics   | `hooks.md`                        | `hook-policy.yaml`             |
| Verification gates        | `verification.md`                 | `project-gates.yaml`           |
| Handoff artifacts         | `artifacts.md`                    | `artifact-schema.yaml`         |
| Handoff templates         | `handoff-templates.md`            | `artifact-schema.yaml`         |
| Subagents & claims        | `subagents.md`                    | `.harness/claims.jsonl` (schema in module) |
| Review + arbitration      | `review-arbitration.md`           | —                              |
| Reviewer personas         | `personas/*.md`                   | adapter parity check           |
| Token efficiency          | `token-efficiency.md`             | `router-policy.yaml.metrics`   |
| Doc gardening             | `doc-gardening.md`                | —                              |
| Operational metrics       | `metrics.md`                      | `metrics-schema.yaml`          |
| Harness learnings         | `learnings.md`                    | docs ownership check           |
| Governance                | `GOVERNANCE.md`                   | —                              |
| Change history            | `CHANGELOG.md`                    | —                              |
