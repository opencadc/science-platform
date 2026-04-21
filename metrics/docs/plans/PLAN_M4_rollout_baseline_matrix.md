# CANFAR metrics rollout baseline matrix

This document captures the baseline gap matrix used to drive the milestone
rollout. It maps the current implementation to each milestone objective and
locks acceptance gates before execution.

## Capability matrix

The matrix records what was present before execution and what was added during
implementation.

Read this matrix as **baseline versus roadmap target**, not as a live status
dashboard. Several rows describe milestone intent that is not yet fully reflected
in the service implementation, and the roadmap review called out those gaps
explicitly.

- **M1 foundation**
  - Baseline: service code and unit tests only; no local cluster harness, no
    dedicated CI workflow, no service-specific chart.
  - Target: dedicated CI workflow, prerequisite checks, `docker compose`
    local app and Redis workflow, a general-purpose cluster integration script
    for an already running dev cluster, Helm chart, container hardening,
    environment contracts, and metrics-focused
    pre-commit hooks.
- **M2 platform release**
  - Baseline: platform endpoint with generic `live` or `static` provider
    selection, no explicit mode startup validation, and no Kueue fixture
    contract.
  - Target: strict `Kueue` mode, Kueue `0.17.0+` scope, startup
    validation, `FastAPI TestClient` coverage for valid and invalid mode
    behavior, Kueue fixtures under `tests/fixtures/kueue`, and review
    checkpoint for Kueue runtime design.
- **M2b kube-metrics release**
  - Baseline: no dedicated kube-metrics mode milestone and no fixture contract
    for kube-metrics-specific assets.
  - Target: follow-on `Kube-Metrics` mode milestone with the same
    single-mode runtime contract, startup validation model, `FastAPI
    TestClient` requirement, fixture ownership under
    `tests/fixtures/kube-metrics`, and architecture review checkpoint.
- **M3 user release**
  - Baseline: user route exists and returns a `UserMetrics` envelope, but
    production attribution rules, label governance, and scoped operational
    guardrails are still milestone work.
  - Target: canonical user attribution contract, bounded query behavior,
    user-scope telemetry parity, and tests that lock edge cases beyond the
    current static-provider contract checks.
- **M4 session release**
  - Baseline: session route exists and returns a `SessionMetrics` envelope, but
    session identity validation, high-cardinality controls, and production-grade
    failure semantics are still milestone work.
  - Target: strict session identity mapping, cardinality safeguards, session
    cache strategy, and rollout monitoring checks that go beyond the current
    contract-level coverage.
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
- `FastAPI TestClient` covers app-level contracts for routes that are safe to
  exercise without a live cluster, including mode startup failures **once**
  startup validation exists in the application factory or lifespan
- optional fixture-backed unit tests read manifests from `tests/fixtures/kueue`
  or `tests/fixtures/kube-metrics` once those directories exist

### Gate B: local deployment gate

The service must pass the local Kubernetes loop.

- local scripts fail immediately if `kubectl`, `docker`, or `helm` are
  unavailable, or if the intended cluster context is unreachable
- `bash scripts/run-minikube-integration.sh` (historical script name; the
  updated roadmap contract assumes the cluster already exists)
- cluster-backed smoke tests in `tests/integration` return success against a
  deployed endpoint using `METRICS_BASE_URL`
- chart deploy and rollout complete without manual intervention

### Gate C: container and runtime gate

The container must build and expose healthy runtime behavior.

- `docker build` succeeds from `metrics/Dockerfile`
- local `dev` workflow starts the app and Redis through `docker compose` once the
  compose specification is checked in under `metrics/`
- `/healthz` returns `200`
- runtime env values resolve via `METRICS_` variables

### Gate D: release observability gate

The release must expose cache and telemetry signals for operational debugging.

- `Cache-Control`, `Date`, `Expires`, and `Last-Modified` headers appear on metrics responses
- OTel metrics include request, provider, cache, and compute signals
- debug checklist is attached to rollout review

### Gate E: roadmap review checkpoints

Critical milestone design checkpoints must be recorded before implementation
widens.

- M1 environment ownership checkpoint confirms `dev` artifacts stay in this
  repository and higher-environment overlays stay elsewhere
- M2 checkpoint confirms the `Kueue` mode runtime contract and `0.17.0+` scope
- M2b checkpoint confirms the shared architecture boundary remains clean before
  adding kube-metrics implementation depth
