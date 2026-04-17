# Harness changelog

This file records harness contract and schema changes that affect adapters,
validation, or operating policy.

## Schema versions

- hook-policy 2.2
- router-policy 2
- artifact-schema 2
- metrics-schema 2
- project-gates 1

## 2.2.0 - 2026-04-17

- Moved repository-owned documentation into `docs/`: project architecture,
  design, specs, and project learnings now live at `docs/architecture.md`,
  `docs/design.md`, `docs/specs.md`, and `docs/learnings.md`. Harness-specific
  lessons remain in `docs/harness/learnings.md`.
- Moved canonical reviewer personas into `docs/harness/personas/`; adapter
  persona files now mirror that harness-owned source instead of using one
  tool adapter as canonical.
- Added `python -m harness claim` with acquire, heartbeat, release, and reclaim
  subcommands for append-only work-claim enforcement. The CLI blocks mirror-set
  overlap, rejects recursive globs, detects stale claims, and records lifecycle
  transitions in `.harness/claims.jsonl`.
- Added hook bridge strict-mode overrides via `--mode strict` and
  `HARNESS_HOOK_MODE=strict` so CI and unattended runs can apply strict
  semantics without editing `hook-policy.yaml`.
- Unified metrics vocabulary across `router-policy.yaml`, `metrics-schema.yaml`,
  and prose: renamed `review_turnaround` to `reviewer_sla` +
  `review_to_decision`; added `retry_rate`, `reopen_rate`,
  `parallel_workers_max`, `claim_stale_count`, `dispatch_blocked_overlap`.
  Added `metrics vocabulary` contract check asserting the router track list
  is a subset of declared metric keys.
- Hook bridge output now includes the matched `check_id` in every tool shape
  for auditable block/ask/warn/allow decisions. Added `_audit_message`
  helper and shaper registry signature update.
- Hook bridge treats empty or malformed payloads on `shell_command_start`
  and `pre_file_write` as `advisory_warn` (`EMPTY-PAYLOAD-WARN`) rather
  than silent allow.
- Hardened hook patterns: `CMD-BLOCK-PIPE-TO-SHELL` now covers `dash`,
  `ksh`, `fish`, `ruby`, `perl`, `node`, `lua`, `iex`, `irm`,
  `invoke-restmethod`; `CMD-ASK-DESTRUCTIVE-RM` catches `rm -r` without
  `-f`; `CMD-ASK-SED-IN-PLACE` catches `--in-place`;
  `CMD-ASK-FIND-DELETE` catches `-execdir`/`-okdir`/`-ok` forms; added
  `CMD-ASK-SYSTEM-WRITE` for `sudo tee`, `install`, `cp`, `mv` into
  `/etc`, `/usr`, `/var`, `/boot`, `/bin`, `/sbin`, `/opt`, `/root`,
  `/lib`, `/lib64`.
- Added `subagent_dispatch` stage to `artifact-schema.yaml` with canonical
  handoff fields that mirror the subagent dispatch template.
- Extended `check_schema_version` to cover router, artifact, metrics, and
  project-gates versions; every version must be declared and recorded in
  the CHANGELOG "Schema versions" section.
- Added `check_metrics_vocabulary`, `check_adapter_map`, and
  `check_agents_index` to enforce metric/router alignment, adapter
  discovery, and `AGENTS.md` supersetting of canonical modules.
- Added required sub-headings for the previously thin modules (routing,
  artifacts, verification, doc-gardening, metrics, learnings) and a module
  map in `index.md`.
- Added retrieval hierarchy citations in `routing.md`, `verification.md`,
  and `hooks.md`; reconciled `AGENTS.md` to list every canonical module.
- Capped the "Current entries" section of `docs/harness/learnings.md` at
  10 entries with an archive path at `docs/harness/review-logs/learnings/`.
- Extended the differentiated-lens persona test across every adapter in
  `ADAPTER_DIRS` and documented Codex, Cursor, and Claude adapters
  explicitly.

## 2.1.0 - 2026-04-17

- Hardened `hook-policy.yaml` patterns: sudo-rm now handles `-fr`, `-Rf`,
  and `--recursive --force`; pipe-to-shell covers process substitution,
  eval of curl/wget capture, fetch-then-exec, and `pwsh`/`iwr`;
  adapter-write gate now covers `.claude/`; added `find -delete`,
  `find -exec rm`, `sed -i`, `dd` to devices, recursive chmod/chown of
  root paths, redirection into system paths, and a
  `WRITE-ASK-SECRET-FILE` rule.
- Removed `sed` and `find` from `CMD-ALLOW-READONLY`; both ship
  destructive variants that were masking higher-risk checks.
- Expanded secret-file pattern to cover `.env.*`, SSH private keys,
  `.pem`, `.pfx`, `.p12`, `.key`, `kubeconfig`, `.npmrc`, `.pypirc`,
  and `.aws/credentials`.
- Extended fixtures from 18 to 46 cases, adding negative scenarios per
  check and new positive coverage for every hardened pattern.
- Lifted the coverage floor (`--cov-fail-under=80`) out of
  `.harness/harness/checks.py` into the `project-gates.yaml` field
  `min_coverage`.
- Replaced the argparse `SUPPORTED_TOOLS` whitelist in
  `harness.hooks.bridge` with an open `--tool` argument backed by a shaper
  registry; added `_claude_output` and a live bridge-runtime check.
- Added `check_bridge_runtime` and `check_persona_parity` to
  `harness check`.
- Reconciled parallelism formula, work-claim storage, SLA naming, WIP
  cap, and required-reviewer-set semantics across `subagents.md`,
  `loop.md`, and `review-arbitration.md`.

## 2.0.0 - 2026-04-17

- Added machine-readable router, artifact, hook, metric, and project gate
  validation contracts.
- Moved harness runtime code under `.harness/harness`.
- Added Codex, Cursor, and Claude parity checks for hook manifests and
  reviewer personas.
- Split harness learnings from product learnings.
