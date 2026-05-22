# Kind: Metrics API development and testing

Use `bash scripts/kind-smoke.sh` for the full local smoke path. The script
creates a one-node kind cluster when needed, installs Kueue, applies
`scripts/test-setup.yaml`, builds and loads the Metrics API image, deploys with
Helm, waits for admission and rollouts, and runs integration tests.

## Prerequisites

Install these tools before running the smoke flow.

- Docker
- kind
- kubectl
- Helm
- Python 3.13 and `uv`

Run from the `metrics/` directory.

## Recommended local run

Run the full smoke path with a single command:

```bash
bash scripts/kind-smoke.sh
```

Default runtime values:

- `KIND_CLUSTER_NAME=metrics`
- `KUBE_CONTEXT=kind-metrics`
- `NAMESPACE=metrics`
- `PORT_FORWARD_PORT=18080`

After a successful local run, the API port-forward stays up for debugging.
Stop it with:

```bash
bash scripts/kind-smoke-teardown.sh
```

## CI-parity run

To mirror CI behavior locally (no persistent port-forward):

```bash
KIND_SMOKE_CI=1 KIND_SMOKE_EXIT_AFTER_TESTS=1 KIND_PRELOAD_IMAGES=false \
  bash scripts/kind-smoke.sh
```

If you prebuild the image and want to skip the build step:

```bash
docker build -t canfar-metrics-local:dev-local .
KIND_SMOKE_SKIP_BUILD=1 METRICS_IMAGE_TAG=dev-local bash scripts/kind-smoke.sh
```

## Teardown options

Non-destructive (stop only the background port-forward):

```bash
bash scripts/kind-smoke-teardown.sh
```

Delete Helm releases, fixtures, the Metrics images loaded into the kind node,
and the kind cluster:

```bash
bash scripts/kind-smoke-teardown.sh --all --kind
```

## Contract fixtures

The smoke contract names in `scripts/test-setup.yaml` and chart values remain:

- `default-flavor`
- `cq-proton`
- `cq-neutron`
- `cq-electron`
- `lq-smoke`
- `integration-idle`

The sample workload targets `cq-electron`, which has `100m` CPU and `100Mi`
memory nominal quota. The workload requests `200m` CPU and `200Mi` memory, so
the smoke test verifies admitted usage totals in platform `allocated`. Borrowed
and lending response-field expansion is out of scope for this delivery.

## Troubleshooting

- Image not found (`ErrImageNeverPull`): re-run `bash scripts/kind-smoke.sh`, or
  rebuild and load explicitly:
  `docker build -t canfar-metrics-local:TAG .` then
  `kind load docker-image canfar-metrics-local:TAG --name metrics`.
- API startup failure: check logs with
  `kubectl --context kind-metrics -n metrics logs deploy/metrics-api-metrics-api --tail=200`.
- Workload not admitted: check Kueue status and fixture objects:
  `kubectl --context kind-metrics get clusterqueue` and
  `kubectl --context kind-metrics -n metrics get localqueue,workload`.
- Redis cache data stale during iterative tests:
  `kubectl --context kind-metrics exec -n metrics deploy/metrics-api-redis -- redis-cli FLUSHDB`.

## Related files

- `scripts/kind-smoke.sh`
- `scripts/kind-smoke-teardown.sh`
- `scripts/kind-values.yaml`
- `scripts/test-setup.yaml`
- `../.github/workflows/ci.metrics.yml`
