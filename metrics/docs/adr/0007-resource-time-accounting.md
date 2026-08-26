# ADR-0007: Resource-time accounting

## Status

Superseded by [ADR-0010](0010-simple-kueue-metrics-service.md).

The former lifetime-accounting proposal is retired. Metrics does not own a
producer, checkpoints, a usage-history series, or an accounting Redis. Current
efficiency is optional and comes from fixed PromQL against an external
Prometheus/Mimir system; Kueue reservations and current Running-Pod efficiency
remain distinct values. This file is retained only so the rejected historical
direction is discoverable and cannot be mistaken for an active contract.
