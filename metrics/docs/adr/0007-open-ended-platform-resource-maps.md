# ADR-0007: Open-ended platform resource maps

## Status

Accepted (M2)

## Context

ClusterQueue nominal quota and flavors usage can include GPU and other resource
names beyond CPU and memory. A fixed typed wire shape would require API revisions
when operators add resource groups.

## Decision

- Platform `data.capacity` and `data.allocated` are open string-keyed maps
  (`dict[str, str]`) keyed by Kubernetes resource names (for example `cpu`,
  `memory`, `nvidia.com/gpu`).
- Unit parity per resource name remains mandatory (ADR-0003).

## Consequences

- Clients must tolerate unknown keys and must not assume only CPU/memory.
- Provider formatting rules apply consistently across all keys in both maps.

## References

- [`0003-platform-capacity-allocated-unit-parity.md`](0003-platform-capacity-allocated-unit-parity.md)
