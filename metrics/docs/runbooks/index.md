# Metrics operator runbooks

These procedures cover the one Metrics service and its external cache. They
do not replace environment deployment procedures or the canonical
[Confluence API Contract](https://herzberg.atlassian.net/wiki/spaces/C/pages/2690809875/API+Contract).

## Start here

| Symptom | Runbook | First safe proof |
| --- | --- | --- |
| `/readyz` fails or reports Redis unavailable | [Redis outage and readiness](redis.md) | `/healthz`, `/livez`, `/readyz`, one report GET, and a bounded Redis `PING` |
| A release or chart change must be backed out | [Rollback and verification](rollback.md) | Confirm the owning release, then prove probes, routes, cache headers, and source output |
| A User or Community report is missing | [Metadata-label requirements](../metadata-labels.md) | Inspect configured namespaces, LocalQueue labels, referenced ClusterQueue, and exact label equality |

## Safety boundaries

- `/healthz` and `/livez` describe process liveness; `/readyz` describes
  coordinated readiness and does not replace a report request.
- Do not use `FLUSHDB`, delete snapshot pointers, or bypass Redis with a source
  read for every request as an outage response.
- Current queue reservations are not measured usage. Optional efficiency is a
  current Prometheus/Mimir ratio and is not a historical series.
- Test fixtures may install Redis, KSM, Prometheus/Mimir, or an OTLP metrics receiver;
  production ownership remains external.

The procedures in this directory are read-only diagnostics unless an explicit
deployment owner authorizes a rollback or external recovery action.
