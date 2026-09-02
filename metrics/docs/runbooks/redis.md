# Redis outage and readiness

Redis is the shared cache and cross-replica single-flight boundary. The
Metrics chart references an externally managed Redis; it does not own Redis
persistence, replication, backups, or upgrades. Cache windows and HTTP
semantics live in [`../specs.md`](../specs.md).

## Symptoms

| Observation | Meaning | Action |
| --- | --- | --- |
| `/healthz` or `/livez` is 200 and `/readyz` is 503 | Process is alive but a required dependency or coordinated state is unavailable | Inspect Redis connectivity and the last report outcome |
| Report is 503 with `Retry-After` | No serviceable snapshot and no successful fill (`metrics_cache_unavailable`) | Restore Redis/Kueue access, then retry |
| Report is 200 with stale cache provenance | A known complete snapshot remains serviceable (`Ready=False`/`StaleData`) | Restore the dependency; do not flush the cache |
| Redis is reachable but readiness remains false | Readiness records coordinated state, not only a socket probe | Make one safe report GET and re-check `/readyz` |

## Safe diagnostics

```bash
export METRICS_BASE_URL='<metrics-base-url>'
export METRICS_NAMESPACE='<metrics-namespace>'
export METRICS_USER='<canonical-username>'
export METRICS_SESSION='<canonical-session-id>'
export METRICS_REDIS_URL='<injected-redis-url>'

curl -sS -i "$METRICS_BASE_URL/healthz"
curl -sS -i "$METRICS_BASE_URL/livez"
curl -sS -i "$METRICS_BASE_URL/readyz"
curl -sS -i \
  "$METRICS_BASE_URL/apis/canfar.net/v1alpha1/metrics/user/$METRICS_USER"
curl -sS -i \
  "$METRICS_BASE_URL/apis/canfar.net/v1alpha1/metrics/session/$METRICS_SESSION"
redis-cli -u "$METRICS_REDIS_URL" PING
```

Inspect `Ready`, `Cached`, `Age`, and `Cache-Status`. Do not paste a
credential-bearing Redis URL into logs or tickets.

For Kubernetes, inspect only the application resources and events:

```bash
kubectl -n "$METRICS_NAMESPACE" get pods \
  -l app.kubernetes.io/name=metrics-api -o wide
kubectl -n "$METRICS_NAMESPACE" logs \
  -l app.kubernetes.io/name=metrics-api --all-containers=true --tail=100
kubectl -n "$METRICS_NAMESPACE" get events --sort-by=.lastTimestamp
```

Managed Redis may reject `INFO` or `CONFIG`; that is an authorization result,
not proof of outage. Use the provider's approved read-only inspection path.

## Recovery

1. Correct DNS, network policy, credentials, TLS, endpoint, or external Redis
   health using the environment owner’s procedure.
2. Preserve existing snapshots. Do not run `FLUSHDB`, delete lease keys, or
   edit cache payloads as a first response.
3. Make one report GET after Redis is reachable.
4. Confirm `/healthz` and `/livez` are 200, `/readyz` reflects the successful
   coordinated read, and the affected route returns a complete report.

If Kueue is still unavailable, follow the source and RBAC diagnostics in
[`../../../skaha/docs/labels.md`](../../../skaha/docs/labels.md) and
[`dev-setup.md`](../dev-setup.md). When
`METRICS_PROVIDERS__PROMQL__BASE_URL` is present, an optional PromQL failure
should appear as HTTP 200 with `PartialData`; it is not a Redis recovery
condition.

## Local test note

A disposable test profile may use a local Redis and reset it between tests.
That behavior is test setup only and is not an operational recovery command.
