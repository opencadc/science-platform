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
`docs/environment-contracts.md` for env var contracts and alias precedence.

## API routes

The canonical routes are:

- `GET /api/v1/metrics/platform`
- `GET /api/v1/metrics/users/{user}`
- `GET /api/v1/metrics/users/{user}/sessions/{uuid}`

Compatibility aliases also exist under `/metrics` for transition and smoke
testing workflows.

## 12-factor runtime model

All runtime behavior is configured via environment variables prefixed with
`METRICS_`.

- Configuration and credentials come from environment variables only.
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
METRICS_CACHE_BACKEND=memory \
METRICS_PLATFORM__PROMETHEUS__URL=http://127.0.0.1:9090 \
METRICS_PLATFORM__KUEUE__KUBE_API_URL=https://kubernetes.default.svc \
METRICS_KUEUE_CLUSTER_QUEUES=cq-proton \
METRICS_KUEUE_COHORT=cohort-atom \
uv run python -m metrics.main
```

Use this command for process-level debugging. For supported `dev` operation
with Kueue dependencies, follow the Kubernetes-first setup in
`docs/dev-kueue-cluster-setup.md`.

For roadmap-level environment naming and how `METRICS_ENVIRONMENT` maps across
`dev`, integration, staging, and production, see `docs/environment-contracts.md`.

### Kueue-backed platform metrics

For **module responsibilities** and the **startup vs request** flow for
Kueue-backed platform metrics, see `docs/kueue-platform.md`. That guide is the
canonical developer-oriented supplement to milestones M2 and M3.

**Cluster dev setup** (preflight → Helm Kueue → fixtures → Metrics/Redis Helm →
`kubectl port-forward`) is step-by-step in `docs/dev-kueue-cluster-setup.md`.

## Local Kubernetes integration loop

Local and CI both use **Minikube** (not Kind) so the environment matches upcoming
work that depends on cluster addons such as **metrics-server** (resource
metrics API). Minikube can enable these with `minikube addons enable …`.

### Iterative dev (keep your cluster)

Follow `docs/dev-kueue-cluster-setup.md` for preflight checks, Kueue (Helm),
fixture apply, image build + Helm deploy, and a **local port** to reach the API
via `kubectl port-forward`. That guide targets your **existing** Minikube
(usually kubectl context `minikube`); it does not create a second profile.

### One-shot verification (CI-style)

```bash
bash scripts/run-minikube-integration.sh
```

This script is meant for **automated smoke / CI**, not day-to-day dev on your
default cluster. Use your active Minikube context for this workflow. It:

1. Uses the current Minikube profile and enables the **metrics-server** addon
   unless `MINIKUBE_ENABLE_METRICS_SERVER=false`.
2. Installs Kueue via Helm (`scripts/install-kueue-minikube.sh`) and applies
   `tests/fixtures/kueue/`.
3. Builds and loads the local metrics container image into Minikube.
4. Deploys the Helm chart with `scripts/minikube-values.yaml`.
5. Runs black-box integration tests in `tests/integration`.
6. Cleans up the release namespace according to script teardown behavior.

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

Lint, unit tests, Docker image validation, and Minikube smoke deployment run from
`.github/workflows/ci.metrics.yml` in the parent repository on changes under
`metrics/**`.

Release container images (`linux/amd64`, `linux/arm64`) publish only on Git tags
matching `metric-v*` via `.github/workflows/cd.metrics.release.build.yml`.

Release notes and versioning for Metrics follow the separate Metrics package in
root `release-please-config.json`, using tags like `metric-v0.1.0`.
