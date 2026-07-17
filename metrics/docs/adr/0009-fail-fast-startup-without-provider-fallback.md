# ADR-0009: Fail-fast startup without provider fallback

## Status

Accepted (M2–M4)

## Context

Silent degradation (node listing, static defaults, or alternate providers) hides
misconfiguration and produces figures that contradict Kueue-backed platform stats.

## Decision

- Active source dependencies are validated during FastAPI lifespan `startup()`.
- When required upstreams are misconfigured or unreachable, the process **refuses
  to serve** rather than falling back to another provider or partial data.
- Inactive configured providers do not run startup checks (ADR-0005).

## Consequences

- Operators detect bad queue lists, RBAC, or API URLs at deploy time.
- Request-time failures map to HTTP errors and telemetry; they do not substitute
  a different metrics source.

## References

- [`../specs.md`](../specs.md)
