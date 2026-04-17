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
- Environment overlays are provided by Helm values for `dev`, `int`, `staging`,
  and `prod`.

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

Run the API locally:

```bash
METRICS_CACHE_BACKEND=memory \
METRICS_PROVIDER_MODE=static \
uv run python -m metrics.main
```

## Local Kubernetes integration loop

A complete local loop is provided through a kind-based integration script.

```bash
bash scripts/run-kind-integration.sh
```

This script:

1. Creates a local kind cluster.
2. Builds and loads the local metrics container image.
3. Deploys the Helm chart with `scripts/kind-values.yaml`.
4. Runs black-box integration tests in `tests/integration`.
5. Tears down the cluster.

## Container image

Build the image from the service directory:

```bash
docker build -t canfar-metrics:local .
```

The image exposes port `8000` and includes a health check against `/healthz`.

## Helm deployment

The service chart is located at
`deployment/helm/metrics-api` in the parent `science-platform` repository.

Development deployment example:

```bash
helm upgrade --install metrics-api ../deployment/helm/metrics-api \
  --namespace metrics \
  --create-namespace \
  -f ../deployment/helm/metrics-api/values-dev.yaml
```

You can also use the helper script:

```bash
bash scripts/deploy-with-helm.sh dev
```

## CI workflow

A dedicated workflow is available at
`.github/workflows/ci.metrics.yml` in the parent repository. It runs metrics
lint/tests, builds the metrics container image, and supports optional kind
smoke validation through manual dispatch.
