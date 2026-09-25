# ADR-0012: Latched readiness

## Status

Accepted.

## Context

`/readyz` decides whether a pod receives traffic and whether a rolling update
may retire an old pod. Every replica shares one Redis and one Kubernetes API.
A readiness check that follows those shared dependencies on every probe
removes all replicas from the Service at the same moment during an outage,
turning stale-but-serviceable reports into connection errors.

## Decision

A pod becomes ready after one validation proves that Redis answers and that
every configured ClusterQueue is readable and well formed. Until then each
probe re-runs one shared, bounded validation and `/readyz` returns 503. After
the first success readiness **latches**: it stays 200 until shutdown.
LocalQueue and Job list access are probed at startup and logged but never gate
readiness. A pod that cannot reach Redis at all during startup exits and is
restarted by Kubernetes instead of waiting unready.

## Consequences

- A new pod with a bad configuration, missing ClusterQueue RBAC, or no Redis
  never becomes ready (it exits or stays unready), so a rollout stops before
  it replaces a working pod.
- A later shared outage degrades reports (stale data, the outage copy, then
  sanitized 503s) instead of emptying the Service.
- Liveness (`/healthz`, `/livez`) only reports that the process serves HTTP.
