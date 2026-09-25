# Redis outage and readiness

Redis is the shared cache and cross-replica single-flight boundary. The
Metrics chart references an externally managed Redis; it does not own Redis
persistence, replication, backups, or upgrades. Cache windows and HTTP
semantics live in [`../specs.md`](../specs.md#cache).

## How an outage looks

Readiness latches ([ADR-0012](../adr/0012-latched-readiness.md)): once a pod
has validated Redis and the configured ClusterQueues, `/readyz` stays 200 until
the pod stops. A Redis outage therefore shows up in reports and logs, not in
readiness.

| Observation | Meaning | Action |
| --- | --- | --- |
| New pods restart (CrashLoopBackOff) while old pods serve | The new pod could not reach Redis during startup | Read its `Redis is unavailable at startup` log line; fix Redis connectivity or credentials |
| New pods stay unready (`/readyz` 503) while old pods serve | The new pod reached Redis but has not validated Redis and the configured ClusterQueues together | Read its `metrics runtime not ready` log line; fix Redis or ClusterQueue RBAC |
| 200 with `Cached=Unknown`/`RedisUnavailable` and `Cache-Status: …; detail="redis-unavailable"` | Redis is unreachable; the replica serves its in-memory copy until the snapshot's Redis TTL would have ended | Restore Redis |
| 503 `ServiceUnavailable` with `Retry-After: 1` | Redis is unreachable and this replica has no copy of the subject | Restore Redis |
| 503 without `Retry-After` | Redis works, but no snapshot exists and the source failed, is cooling down after a failure, or did not answer in time | Follow the `cache fill failed` log line to the source (Kueue, Jobs, RBAC) |
| 500 `InternalError` | An unexpected failure; without a stale snapshot the subject keeps answering 500 until the failure cooldown ends | Follow the `cache fill failed … category=internal` or `Unhandled request failure` log line |
| 200 with `Ready=False`/`StaleData` and `Cached=True`/`StaleHit` | Normal: a stale snapshot is served while one request refreshes it | None, unless it persists past the fresh window; then check for `cache fill failed` |

## Log lines

| Line | Meaning |
| --- | --- |
| `Redis is unavailable at startup error=…` then `Application startup validation failed; see configuration docs` | The pod could not reach Redis while starting and exits |
| `metrics runtime ready` | This pod latched readiness |
| `metrics runtime not ready: dependency validation failed error=…` | A readiness validation failed; the error names exception types, HTTP status, and Metrics' own messages only |
| `User LocalQueue` / `Session Job` / `PromQL efficiency` `access could not be verified at startup error=…` | A startup probe failed; readiness is unaffected, but that surface will likely return 503 or `PartialData` |
| `cache fill failed scope=<surface> category=<category> error=…` | One fill failed; its owner logs it once, and no replica retries until the failure cooldown ends |
| `optional <efficiency\|usage> unavailable scope=<surface> error=…` | An optional read failed; the report is served with `PartialData` |
| `optional <efficiency\|usage> skipped scope=<surface>: fill deadline reached` | At most 0.5 s of the fill budget was left after the primary read; the report is served with `PartialData` |
| `optional pod state unavailable scope=session` | The Session Pod list failed; Job data is served with `PartialData` |
| `request failed status=<code> code=<error code> route=<route template>` | An expected 5xx response; `code` is the internal error code (for example `metrics_cache_unavailable` or `session_metrics_unavailable`). Subjects and paths are never logged |
| `Unhandled request failure error=… method=… route=…` | An unexpected 500 |

## Safe diagnostics

```bash
export METRICS_BASE_URL='<metrics-base-url>'
export METRICS_NAMESPACE='<metrics-namespace>'
# metrics-api chart: app.kubernetes.io/name=metrics-api
# Skaha chart:       app.kubernetes.io/name=skaha-metrics-api
export METRICS_SELECTOR='<pod-label-selector>'
export METRICS_SESSION='<canonical-session-id>'
export METRICS_REDIS_URL='<injected-redis-url>'

curl -sS -i "$METRICS_BASE_URL/healthz"
curl -sS -i "$METRICS_BASE_URL/readyz"
curl -sS -i \
  "$METRICS_BASE_URL/apis/canfar.net/v1alpha1/metrics/platform/canfar"
curl -sS -i \
  "$METRICS_BASE_URL/apis/canfar.net/v1alpha1/metrics/session/$METRICS_SESSION"
redis-cli -u "$METRICS_REDIS_URL" PING
```

Inspect `Ready`, `Cached`, `Age`, and `Cache-Status`. Do not paste a
credential-bearing Redis URL into logs or tickets.

For Kubernetes, inspect only the application resources and events:

```bash
kubectl -n "$METRICS_NAMESPACE" get pods -l "$METRICS_SELECTOR" -o wide
kubectl -n "$METRICS_NAMESPACE" logs -l "$METRICS_SELECTOR" --tail=100
kubectl -n "$METRICS_NAMESPACE" get events --sort-by=.lastTimestamp
```

Managed Redis may reject `INFO` or `CONFIG`; that is an authorization result,
not proof of outage. Use the provider's approved read-only inspection path.
Metrics runs Lua scripts, so Redis must allow `EVAL` and `EVALSHA`.

## Recovery

1. Correct DNS, network policy, credentials, TLS, endpoint, or external Redis
   health using the environment owner's procedure.
2. Do not run `FLUSHDB`, delete lease keys, or edit cache payloads. A snapshot
   lives at most one stale window (60 seconds for Session, 10 minutes for
   Platform), and each lease ends by itself within seconds.
3. Make one report GET after Redis is reachable; it refills the snapshot.
4. Confirm the report returns `Cached=False`/`Refreshed` or a hit, without
   `detail="redis-unavailable"`.

Restarting pods is not a recovery step: a restarted pod cannot start until
Redis answers, while the running pods keep serving what they hold.

If Kueue is still unavailable, follow the source and RBAC diagnostics in
[`../../../skaha/docs/labels.md`](../../../skaha/docs/labels.md) and
[`../dev-setup.md`](../dev-setup.md). When
`METRICS_PROVIDERS__PROMQL__BASE_URL` is present, an optional PromQL failure
appears as HTTP 200 with `PartialData`; it is not a Redis recovery condition.

## Local test note

A disposable test profile may use a local Redis and reset it between tests.
That behavior is test setup only and is not an operational recovery command.
