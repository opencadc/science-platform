# ADR-0003: Platform capacity and allocated unit parity

## Status

Accepted

## Context

Skaha and Science Portal compare `capacity` and `allocated` without conversion.
Mixed units (millicores vs cores, mismatched memory quantities) are unacceptable
defects.

## Decision

- For each resource name present in `data.capacity`, the same name appears in
  `data.allocated` using the **same unit** (CPU as decimal core counts, memory
  as `Gi` binary quantities, consistent rules for other resources).

## Consequences

- Provider aggregation and formatting must be reviewed together when adding
  resource types.

## References

- [`../specs.md`](../specs.md)
- [`../../CONTEXT.md`](../../CONTEXT.md)
