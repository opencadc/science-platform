# ADR-0001: Kubernetes-first environment contract

## Status

Accepted

## Context

Metrics must run the same way locally, in CI, and in deployed clusters. Docker
Compose drifted from real runtime dependencies.

## Decision

- Supported environments: `dev`, `integration`, `staging`, `production`
  (`METRICS_ENVIRONMENT`; legacy `int`/`prod` normalize at validation).
- **Kubernetes-first in every environment.** Docker Compose is not part of the
  supported contract.
- `dev` uses kind, Helm, and `kubectl` with Kueue fixtures and in-cluster Redis.
- Higher environments assume an operating cluster and Helm deploy paths only.

## Consequences

- Local verification flows through `scripts/kind-smoke.sh` and related scripts,
  not compose stacks.

## References

- [`../environment-contracts.md`](../environment-contracts.md)
- [`../../CONTEXT.md`](../../CONTEXT.md)
