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
- `cq-proton`
- `cq-electron`
- `lq-smoke`
- `integration-idle`

The sample workload targets `cq-electron`, which has `100m` CPU and `100Mi`
memory nominal quota. The workload requests `200m` CPU and `200Mi` memory, so
the smoke test verifies admitted usage totals in platform `allocated`. Borrowed
and lending response-field expansion is out of scope for this delivery.

## Troubleshooting

- Image not found (`ErrImageNeverPull`): run `uv run metrics-dev up` again.
- API startup failure: check logs with
  `kubectl --context kind-metrics -n metrics logs deploy/metrics-api-metrics-api --tail=200`.
- Workload not admitted: check Kueue status and fixture objects:
  `kubectl --context kind-metrics get clusterqueue` and
  `kubectl --context kind-metrics -n metrics get localqueue,workload`.
- Redis cache data stale during iterative tests:
  `kubectl --context kind-metrics exec -n metrics deploy/metrics-api-redis -- redis-cli FLUSHDB`.

## Related files

- `scripts/kind-smoke.sh`
- `src/metrics/dev/stack.py`
- `scripts/kind-values.yaml`
- `scripts/test-setup.yaml`
- `../.github/workflows/ci.metrics.yml`
