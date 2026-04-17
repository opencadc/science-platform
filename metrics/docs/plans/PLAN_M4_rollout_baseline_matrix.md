# CANFAR metrics rollout baseline matrix

This document captures the baseline gap matrix used to drive the milestone
rollout. It maps the current implementation to each milestone objective and
locks acceptance gates before execution.

## Capability matrix

The matrix records what was present before execution and what was added during
implementation.

- **M1 foundation**
  - Baseline: service code and unit tests only; no local cluster harness, no
    dedicated CI workflow, no service-specific chart.
  - Implemented: Minikube integration script, integration smoke tests, dedicated
    CI workflow, Helm chart, container hardening, and metrics-focused
    pre-commit hooks.
- **M2 platform release**
  - Baseline: platform endpoint only, no HTTP cache headers, basic OTel
    counters/histograms.
  - Implemented: response cache headers, expanded telemetry dimensions, and
    production debug runbook checklist.
- **M3 user release**
  - Baseline: placeholder `501` user endpoint.
  - Implemented: user endpoint now computes and returns capacity/usage sources
    with caching and telemetry.
- **M4 session release**
  - Baseline: placeholder `501` session endpoint.
  - Implemented: session endpoint now computes and returns capacity/usage
    sources with caching and telemetry.
- **Post-initial GitOps**
  - Baseline: no ArgoCD integration plan for this service.
  - Implemented: deferred staging GitOps plan documented for follow-on work.

## Acceptance gates

These gates are required for milestone completion.

### Gate A: quality and contract gate

The service must pass static checks and tests before release candidates.

- `uv run ruff check src tests`
- `uv run pytest`
- coverage floor remains `>=80%`

### Gate B: local deployment gate

The service must pass the local Kubernetes loop.

- `bash scripts/run-minikube-integration.sh`
- integration tests in `tests/integration` return success
- chart deploy and rollout complete without manual intervention

### Gate C: container and runtime gate

The container must build and expose healthy runtime behavior.

- `docker build` succeeds from `metrics/Dockerfile`
- `/healthz` returns `200`
- runtime env values resolve via `METRICS_` variables

### Gate D: release observability gate

The release must expose cache and telemetry signals for operational debugging.

- `Cache-Control` and `X-Metrics-Cached` headers appear on metrics responses
- OTel metrics include request, provider, cache, and compute signals
- debug checklist is attached to rollout review
