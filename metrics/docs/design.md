# Design reference

This file captures repository-specific design decisions and tradeoffs. It is
not used for generic harness instructions.

## Environment naming

Operational environment contracts and roadmap-to-runtime mappings for Metrics
live in `environment-contracts.md` in this directory.

## Current design (post M3)

- **Kubernetes-first service contract:** Dev, integration, staging, and
  production run through Kubernetes deployment paths. Docker Compose is not
  part of the supported service contract (see `environment-contracts.md`).
- **Single service process with source composition:** The FastAPI factory wires
  `KueuePlatformEngine` for platform maps, `KueueCapacityProvider` for user/session
  capacity, and `PrometheusUsageProvider` for usage. There is no `static` or
  `node` adapter path.
- **Supported platform sources:** Kueue and Prometheus are active; kube-metrics
  is configuration-only until M4.
- **Pydantic-first contracts:** `Settings` and HTTP schemas use Pydantic with
  nested `platform` / `user` domains and `pydantic-settings` env parsing (including
  legacy flat env merge for operators).

## Milestone design mapping

- M3: package realignment and provider cleanup.
- M4: kube-metrics runtime depth.
- M5: user metrics hardening.
- M6: session metrics hardening.
- M7-M8: rollout baseline and stabilization runbook.
- M9: deferred ArgoCD staging integration.

## Ownership

- Record why key decisions were made.
- Keep rationale tied to current implementation constraints.
- Link to milestone plans for planned design work.

## Update rules

- Use scenario-led prose.
- Keep decisions concise and auditable.
- Remove stale or speculative guidance.
