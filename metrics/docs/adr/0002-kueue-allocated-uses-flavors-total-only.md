# ADR-0002: Kueue allocated uses flavors total only

## Status

Accepted

## Context

Kueue `status.flavorsUsage.resources[].total` already includes borrowed quota.
Adding `borrowed` separately inflates **platform allocation**.

## Decision

- Platform **`data.allocated`** sums **`status.flavorsUsage.resources[].total`**
  across configured ClusterQueues.
- Do **not** add `borrowed` values on top of `total`.

## Consequences

- Tests and reviewers must treat double-counting as a defect, not an alternate
  interpretation.

## References

- [`../specs.md`](../specs.md)
- `src/metrics/providers/kueue.py`
