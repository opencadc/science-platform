# Metrics development and testing

Metrics is developed as one asynchronous FastAPI service. Kubernetes-first
tests use kind, Helm, Kueue, and the configured external-service interfaces.
The production chart does not install Redis, KSM, Prometheus/Mimir, or an OTLP
metrics receiver; a disposable test profile may install them for integration
checks.

## Prerequisites

- Docker
- kind
- kubectl
- Helm
- Python 3.13 and `uv`

Run commands from `metrics/`. Use the repository's pinned kind/Kubernetes and
Kueue versions for CI-equivalent checks.

## Fast local loop

```bash
uv sync --group dev
uv run ruff check src tests
uv run pytest -m "not integration"
```

The application test suite should inject Kueue, Redis, and optional PromQL
fakes. It must exercise the same service interfaces as the deployed process:
fresh hits, stale refresh, cold single-flight, subject isolation, source
errors, 404 subjects, and optional `PartialData` responses.

## Kubernetes smoke loop

Use the installed project lifecycle for the reusable `kind-metrics` cluster:

```bash
uv run metrics-dev up
uv run metrics-dev smoke
```

The deployed smoke must configure:

```text
METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES='["cq-astronomy","cq-physics"]'
METRICS_PROVIDERS__KUEUE__NAMESPACES='["canfar-workloads"]'
```

Fixtures should create LocalQueues with exact User and Community labels,
ClusterQueues with exact Community labels, and workloads that exercise pending
and reserving states. Each LocalQueue's Community label must equal its
referenced ClusterQueue's label. Fixture names are not identity sources.

These fixtures represent the output of a trusted platform provisioning or
admission path. The Metrics and Skaha charts do not deploy that path. Before
production use, an independently approved component must create the
per-user LocalQueue, hand its exact name to the submitter, validate
`LocalQueue -> configured ClusterQueue -> Community`, reserve the attribution
labels from user override, and stamp only configured-ClusterQueue workloads.

Each submitting Job or other supported workload object (not a
generated/direct Kueue Workload CR) must identify its LocalQueue with the
queue-name metadata label:

```yaml
metadata:
  labels:
    kueue.x-k8s.io/queue-name: lq-bob-astronomy
    canfar.net/username: bob
    canfar.net/community: astronomy
spec:
  template:
    metadata:
      labels:
        canfar.net/username: bob
        canfar.net/community: astronomy
```

Every submitted Job or supported workload object must carry the
`kueue.x-k8s.io/queue-name` label for its User-specific LocalQueue. A
generated/direct Kueue Workload CR is distinct: its authoritative queue
selection is `.spec.queueName`, a field rather than a metadata label. Its
top-level `canfar.net/username` and `canfar.net/community` labels are optional
mirrors. When `METRICS_PROVIDERS__PROMQL__BASE_URL` is present, every
`.spec.podSets[].template.metadata.labels` entry and the submitting metadata
must carry the authoritative two `canfar.net` labels. An admitted Pod may additionally receive
`kueue.x-k8s.io/local-queue-name` as corroborating metadata; PromQL attribution
still uses the `canfar` labels.

For the optional efficiency gate, add disposable KSM and Prometheus/Mimir
fixtures, enable the KSM label allowlist, and add the stable `cluster` label to
every ingested series used by the fixed query. `external_labels.cluster` is
appropriate on the remote-write path to Mimir but is insufficient by itself
for a local Prometheus query. The fixture must propagate authoritative labels
to Jobs and Pod templates so recreated Running Pods retain attribution. See
[`../../skaha/docs/labels.md`](../../skaha/docs/labels.md).

## Read-only Kueue inspection

Use these commands to inspect the exact source objects:

```bash
kubectl --context kind-metrics get localqueues.kueue.x-k8s.io -A \
  -l canfar.net/username=bob -o yaml
kubectl --context kind-metrics get clusterqueues.kueue.x-k8s.io \
  -l canfar.net/community=astronomy -o yaml
kubectl --context kind-metrics get \
  localqueues.kueue.x-k8s.io -n canfar-workloads \
  -l canfar.net/username=bob \
  -o jsonpath='{range .items[*]}{.metadata.namespace}{"/"}{.metadata.name}{"\n"}{end}'
kubectl --context kind-metrics get --raw \
  '/apis/kueue.x-k8s.io/v1beta2/namespaces/canfar-workloads/localqueues?labelSelector=canfar.net%2Fusername%3Dbob'
kubectl --context kind-metrics get --raw \
  '/apis/kueue.x-k8s.io/v1beta2/clusterqueues?labelSelector=canfar.net%2Fcommunity%3Dastronomy'
```

Inspect `status.flavorsReservation`, `status.reservingWorkloads`, and the
referenced ClusterQueue. `pendingWorkloads` is waiting and is not the public
Metrics count.

## Deployment boundary

The Helm smoke may create disposable support resources inside the test
namespace. Production deployment must provide external references for:

- one shared Redis cache;
- optional Prometheus/Mimir current-resource metrics; and
- optional OTLP/HTTP application-state metrics.

The Metrics chart owns only the API deployment, Service, RBAC, probes, and
configuration references. It must not create a producer, accounting store,
Cohort source, KSM, Prometheus, Mimir, or Collector.

## Manual OTLP metrics smoke

Run this from `metrics/` after the disposable stack is available:

```bash
UV_CACHE_DIR=/tmp/canfar-uv-cache bash scripts/precommit-otel-smoke.sh
```

The script first proves the current context is the exact `kind-metrics` target,
then restarts the disposable Collector and API, flushes Redis, and makes real
`/readyz`, Platform, User, and Community API requests. The Collector has one
metrics pipeline and its file exporter is the evidence source. The smoke
requires the application instruments emitted by that healthy startup and those
Redis-backed cold reads:

- `canfar.metrics.cache.lookups`, `canfar.metrics.cache.age`,
  `canfar.metrics.cache.leases`, and `canfar.metrics.cache.fill.duration`;
- `canfar.metrics.provider.duration`;
- `canfar.metrics.redis.duration` and `canfar.metrics.redis.health`; and
- `canfar.metrics.readiness` and `canfar.metrics.lifecycle.duration`.

The recorder also declares compute-duration and provider-error instruments, but
the current production request path does not record them, so this healthy-path
smoke does not require them. The privacy proof rejects fixture User/Community
identities and selectors, opaque Redis key markers, response payload fields and
values, and any missing application metric evidence. It does not require HTTP
request auto-instrumentation. Port-forward processes and temporary evidence are
cleaned up on exit; use the lifecycle commands above to manage the cluster.

## Retired accounting profile migration

The retired accounting profile was applied directly with `kubectl`, outside
the Metrics Helm release. Helm cannot prune resources applied outside the
release because they lack Helm ownership/release tracking. The lifecycle
cleanup is bounded to the exact accounting label. Inspect first, then delete
only these resources if a manual cleanup is required:

The Role/RoleBinding inspect/delete pair must be repeated for every
`METRICS_PROVIDERS__KUEUE__NAMESPACES` entry; `canfar-workloads` and
`canfar-workloads-secondary` are only the disposable fixture's configured
examples.

```bash
kubectl --context kind-metrics --namespace metrics get deployment,service,configmap,serviceaccount -l metrics.canfar.net/profile=accounting -o yaml
kubectl --context kind-metrics --namespace metrics delete deployment,service,configmap,serviceaccount -l metrics.canfar.net/profile=accounting --ignore-not-found --wait

kubectl --context kind-metrics --namespace canfar-workloads get role,rolebinding -l metrics.canfar.net/profile=accounting -o yaml
kubectl --context kind-metrics --namespace canfar-workloads delete role,rolebinding -l metrics.canfar.net/profile=accounting --ignore-not-found

kubectl --context kind-metrics --namespace canfar-workloads-secondary get role,rolebinding -l metrics.canfar.net/profile=accounting -o yaml
kubectl --context kind-metrics --namespace canfar-workloads-secondary delete role,rolebinding -l metrics.canfar.net/profile=accounting --ignore-not-found

kubectl --context kind-metrics get clusterrole,clusterrolebinding -l metrics.canfar.net/profile=accounting -o yaml
kubectl --context kind-metrics delete clusterrole,clusterrolebinding -l metrics.canfar.net/profile=accounting --ignore-not-found
```

## Teardown

Use the lifecycle's non-destructive stop/reset commands for local iteration.
Only the explicit cluster-destroy command may delete the disposable kind
cluster. Never use local fixture cleanup commands as production rollback or
Redis recovery instructions.

## Related documentation

- [`README.md`](../README.md)
- [`specs.md`](specs.md)
- [`../../skaha/docs/labels.md`](../../skaha/docs/labels.md)
- [`environment-contracts.md`](environment-contracts.md)
- [`adr/README.md`](adr/README.md)
