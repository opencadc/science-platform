# Design reference

This file captures repository-specific design decisions and tradeoffs.

## Environment naming

Operational environment contracts and roadmap-to-runtime mappings for Metrics
live in `environment-contracts.md` in this directory.

## Current design (post M4)

See [`docs/adr/README.md`](adr/README.md) for distilled decisions. Summary:

- **Kubernetes-first service contract:** Dev, integration, staging, and
  production run through Kubernetes deployment paths. Docker Compose is not
  part of the supported service contract (see `environment-contracts.md`).
- **Single service process, platform-only HTTP:** `MetricsRuntime` composes the
  active Kueue provider from `core/registry.py`, owns provider
  lifecycle and cache resources, and exposes platform reads to versioned
  routes. Kueue reads Kubernetes through kr8s (ADR-0023). M4 serves only
  `GET /api/v1/metrics/platform` and `GET /healthz`.
- **Truthful provider configuration:** Kueue is the only configured provider
  and the only accepted `sources.platform` value.
- **Truthful platform capability:** `Provider` owns only identity, lifecycle,
  and cache fingerprinting. `PlatformMetrics` owns the asynchronous platform
  read, which `KueueProvider` implements directly. The binder checks that
  capability without separate scope metadata.
- **Kueue allocated semantics:** Platform `allocated` values come from
  `status.flavorsUsage.resources[].total`. Kueue total already includes
  borrowed quota, so borrowed values are not added again.
- **Honest quantity semantics:** Kueue quantity strings are parsed with
  quantiphy (ADR-0024), summed, and formatted as CPU cores or storage Gi to
  6 decimal places. Invalid or overflowing upstream quantities fail closed
  instead of becoming zero.
- **Pydantic-first contracts:** `Settings` and HTTP schemas use Pydantic with
  `pydantic-settings` env parsing (nested `METRICS_*` keys) and optional YAML
  under `/etc/canfar/metrics/config.yaml` (see `core/yaml_config.py`).
- **One application lifecycle:** Production-built and test-injected runtimes
  execute the same FastAPI lifespan. Package imports create no settings,
  application, runtime, client, or exporter.

## Design mapping

Milestone-to-decision mapping (M3–M11): [`docs/adr/README.md`](adr/README.md).

## Ownership

- Record why key decisions were made.
- Keep rationale tied to current implementation constraints.
- Link to ADRs for durable design choices.

## Update rules

- Use scenario-led prose.
- Keep decisions concise and auditable.
- Remove stale or speculative guidance.
