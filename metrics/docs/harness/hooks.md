# Hook policy

Hooks provide fast preflight and postflight checks with advisory-first
behavior. Canonical machine policy lives in `hook-policy.yaml`; this module
documents the semantics every adapter must preserve.

## Purpose

Define safe automation boundaries for commands, writes, startup context, and
completion checks without replacing tests or human approval.

## Trigger

Use when adding hook checks, adapter manifests, or hook runtime code, and
when a reviewer evaluates whether a command should be blocked, asked, warned,
or allowed.

## Inputs

`hook-policy.yaml`, adapter manifests, event payloads, fixture scenarios, and
observed false-positive or false-negative history.

## Required outputs

Decision id, action, reason, and validation evidence. For `advisory_block`
and `strict_block`, the decision must also include the payload excerpt that
matched.

## Examples

Good: promote a repeated `advisory_warn` to `advisory_ask` after three
independent incidents, with a new fixture scenario committed in the same
change. Bad: block broad command classes without evidence and without a
fixture regression.

## Non-goals

Hooks do not replace tests, peer review, or human approval for ambiguous
risk. They are not a policy engine for code style or a substitute for
ownership.

## Default mode

- Start adapters in `advisory` mode.
- Use `advisory_ask` for dangerous or ambiguous payloads where a human
  should decide.
- Use `advisory_block` only for known high-risk patterns that have no safe
  use case in this repository.

## Strict mode semantics

When `mode` is `strict`, actions map as follows:

- `advisory_allow` becomes `strict_allow`.
- `advisory_warn` becomes `strict_warn`.
- `advisory_ask` becomes `strict_block`.
- `advisory_block` becomes `strict_block`.

Strict mode is intended for CI or unattended runs. Interactive sessions
default to `advisory`. Use `python -m harness.hooks.bridge <event> --mode
strict` or set `HARNESS_HOOK_MODE=strict` to apply strict semantics at
runtime without editing `hook-policy.yaml`.

## Risk-priority evaluation

When multiple checks match the same payload, the adapter selects the
highest-risk decision:

`advisory_block` > `advisory_ask` > `advisory_warn` > `advisory_allow`.

`advisory_allow` rules are skipped when the payload contains shell control
operators (`&&`, `||`, `;`, `|`, `$(...)`, backticks). This prevents an
allow-rule from masking a dangerous compound command such as
`cat X && rm -rf Y`.

## Cross-tool event model

All adapters must normalize to these events:

- `session_start` — fired when a new agent session begins.
- `shell_command_start` — fired before a shell command executes.
- `pre_file_write` — fired before a file write or edit applies.
- `post_task_complete` — fired when a task is marked complete.

Tools may add additional events, but must not drop or rename these.

## Escalation path from advisory to strict

1. Ship the check in `advisory_warn` and collect fixture scenarios for both
   positive and negative cases.
2. After three independent incidents confirm signal, promote the check to
   `advisory_ask`. Add the corresponding scenario and update the CHANGELOG.
3. Only after `advisory_ask` has been accepted without disputed denials for
   a full milestone may the check be promoted to `advisory_block`.
4. Strict mode is an operator setting, not a per-check promotion path.

## Required evidence for any hook change

- Decision id and matched pattern.
- User-facing message text.
- At least one new fixture scenario in
  `docs/harness/fixtures/hooks/scenarios.json`.
- Updated adapter manifests; parity check must remain green.
- Telemetry sample (optional, when `HARNESS_TELEMETRY=1`) showing the
  decision was emitted.

## Adapter runtime wiring

Adapter manifests bind tool-runtime events to the harness bridge CLI. The
canonical policy in `hook-policy.yaml` is interpreted by
`python -m harness.hooks.bridge <event>`. Adapter wiring is validated by the
`adapter wiring` check in `python -m harness check`.

Reviewers evaluating a hook change should read
`hook-policy.yaml` first, then `hooks.md`, then the relevant fixtures in
`docs/harness/fixtures/hooks/scenarios.json`. See
`token-efficiency.md#retrieval-hierarchy` for the general rule: prefer YAML
schema over prose, and prose over chat context.
