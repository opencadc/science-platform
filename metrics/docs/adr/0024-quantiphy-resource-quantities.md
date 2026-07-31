# ADR-0024: Resource quantities parse via quantiphy, not a bespoke model

## Status

Accepted (2026-07-31)

## Context

`metrics.quantity` reimplemented Kubernetes quantity parsing with exact
`Decimal` arithmetic, trap contexts, and its own suffix grammar — roughly 150
lines of numeric code to maintain for values the API only ever renders to a
handful of decimal places.

## Decision

- Quantities parse through `quantiphy` (`Quantity(raw, binary=True)`): SI and
  binary suffixes and scientific notation, validated at 100% agreement against
  the previous parser on a corpus of Kubernetes quantity forms.
- Values are floats. The public contract is agreement to the 6 decimal places
  the API formats (`format_resource_amount`), not bit-exactness; unit choices
  from ADR-0002 (cores, GiB, base units) are unchanged.
- Parsing, formatting, and aggregation guards (non-negative, finite, below
  2**63 base units) live in the provider (`metrics.providers.kueue`); the
  standalone `metrics.quantity` module is deleted.

## Consequences

- quantiphy accepts slightly more than the Kubernetes grammar (for example
  `1e` as `1`); inputs come from the Kubernetes API, which enforces its own
  syntax upstream, and the provider still rejects negatives, non-finite
  values, whitespace, and overflow.
- Fractional totals may carry float representation noise internally; the
  6-decimal formatter absorbs it (`0.1 + 0.1 + 0.1` renders `0.3`).
