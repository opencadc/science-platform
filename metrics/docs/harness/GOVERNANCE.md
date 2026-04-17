# Harness governance

Harness-sensitive changes affect `docs/harness/`, `.harness/`, and every
adapter directory registered in `.harness/harness/checks.py::ADAPTER_DIRS`
(currently `.codex/`, `.cursor/`, `.claude/`). Treat these changes as process
infrastructure changes.

## Review requirements

- Run `uv run --group harness python -m harness check` before handoff.
- Run `uv run --group harness pytest -q tests/harness/test_contracts.py` before
  handoff.
- Use the reviewer set in `docs/harness/review-arbitration.md` for material
  policy, schema, hook, adapter, or routing changes.

## Versioning

Bump schema versions when changing wire shape, required fields, or hook action
semantics. Record the change in `docs/harness/CHANGELOG.md`.
