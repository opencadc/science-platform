# Kind: Metrics API development and testing

Use the installed `metrics-dev` command for the reusable core profile. It owns
the one supported kind cluster (`metrics`) and context (`kind-metrics`), pinned
Kueue, Redis, OpenTelemetry Collector, fixtures, image, and Helm release.

## Prerequisites

Install these tools before running the smoke flow.

- Docker
- kind 0.32.0
- kubectl
- Helm
- Python 3.13 and `uv`

Run from the `metrics/` directory.

The cluster is created from
`kindest/node:v1.33.12@sha256:3f5c8443c620245e4d355cfe09e96a91ead32ceaa569d3f1ca9edf0cb2fe2ff4`;
Kueue is pinned to 0.19.2 and fixtures use its v1beta2 APIs.

## Reusable development loop

Create or converge the core stack, then run the deployed HTTP smoke:

```bash
UV_CACHE_DIR=/tmp/canfar-uv-cache uv run metrics-dev up
UV_CACHE_DIR=/tmp/canfar-uv-cache uv run metrics-dev smoke
```

Time `up` separately when it must pull the pinned kind, Kueue, Redis,
Collector, fixture, or Python images. `smoke` is the warm gate and fails if it
exceeds 120 seconds; its final line reports the measured warm duration.

Other finite lifecycle commands are:

```bash
uv run metrics-dev run
uv run metrics-dev image
uv run metrics-dev fixtures
uv run metrics-dev down
uv run metrics-dev reset
```

`run` writes a temporary, minified kubeconfig containing only `kind-metrics`;
the Helm workload uses only its Kubernetes ServiceAccount. `reset` preserves
the cluster and image cache while recreating fixtures and flushing local Redis.
The core profile does not install Prometheus or Mimir.

The Helm release uses two API replicas for this gate. The warm smoke flushes
Redis and restarts those replicas, then exercises Platform, User, and Community
over a local HTTP socket. It checks cold fill, fresh hit, `If-Modified-Since`
`304`, empty subjects, stale serve during a Kueue permission failure, the
stable fail-closed `Status` response with no snapshot, legacy-route absence,
and a graceful SIGTERM restart with shutdown-log evidence.

Use the optional accounting profile when changing the lifetime source:

```bash
UV_CACHE_DIR=/tmp/canfar-uv-cache uv run metrics-dev up --profile accounting
UV_CACHE_DIR=/tmp/canfar-uv-cache uv run metrics-dev smoke --profile accounting
```

It adds pinned kube-state-metrics and Prometheus workloads plus the
Metrics-owned recording rules and deterministic producer fixture in
`scripts/accounting-profile.yaml`. The default `core` profile remains
unchanged. The accounting smoke reconciles User and Community lifetime fields
with the controlled per-Pod series, recreates the producer and Prometheus data,
and proves a Prometheus outage returns current requests as partial data. Its
separate warm budget is 300 seconds. Running `metrics-dev up` without the
profile removes the optional accounting resources and reconverges core. Mimir
is not part of this local gate; the same provider contract can target Mimir by
setting its server-owned base URL and optional tenant ID.

The compatibility script delegates to the installed command:

```bash
bash scripts/kind-smoke.sh
```

## Context and teardown safety

Every mutating command fails closed unless the exact context is
`kind-metrics` and it resolves to the `metrics` kind cluster. An existing
cluster on a Kubernetes version other than v1.33.12 is rejected; it is never
silently reused or recreated.

Stop the Helm workload while retaining the cluster with `down`. Cluster
deletion requires the exact confirmation:

```bash
uv run metrics-dev destroy --confirm kind-metrics
```

## Contract fixtures

The smoke contract names in `scripts/test-setup.yaml` and chart values remain:

- `default-flavor`
- `cohort-atom`
- `cq-proton`
- `cq-electron`
- `cq-fair`
- `lq-smoke`
- `lq-fair-high`
- `lq-fair-low`
- `integration-idle`

`metrics-dev fixtures` applies `scripts/workload-fixtures.yaml` in condition-
driven phases. `integration-idle` targets `cq-electron`, whose `100m` CPU and
`100Mi` memory nominal quota is smaller than the Job's `200m`/`200Mi` request.
The command requires an admitted Workload and ClusterQueue usage above nominal
quota before continuing.

The separate `cq-fair` scenario uses two equal LocalQueues. The command admits
`fair-warm-high`, waits for non-zero LocalQueue consumed CPU, queues equivalent
high- and low-use contenders, releases the warm Job, and requires
`fair-next-low` to be admitted while `fair-next-high` remains without quota.
The controller uses a one-minute usage half-life and a one-second test sampling
interval from `scripts/kueue-config.yaml`; no fixture assertion depends on a
fixed sleep.

The remaining finite Jobs cover two or more users and communities, Pending and
terminal states, missing and empty subject labels, multiple containers, init
containers, and RuntimeClass Pod overhead. Every normal Job and Pod template
uses the current Skaha labels from `skaha/docs/labels.md`; exclusion controls
omit or empty labels intentionally. Borrowed, Pending, and fair-share state is
fixture evidence and does not expand the Metrics API.

## Troubleshooting

- Image not found (`ErrImageNeverPull`): run `uv run metrics-dev up` again.
- API startup failure: check logs with
  `kubectl --context kind-metrics -n metrics logs deploy/metrics-api-metrics-api --tail=200`.
- Workload not admitted: check Kueue status and fixture objects:
  `kubectl --context kind-metrics get clusterqueue` and
  `kubectl --context kind-metrics -n canfar-workloads get localqueue,workload`.
- Redis cache data stale during iterative tests:
  `kubectl --context kind-metrics exec -n metrics deploy/metrics-api-redis -- redis-cli FLUSHDB`.
- Smoke fails after interrupting the stale/error scenario: rerun
  `uv run metrics-dev image` to reconcile the chart-owned ClusterRoleBinding.
- Warm smoke exceeds 120 seconds: inspect Pod restarts and local CPU pressure;
  image and prerequisite pulls belong to the separately timed `metrics-dev up`.
- Accounting smoke exceeds 300 seconds: inspect producer and Prometheus
  rollouts separately from the core two-minute gate.

## Related files

- `scripts/kind-smoke.sh`
- `src/metrics/dev/stack.py`
- `scripts/kind-values.yaml`
- `scripts/test-setup.yaml`
- `../.github/workflows/ci.metrics.yml`
