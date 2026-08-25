# Design reference

This file captures repository-specific design decisions and tradeoffs.

## Environment naming

Operational environment contracts and roadmap-to-runtime mappings for Metrics
live in `environment-contracts.md` in this directory.

## Current design

See [`docs/adr/README.md`](adr/README.md) for distilled decisions. Summary:

- **Kubernetes-first service contract:** Dev, integration, staging, and
  production run through Kubernetes deployment paths. Docker Compose is not
  part of the supported service contract (see `environment-contracts.md`).
- **Single service process, bounded subject HTTP:** `MetricsRuntime` constructs
  Kueue and Kubernetes providers and owns per-surface cache resources. Platform
  reads Kueue; User and Community phase 1 list Running Pods in configured namespaces.
- **Truthful provider configuration:** `sources.platform` accepts only `kueue`;
  `sources.user` accepts only `kubernetes`.
- **Server-owned subject selection:** Route values are validated Kubernetes label
  values. The provider constructs fixed provenance, exact username or community,
  and Running selectors; callers cannot supply selector syntax. Every namespace
  must succeed.
- **Scheduler request semantics:** User and Community totals sum regular containers and
  restartable sidecars, compare with effective init peaks, then add Pod overhead
  while preserving arbitrary extended resources.
- **Kueue allocated semantics:** Platform `allocated` values come from
  `status.flavorsUsage.resources[].total`. Kueue total already includes
  borrowed quota, so borrowed values are not added again.
- **Honest quantity semantics:** Kueue quantity strings are parsed with
  quantiphy (ADR-0001), summed, and formatted as CPU cores or storage Gi to
  6 decimal places. Invalid or overflowing upstream quantities fail closed
  instead of becoming zero.
- **Pydantic-first contracts:** `Settings` and HTTP schemas use Pydantic with
  `pydantic-settings` env parsing (nested `METRICS_*` keys); configuration is
  environment-only (ADR-0001).
- **One application lifecycle:** Production-built and test-injected runtimes
  execute the same FastAPI lifespan. Package imports create no settings,
  application, runtime, client, or exporter.

## Ownership

- Record why key decisions were made.
- Keep rationale tied to current implementation constraints.
- Link to ADRs for durable design choices.

## Update rules

- Use scenario-led prose.
- Keep decisions concise and auditable.
- Remove stale or speculative guidance.
