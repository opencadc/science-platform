# ADR-0010: Configuration precedence and YAML contract

## Status

Accepted (M3–M4)

## Context

Operators configure Metrics through Helm values, optional mounted YAML, and
direct env overrides. Precedence and secret handling must be predictable.

## Decision

- Merge order: **defaults → optional YAML file → `METRICS_*` environment**, with
  **environment winning** for the same field.
- Default YAML path: `/etc/canfar/metrics/config.yaml`. When present and
  non-empty, the document must include a top-level `metrics:` mapping.
- Missing YAML is allowed unless `METRICS_REQUIRE_CONFIG_FILE=true`.
- Nested env keys use `METRICS_` + `__` delimiters. List-like nested fields
  must be JSON array strings (not comma-separated plain strings).
- **Secrets must not live in ConfigMap-backed YAML.** Use `token_file` /
  `ca_file` paths or env sourced from Kubernetes secrets.

## Consequences

- GitOps values typically render env; file-based config is optional per
  environment.
- Legacy flat aliases (`METRICS_KUEUE_*`, `KUEUE_METRICS_*`) are removed; use
  nested `METRICS_PROVIDERS__*` keys only.

## References

- [`../environment-contracts.md`](../environment-contracts.md)
- [`../examples/metrics.config.yaml`](../examples/metrics.config.yaml)
