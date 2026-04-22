# Design reference

This file captures repository-specific design decisions and tradeoffs. It is
not used for generic harness instructions.

## Environment naming

Operational environment contracts and roadmap-to-runtime mappings for Metrics
live in `environment-contracts.md` in this directory.

## Current versus target design

Current implementation details live in `docs/architecture.md` and code under
`src/metrics/`. The bullets below describe target design direction from M3
onward.

- **Kubernetes-first service contract:** Dev, integration, staging, and
  production all run through Kubernetes deployment paths. Docker Compose is not
  part of the supported service contract.
- **Single service process with configured source composition:** The service
  composes metrics from configured source adapters instead of maintaining
  mutually exclusive provider modes.
- **Supported source set after cutover:** Kueue, Prometheus, and kube-metrics
  are the supported providers. Static and node providers are removed in M3 code
  scope.
- **Pydantic-first contracts:** Settings and data contracts use Pydantic models
  and `pydantic-settings`; dataclasses are not used for runtime/API contracts.

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
