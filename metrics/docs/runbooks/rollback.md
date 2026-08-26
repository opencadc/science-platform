# Rollback and verification

This repository owns the Metrics application and its Helm chart. The
environment owner selects the release and performs the actual rollback. Do not
invent a production release name or namespace from the local kind profile.

## Rollback boundary

An application/chart rollback changes image and configuration state. It does
not repair external Redis, Kueue metadata, Prometheus/Mimir attribution, or
RBAC. Preserve external cache data and source evidence while the release is
changed.

For a local disposable environment, use the supported lifecycle documented in
[`../dev-setup.md`](../dev-setup.md). Cluster deletion is a separate,
explicitly destructive action and is not a normal rollback step.

## Verification

Set only values for the environment being inspected:

```bash
export METRICS_BASE_URL='<metrics-base-url>'
export METRICS_NAMESPACE='<metrics-namespace>'
export METRICS_USER='<canonical-username>'
export METRICS_COMMUNITY='<canonical-community>'

kubectl -n "$METRICS_NAMESPACE" get pods \
  -l app.kubernetes.io/name=metrics-api -o wide
curl -sS -i "$METRICS_BASE_URL/healthz"
curl -sS -i "$METRICS_BASE_URL/livez"
curl -sS -i "$METRICS_BASE_URL/readyz"
curl -sS -i \
  "$METRICS_BASE_URL/apis/canfar.net/v1alpha1/metrics/platform/canfar"
curl -sS -i \
  "$METRICS_BASE_URL/apis/canfar.net/v1alpha1/metrics/user/$METRICS_USER"
curl -sS -i \
  "$METRICS_BASE_URL/apis/canfar.net/v1alpha1/metrics/community/$METRICS_COMMUNITY"
```

Confirm:

- the API Pod is Running and its readiness probe reaches `/readyz`;
- the three probes return the expected liveness/readiness status;
- successful reports retain the `Metrics` envelope, `observedAt`,
  `reservingWorkloads`, and exactly one `Ready` plus one `Cached` condition;
- User/Community values are `requests` and optional current `efficiency`;
- Platform values are comparable `capacity` and `allocated` plus optional
  current `efficiency`;
- `Cache-Control: no-store`, `Age`, `Last-Modified`, and `Cache-Status` remain
  present and coherent; and
- a PromQL failure while `METRICS_PROVIDERS__PROMQL__BASE_URL` is present is
  HTTP 200 with `PartialData`, while a primary Kueue failure without a
  serviceable snapshot is HTTP 503.

If a subject unexpectedly returns 404 or a primary source returns 503, inspect
the queue metadata and configured namespace/ClusterQueue lists using
[`metadata-labels.md`](../metadata-labels.md). Do not treat an absent label or
inaccessible queue as zero resources.

## Stop conditions

Stop the rollback declaration if any route has the wrong API envelope, if
resource units differ between Platform `capacity` and `allocated`, if labels
are not exact, or if a dependency failure is silently converted into an empty
report. Classify the failure as release/configuration, Redis, Kueue/RBAC, or
optional Prometheus/Mimir before taking another action.
