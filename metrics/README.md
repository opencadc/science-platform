# CANFAR Science Platform Metrics API

Metrics is one asynchronous FastAPI service. It reads current Kueue state,
serves a small versioned HTTP contract, and optionally asks an external
Prometheus-compatible system for current CPU and memory efficiency. Redis,
Prometheus/Mimir, Kubernetes metrics exporters, and OTLP metrics receivers are
deployment-owned dependencies rather than components of the production chart.

Confluence remains canonical for the approved product design:
[API Contract](https://herzberg.atlassian.net/wiki/spaces/C/pages/2690809875/API+Contract),
[Technical Design](https://herzberg.atlassian.net/wiki/spaces/C/pages/2690809867/Technical+Design),
and [Implementation Specifications](https://herzberg.atlassian.net/wiki/spaces/C/pages/2690449417/Implementation+Specifications).
The Git implementation contract is summarized in [`docs/specs.md`](docs/specs.md).
The required queue and optional efficiency metadata contract is
[`docs/metadata-labels.md`](docs/metadata-labels.md).

## API routes

```text
GET /apis/canfar.net/v1alpha1/metrics/user/{username}
GET /apis/canfar.net/v1alpha1/metrics/community/{community}
GET /apis/canfar.net/v1alpha1/metrics/platform/{platform}
GET /healthz
GET /livez
GET /readyz
```

The response is one `canfar.net/v1alpha1` `Metrics` object. Its `spec` echoes
the selected subject and its `status` contains `observedAt`,
`reservingWorkloads`, resources, and exactly one `Ready` plus one `Cached`
condition.

## Kueue sources

All queue identity and optional PromQL attribution labels must follow
[`docs/metadata-labels.md`](docs/metadata-labels.md).

| Surface | Selection | Values |
| --- | --- | --- |
| User | LocalQueues in every configured namespace with `canfar.net/username=<username>`; each queue must reference a configured ClusterQueue | Sum `flavorsReservation.resources[].total` and `reservingWorkloads` across all matches |
| Community | Configured ClusterQueues with `canfar.net/community=<community>` | Sum `flavorsReservation.resources[].total` and `reservingWorkloads` across all matches |
| Platform | Every ClusterQueue named in `METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES` | Capacity from nominal quota, allocation from `flavorsUsage.resources[].total`, and summed `reservingWorkloads` |

User and Community reports expose `resources[].requests`. Platform reports
expose `resources[].capacity` and `resources[].allocated` in matching units.
All surfaces expose `reservingWorkloads`. Cohorts are not read or aggregated.

A User with no matching LocalQueue returns 404. A Community with no configured
ClusterQueue carrying the requested label returns 404. A configured queue that
is missing, inaccessible, or references an unconfigured ClusterQueue makes the
affected primary read unavailable; Metrics never silently turns that failure
into zero.

## Optional efficiency

Set `METRICS_PROVIDERS__PROMQL__BASE_URL` to a Prometheus/Mimir endpoint to
activate fixed, server-owned instant PromQL. If the variable is absent,
efficiency is disabled. There is no separate PromQL enable flag. The provider
reports current CPU and memory efficiency for Running Pods:

```text
efficiency = current usage / current resource requests
```

User queries select `label_canfar_net_username`; Community queries select
`label_canfar_net_community`; Platform queries cover the configured platform
population. The query catalog also requires the Running-Pod state. Callers
cannot provide PromQL, label selectors, endpoints, or headers. Efficiency is
omitted when no endpoint is configured, when its denominator is zero, or when
the optional backend fails.

If Kueue data is available but an efficiency query fails while
`METRICS_PROVIDERS__PROMQL__BASE_URL` is present, the API returns HTTP 200 with
the primary report and `Ready=False`/`PartialData`. If the primary Kueue read
fails and no serviceable cache exists, it returns HTTP 503.

## Cache and concurrency

One shared external Redis stores authenticated, versioned snapshots and
distributed leases. The service uses one single-flight identity per surface
and subject, so concurrent requests for Bob do not issue duplicate Kueue or
PromQL reads; Alice and unrelated Communities proceed in parallel.
Redis is mandatory at runtime; there is no deployable in-memory cache mode.
Missing User and Community subjects use a bounded authenticated Redis terminal
outcome so concurrent callers receive the same 404.

| Surface | Fresh | Serviceable stale | Retained only |
| --- | ---: | ---: | ---: |
| User | 2m | 10m | 15m |
| Community | 2m | 10m | 15m |
| Platform | 5m | 30m | 60m |

Fresh snapshots return immediately. One lease winner refreshes a stale
snapshot while other requests receive the stale snapshot. On a cold or
unserviceable miss, one winner fills Redis and concurrent requests wait for
the published result. Redis is the only shared cache; a process-local task
registry may collapse work within one replica but is not a second data store.

## Configuration

Nested settings use `__`, and list values are JSON arrays:

```bash
export METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES='["cq-astronomy","cq-physics"]'
export METRICS_PROVIDERS__KUEUE__NAMESPACES='["canfar-workloads","canfar-workloads-extra"]'
export METRICS_CLUSTER_NAME='cluster.example'
export METRICS_REDIS_URL='rediss://redis.example/0'
export METRICS_CACHE__KEY_SECRET='<secret-reference-or-injected-value>'
export METRICS_PROVIDERS__PROMQL__BASE_URL='https://mimir.example'
```

The Prometheus/Mimir endpoint is optional and is the sole efficiency activation
switch: setting `BASE_URL` enables PromQL, while omitting it disables
efficiency. The Redis URL, cache key secret, Kubernetes access, and optional
OTLP metrics endpoint are deployment configuration; credentials are supplied
through the environment/Secret mechanism. Each configured ClusterQueue maps
to one Community, and the configured list is the complete Platform boundary.

## Telemetry and deployment

Metrics may export application-state OTLP metrics to an external OTLP/HTTP
endpoint. It does not export OTLP traces or logs. The production chart owns
only the Metrics Deployment, Service, RBAC, and configuration references. It
does not create Redis, KSM, Prometheus/Mimir, or an OTLP Collector. A
disposable test profile may install those dependencies to exercise the
integration contract.

## Development

```bash
uv sync --group dev
uv run ruff check src tests
uv run pytest -m "not integration"
```

Cluster-backed development uses kind, Helm, Kueue, and the supported lifecycle
described in [`docs/dev-setup.md`](docs/dev-setup.md).

### Inspect a running Metrics API

The chart does not install a Kubernetes aggregated `APIService`. Therefore a
bare command such as
`kubectl get --raw '/apis/canfar.net/v1alpha1/metrics/user/bob'` queries
kube-apiserver, not the Metrics Service. Use Kubernetes Service proxying:

```bash
export METRICS_NAMESPACE='replace-with-metrics-namespace'
export METRICS_SERVICE='replace-with-metrics-service'

kubectl get --raw \
  "/api/v1/namespaces/${METRICS_NAMESPACE}/services/${METRICS_SERVICE}:http/proxy/apis/canfar.net/v1alpha1/metrics/user/bob"
kubectl get --raw \
  "/api/v1/namespaces/${METRICS_NAMESPACE}/services/${METRICS_SERVICE}:http/proxy/apis/canfar.net/v1alpha1/metrics/community/astronomy"
kubectl get --raw \
  "/api/v1/namespaces/${METRICS_NAMESPACE}/services/${METRICS_SERVICE}:http/proxy/apis/canfar.net/v1alpha1/metrics/platform/canfar"
```

Alternatively, run this in one terminal:

```bash
kubectl -n "$METRICS_NAMESPACE" port-forward \
  "service/$METRICS_SERVICE" 8000:8000
```

Then query the forwarded service from another terminal:

```bash
curl --fail --show-error --silent \
  'http://127.0.0.1:8000/apis/canfar.net/v1alpha1/metrics/user/bob'
```

Direct Kueue raw commands are different: these intentionally query the Kueue
API registered with kube-apiserver and remain useful for source inspection:

```bash
kubectl get localqueues.kueue.x-k8s.io -A \
  -l canfar.net/username=bob -o yaml
kubectl get clusterqueues.kueue.x-k8s.io \
  -l canfar.net/community=astronomy -o yaml
kubectl get --raw \
  '/apis/kueue.x-k8s.io/v1beta2/namespaces/canfar-workloads/localqueues?labelSelector=canfar.net%2Fusername%3Dbob'
kubectl get --raw \
  '/apis/kueue.x-k8s.io/v1beta2/clusterqueues?labelSelector=canfar.net%2Fcommunity%3Dastronomy'
```

The local profile may provide disposable Redis, Prometheus/Mimir, KSM, and an
OTLP metrics receiver for tests. None of those fixtures defines the production
architecture.
