# CANFAR Science Platform Metrics API

This service collects and serves CANFAR platform metrics through a versioned
REST API. It is built as a 12-factor FastAPI service and packaged as a single
container process.

## Kueue mode and RBAC

When running in-cluster, the workload needs read access
to `clusterqueues` and `cohorts` in the `kueue.x-k8s.io` API group. Prefer
creating a dedicated Kubernetes `ServiceAccount` via the chart
(`serviceAccount.create: true`) whenever `rbac.create` is enabled, so cluster
permissions are not bound to the namespace `default` ServiceAccount. See
`docs/environment-contracts.md` for environment naming and nested
`METRICS_*` configuration.

## API routes

The API exposes:

- `GET /api/v1/metrics/platform`
- `GET /healthz`

## 12-factor runtime model

All runtime behavior is configured via environment variables prefixed with
`METRICS_`, merged with optional YAML (see `docs/environment-contracts.md`).

- Configuration and credentials come from environment variables and optional
  `METRICS_CONFIG_FILE` (default `/etc/canfar/metrics/config.yaml`). A missing
  file is allowed unless `METRICS_REQUIRE_CONFIG_FILE` is set to a true value.
- Pydantic `Settings` groups options under `providers`, `sources`, and `cache`
  (nested env keys use `__`; see `docs/environment-contracts.md`).
- The process remains stateless and uses TTL cache backends.
- Structured logs are emitted to stdout through the app server runtime.
- One service process is packaged per container image.
- Environment-specific settings are supplied through Helm values; this
  repository ships `dev` values only (`./helm/metrics-api/values-dev.yaml`
  relative to the `metrics/` directory).

## Local development

Create and sync a development environment:

```bash
uv sync --group dev
```

Run tests and linting:

```bash
uv run pytest
uv run ruff check src tests
```

Run Metrics-only pre-commit hooks (also driven from the repo root):

```bash
pre-commit run --config metrics/.pre-commit-config.yaml --all-files
```

Run the repository root pre-commit checks (includes shared governance hooks):

```bash
pre-commit run --all-files
```

Run the API locally:

```bash
METRICS_CACHE__BACKEND=memory \
METRICS_PROVIDERS__KUEUE__KUBE_API_URL=https://kubernetes.default.svc \
METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES='["cq-proton"]' \
METRICS_PROVIDERS__KUEUE__COHORT=cohort-atom \
uv run python -m metrics.main
```

`METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES` must be a JSON array string (not a
comma-separated list). Use this command for process-level debugging. For
supported `dev` operation with Kueue dependencies, follow the
Kubernetes-first setup in `docs/dev-setup.md`.

For roadmap-level environment naming and how `METRICS_ENVIRONMENT` maps across
`dev`, integration, staging, and production, see `docs/environment-contracts.md`.

### Kueue-backed platform metrics

For **module responsibilities** and the **startup vs request** flow for
Kueue-backed platform metrics, see `docs/kueue-platform.md` (aligned with M4
provider runtime behavior).

**Cluster dev setup** — one script, `docs/dev-setup.md`.

## Local Kubernetes integration loop

Local and CI both use a one-node **kind** cluster for smoke validation.

### Iterative dev (keep your cluster)

See `docs/dev-setup.md`. The supported flow is
**`bash scripts/kind-smoke.sh`** (Helm Kueue, `scripts/test-setup.yaml`,
Docker build + `kind load`, Helm deploy, integration tests).

### One-shot verification (CI-style)

```bash
KIND_SMOKE_CI=1 KIND_SMOKE_EXIT_AFTER_TESTS=1 bash scripts/kind-smoke.sh
```

`scripts/kind-smoke.sh` runs the full kind smoke and can leave the API
port-forward up for local debugging. Stop it with
`bash scripts/kind-smoke-teardown.sh`.

## Container image

Build the image from the service directory:

```bash
docker build -t canfar-metrics:local .
```

The image exposes port `8000` and includes a health check against `/healthz`.

## Helm deployment

The Helm chart lives in `metrics/helm/metrics-api` within this workspace.

Development deployment example (run from `metrics/`):

```bash
helm upgrade --install metrics-api ./helm/metrics-api \
  --namespace metrics \
  --create-namespace \
  -f ./helm/metrics-api/values-dev.yaml
```

You can also use the helper script:

```bash
bash scripts/deploy-with-helm.sh dev
```

## CI workflows

Lint, unit tests, Docker image validation, and kind smoke deployment run from
`.github/workflows/ci.metrics.yml` in the parent repository on changes under
`metrics/**`.

Release container images (`linux/amd64`, `linux/arm64`) publish only on Git tags
matching `metrics-v*` via `.github/workflows/cd.metrics.release.build.yml`.

Release notes and versioning for Metrics follow the separate Metrics package in
root `release-please-config.json`, using tags like `metrics-v0.1.0`. See
`docs/releasing.md` for the Release Please and first-tag `0.1.0` workflow.
