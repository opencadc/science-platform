# ADR-0018: List-on-request Kubernetes reads for quota

## Status

Proposed (M5 — not yet implemented)

## Context

Informer/watch indexes reduce API load but add operational complexity and
backpressure controls. M5 needs a correct quota contract first.

## Decision

- Interactive quota is served via **namespace-scoped Pod list + label selectors**
  on each request (plus application cache per ADR-0017).
- Watch-backed indexes and informer synchronization are **deferred** to a later
  milestone.
- Provider telemetry may count skipped Pods; details stay out of API responses.

## Consequences

- Quota latency scales with Pod list size; cache TTL (2s default) bounds repeat
  load.
- Future optimization must not change the public InteractiveQuota contract.
