# CANFAR Science Platform Metrics API

This service collects and serves CANFAR platform metrics through a versioned
REST API. It is built as a 12-factor FastAPI service and packaged as a single
container process.

## Kubernetes access and RBAC

When running in-cluster, the workload needs read access
to `clusterqueues` in the `kueue.x-k8s.io` API group and namespaced Pod `list`
access for each configured workload namespace. Prefer
creating a dedicated Kubernetes `ServiceAccount` via the chart
(`serviceAccount.create: true`) whenever `rbac.create` is enabled, so cluster
permissions are not bound to the namespace `default` ServiceAccount. See
`docs/environment-contracts.md` for environment naming and nested
`METRICS_*` configuration.

## API routes

The API exposes:

- `GET /apis/canfar.net/v1alpha1/metrics/platform/canfar`
- `GET /apis/canfar.net/v1alpha1/metrics/user/{user}`
- `GET /apis/canfar.net/v1alpha1/metrics/community/{community}`
- `GET /healthz`

## `v1alpha1` contract

The Platform, User, and Community routes return the shared
`canfar.net/v1alpha1` `Metrics` kind.
Freshness is described by `Last-Modified`, `Age`, and `Cache-Status`; all API
responses use `Cache-Control: no-store`. The code-backed reference set is
[`docs/architecture.md`](docs/architecture.md), [`docs/design.md`](docs/design.md),
and [`docs/specs.md`](docs/specs.md).

The transition grows the existing `src/metrics` package in place and retains
its `api`, `core`, `providers`, `schemas`, and `services` seams.

## 12-factor runtime model

All runtime behavior is configured via environment variables prefixed with
`METRICS_` (see `docs/environment-contracts.md`).

- Configuration comes from environment variables; Kubernetes access is
  discovered by kr8s from the service account (ADR-0001).
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
uv run metrics-dev up
uv run metrics-dev run
```

This starts the host process against the local Redis and Kueue dependencies.
Use `uv run metrics-dev up --profile accounting` to add pinned
kube-state-metrics, Prometheus, recording rules, and producer fixtures; the
default core profile remains unchanged.
For the full Kubernetes-first workflow, follow `docs/dev-setup.md`.

For roadmap-level environment naming across `dev`, integration, staging, and
production, see `docs/environment-contracts.md`.

Platform response expansion for borrowed/lending details is out of scope for
this delivery; each resource reports normalized `capacity` and `allocated`
quantities.

### Kueue-backed platform metrics

For **module responsibilities** and the **startup vs request** flow for
Kueue-backed platform metrics, see `docs/kueue-platform.md` (aligned with M4
provider runtime behavior).

**Cluster dev setup** — installed `metrics-dev` commands; see `docs/dev-setup.md`.

## Local Kubernetes integration loop

Local and CI both use a one-node **kind** cluster for smoke validation.

### Iterative dev (keep your cluster)

See `docs/dev-setup.md`. Start or converge the pinned reusable stack with:

```bash
uv run metrics-dev up
```

### One-shot verification (CI-style)

```bash
uv run metrics-dev smoke
```

`scripts/kind-smoke.sh` remains a thin wrapper around `metrics-dev smoke`.
Use `uv run metrics-dev down`, `reset`, or `destroy --confirm kind-metrics`
for lifecycle cleanup.

The smoke runs the chart-built image with two API replicas and embedded Redis.
Over real forwarded sockets it proves cold and fresh cache reads, conditional
`304` responses, stale fallback, fail-closed errors, empty User/Community
responses, all three current-metrics routes, legacy-route absence, and graceful
SIGTERM shutdown. The reported warm duration excludes prerequisite and image
pulls; use `metrics-dev up` to record cold setup separately.

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
helm upgrade --install metrics-api helm/metrics-api -n metrics \
  --create-namespace -f helm/metrics-api/values-dev.yaml --wait
```

## CI workflows

Lint, unit tests, Docker image validation, and kind smoke deployment run from
`.github/workflows/ci.metrics.yml` in the parent repository on changes under
`metrics/**`.

Release container images (`linux/amd64`, `linux/arm64`) publish only on Git tags
matching `metrics-v*` via `.github/workflows/cd.metrics.release.build.yml`.

Release notes and versioning for Metrics follow the separate Metrics package in
root `release-please-config.json`, using tags like `metrics-v0.1.0`.
