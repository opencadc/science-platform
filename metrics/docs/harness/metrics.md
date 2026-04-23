# Harness metrics

Harness metrics measure whether the operating system is improving accepted outcomes, latency, cost, and rework.

## Purpose

Provide lightweight feedback for routing, hooks, review, and delegation.

## Trigger

Use during milestone validation and optional telemetry collection.

## Inputs

`metrics-schema.yaml`, review logs, gate outcomes, and optional telemetry.

## Required outputs

Metric summary, trend, and tuning action when thresholds drift.

## Examples

Good: lower a noisy hook after repeated false positives. Bad: optimize cost by skipping verification.

## Non-goals

Metrics do not collect secrets or raw command outputs by default.

## Schema linkage

`metrics-schema.yaml` is the single source of truth for metric names,
descriptions, and targets. `router-policy.yaml.metrics.track` must be a
subset of those declared metrics; the `metrics vocabulary` check in
`python -m harness check` enforces this. Add a metric to the schema first,
then route it to the track list, then cite it from prose.

## Tuning actions

When a target drifts, apply the smallest corrective action:

- `reviewer_sla` exceeded — reduce reviewer fan-out, raise the floor on
  input context quality, or adjust route per `router-policy.yaml`.
- `retry_rate` above 10% — narrow scope, strengthen acceptance criteria
  in the plan stage, or promote a noisy hook to `advisory_ask`.
- `cost_per_completed_task` trending up — re-apply the retrieval hierarchy
  in `token-efficiency.md#retrieval-hierarchy`, prune inherited chat, and
  recheck `forbid_raw_repo_to_expensive_models`.
- `claim_stale_count` non-zero — shorten dispatch wall time or raise
  heartbeat cadence in `subagents.md`.
- `dispatch_blocked_overlap` non-zero — split globs, adjust mirror sets,
  or serialize the conflicting slices.
