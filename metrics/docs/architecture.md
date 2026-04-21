# Architecture reference

This file stores repository-specific architecture facts only. Harness process
policy belongs in `docs/harness/`.

## Ownership

- Record stable boundaries and module responsibilities.
- Document architecture invariants that are verifiable in code.
- Remove claims that are not currently implemented.

## Current state

- Deployment and environment naming contracts for Metrics are summarized in
  `environment-contracts.md` (same directory as this file).
- `src/metrics/` is the Python package root.
- Runtime dependencies are defined in `pyproject.toml`.
- Test dependencies are in the `dev` dependency group.

## Update rules

- Keep content implementation-backed.
- Prefer short, direct explanations with concrete paths.
