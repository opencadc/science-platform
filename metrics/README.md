# CANFAR Science Platform Metrics API

This service collects and serves CANFAR platform metrics through a versioned
REST API. It is built as a 12-factor FastAPI service and packaged as a single
container process.

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
METRICS_PROVIDER_MODE=static \
uv run python -m metrics.main
```

## Local Kubernetes integration loop

Local and CI both use **Minikube** (not Kind) so the environment matches upcoming
work that depends on cluster addons such as **metrics-server** (resource
metrics API). Minikube can enable these with `minikube addons enable …`.

```bash
bash scripts/run-minikube-integration.sh
```

This script:

1. Starts a dedicated Minikube profile (`metrics-local` by default) and enables
   the **metrics-server** addon unless `MINIKUBE_ENABLE_METRICS_SERVER=false`.
2. Builds and loads the local metrics container image into Minikube.
3. Deploys the Helm chart with `scripts/minikube-values.yaml`.
4. Runs black-box integration tests in `tests/integration`.
5. Deletes the profile and namespace on exit (same teardown style as CI smoke).

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
