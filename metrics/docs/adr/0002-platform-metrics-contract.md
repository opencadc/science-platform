# ADR-0002: Platform metrics contract (Kueue ClusterQueues)

## Status

Accepted (M2–M4).
Consolidates ADR-0002 (allocated uses flavors total only), ADR-0003
(capacity/allocated unit parity), ADR-0006 (ClusterQueue-only), ADR-0007
(open-ended resource maps), and ADR-0008 (allocated from flavorsUsage).

## Context

Skaha and Science Portal compare `capacity` and `allocated` without conversion,
operators control aggregation scope through configured queue lists, and Kueue
exposes several usage-like status fields that invite double counting or
reservation/usage confusion.

## Decision

- **Scope.** Platform metrics (`GET /api/v1/metrics/platform`) aggregate
  **configured ClusterQueues only** (`METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES`).
  Kueue **Cohort** objects are not part of configuration or aggregation.
- **Open-ended maps.** `data.capacity` and `data.allocated` are open
  string-keyed maps (`dict[str, str]`) keyed by Kubernetes resource names
  (`cpu`, `memory`, `nvidia.com/gpu`, …). Clients must tolerate unknown keys.
- **Unit parity.** Every resource name in `capacity` appears in `allocated`
  with the **same unit**: CPU as decimal cores, memory and ephemeral-storage
  as `Gi` binary quantities, other resources in base units. Formatting rules
  apply identically to both maps.
- **Allocated semantics.** `data.allocated` sums
  `ClusterQueue.status.flavorsUsage.resources[].total` across configured
  queues. `total` already includes borrowed quota — do **not** add
  `borrowed` on top — and `flavorsReservation` (reserved, not admitted) is
  **not** allocation.
- **Invalid data fails the read.** Missing, malformed, negative, non-finite,
  and overflowing quantities fail the provider read rather than becoming
  zero. Quantity parsing and precision follow ADR-0024.

## Consequences

- Double-counting borrowed quota or reading reservation fields is a defect,
  not an alternate interpretation.
- Operator queue lists in Helm/GitOps values define the platform metrics
  scope.
- Provider aggregation and formatting must be reviewed together when adding
  resource types; a new resource name needs no API revision.

## References

- [`../specs.md`](../specs.md)
- [`0024-quantiphy-resource-quantities.md`](0024-quantiphy-resource-quantities.md)
- `src/metrics/providers/kueue.py`
