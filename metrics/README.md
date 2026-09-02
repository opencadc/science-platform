# CANFAR Science Platform Metrics API

Metrics is one asynchronous FastAPI service. It reads current Kueue state and
Session Jobs, serves a small versioned HTTP contract, and optionally asks an
external Prometheus-compatible system for CPU and memory efficiency. Redis,
Prometheus/Mimir, Kubernetes metrics exporters, and OTLP metrics receivers are
deployment-owned dependencies rather than components of the production chart.

## Authority

- Product design:
  [Confluence API Contract](https://herzberg.atlassian.net/wiki/spaces/C/pages/2690809875/API+Contract)
- Implementation-of-record: [`docs/specs.md`](docs/specs.md) (may lead Confluence)
- Deploy/env: [`docs/environment-contracts.md`](docs/environment-contracts.md)
- Decisions (why): [`docs/adr/README.md`](docs/adr/README.md)
- Glossary: [`CONTEXT.md`](CONTEXT.md)
- Platform labels: [`../skaha/docs/labels.md`](../skaha/docs/labels.md)

## API routes

```text
GET /apis/canfar.net/v1alpha1/metrics/user/{username}
GET /apis/canfar.net/v1alpha1/metrics/community/{community}
GET /apis/canfar.net/v1alpha1/metrics/platform/{platform}
GET /apis/canfar.net/v1alpha1/metrics/session/{id}
GET /healthz
GET /livez
GET /readyz
```

The response is one `canfar.net/v1alpha1` `Metrics` object. Its `spec` echoes
the selected subject and its `status` contains `observedAt`,
`reservingWorkloads`, resources, and exactly one `Ready` plus one `Cached`
condition. Source rules, cache windows, and failure semantics live in
[`docs/specs.md`](docs/specs.md).

## Configuration

Nested settings use `__`, and list values are JSON arrays. At minimum:

```bash
export METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES='["cq-astronomy","cq-physics"]'
export METRICS_PROVIDERS__KUEUE__NAMESPACES='["canfar-workloads","canfar-workloads-extra"]'
export METRICS_CLUSTER_NAME='cluster.example'
export METRICS_REDIS_URL='rediss://redis.example/0'
export METRICS_CACHE__KEY_SECRET='<secret-reference-or-injected-value>'
```

Optional: `METRICS_PROVIDERS__PROMQL__BASE_URL`,
`METRICS_OTEL__METRICS_ENABLED` with `METRICS_OTEL__EXPORTER_OTLP_ENDPOINT`.
Full deploy contract: [`docs/environment-contracts.md`](docs/environment-contracts.md).

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
kube-apiserver, not the Metrics Service. Use Kubernetes Service proxying or
port-forward:

```bash
export METRICS_NAMESPACE='replace-with-metrics-namespace'
export METRICS_SERVICE='replace-with-metrics-service'

kubectl -n "$METRICS_NAMESPACE" port-forward \
  "service/$METRICS_SERVICE" 8000:8000
```

```bash
curl --fail --show-error --silent \
  'http://127.0.0.1:8000/apis/canfar.net/v1alpha1/metrics/user/bob'
curl --fail --show-error --silent \
  'http://127.0.0.1:8000/apis/canfar.net/v1alpha1/metrics/session/<id>'
```

Direct Kueue raw commands remain useful for source inspection:

```bash
kubectl get localqueues.kueue.x-k8s.io -A \
  -l canfar.net/username=bob -o yaml
kubectl get clusterqueues.kueue.x-k8s.io \
  -l canfar.net/community=astronomy -o yaml
```

Operator runbooks: [`docs/runbooks/index.md`](docs/runbooks/index.md).
