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
  active platform source from `core/provider_registry.py`, owns upstream
  `httpx.AsyncClient` instances and cache backends, and exposes platform reads
  to versioned routes. M4 serves only `GET /api/v1/metrics/platform` and
  `GET /healthz`; user/session metrics are out of scope until later milestones.
- **Reserved providers:** Prometheus and kube provider types exist for typed
  configuration; M4 does not open unused upstream HTTP clients for them.
- **Kueue allocated semantics:** Platform `allocated` values come from
  `status.flavorsUsage.resources[].total`. Kueue total already includes
  borrowed quota, so borrowed values are not added again.
- **Pydantic-first contracts:** `Settings` and HTTP schemas use Pydantic with
  `pydantic-settings` env parsing (nested `METRICS_*` keys) and optional YAML
  under `/etc/canfar/metrics/config.yaml` (see `core/yaml_config.py`).

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
