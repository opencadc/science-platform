# ADR-0010: Environment-only configuration

## Status

Accepted (M3–M4); amended 2026-07-31 — the optional YAML file source was
removed with the over-engineering audit (nothing deployed mounted one).

## Context

Operators configure Metrics through Helm values rendering environment
variables. The earlier optional mounted-YAML source added a custom
pydantic-settings source, a parallel precedence contract, and a pyyaml
dependency that no deployment used.

## Decision

- Configuration is **environment-only**: defaults, then `METRICS_*`
  environment variables (environment wins), then Kubernetes secret file
  sources when present.
- Nested env keys use `METRICS_` + `__` delimiters. List-like nested fields
  must be JSON array strings (not comma-separated plain strings).
- **Secrets must not live in ConfigMaps.** Use env sourced from Kubernetes
  secrets; Kubernetes API credentials are not configuration at all (kr8s
  discovery, ADR-0023).
- Legacy flat aliases (`METRICS_KUEUE_*`, `KUEUE_METRICS_*`) remain removed;
  use nested `METRICS_PROVIDERS__*` keys only.

## Consequences

- GitOps values render env vars; there is no file-based configuration path.
- Reintroducing a file source is a new ADR, not a default.

## References

- [`../environment-contracts.md`](../environment-contracts.md)
