# ADR-0008: Platform allocated from flavorsUsage

## Status

Accepted (M2)

## Context

Kueue exposes both reservation and usage status. Platform **allocation** must
reflect admitted workload, not reserved-but-not-admitted quota.

## Decision

- Platform **`data.allocated`** aggregates
  `ClusterQueue.status.flavorsUsage.resources[].total` across configured queues.
- Do **not** use `flavorsReservation` for platform allocation.
- This is independent of the borrowed-quota rule in ADR-0002 (`total` already
  includes borrowed quota; do not add `borrowed` again).

## Consequences

- Reviewers must not conflate reservation fields with the platform allocation
  contract.

## References

- [`0002-kueue-allocated-uses-flavors-total-only.md`](0002-kueue-allocated-uses-flavors-total-only.md)
