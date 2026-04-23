# CANFAR metrics rollout baseline matrix (M7 support plan)

This document captures the roadmap baseline matrix and acceptance gates for the
post-M6 rollout sequence.

## Capability matrix

- **M1 foundation**
  - Baseline: service code and unit tests only.
  - Target: dedicated CI workflow, prerequisites checks, cluster integration
    script, Helm chart, and metrics-focused pre-commit hooks.
- **M2 platform release**
  - Baseline: broad provider selection and weak startup validation.
  - Target: strict Kueue mode contract and startup checks.
- **M3 app structure realignment**
  - Baseline: flat package layout and legacy provider remnants.
  - Target: layered FastAPI structure and three-source model with static/node
    removed.
- **M4 kube-metrics release**
  - Baseline: kube-metrics not fully wired as runtime source.
  - Target: kube-metrics runtime implementation with startup validation.
- **M5 user release**
  - Baseline: user route exists but lacks production attribution controls.
  - Target: canonical user attribution and bounded query behavior.
- **M6 session release**
  - Baseline: session route exists with contract-level coverage only.
  - Target: strict session mapping, cardinality safeguards, and rollout controls.
- **M9 post-initial GitOps**
  - Baseline: no ArgoCD integration plan execution.
  - Target: deferred staging GitOps plan after earlier milestone stability.

## Acceptance gates

### Gate A: quality and contract gate

- `uv run ruff check src tests`
- `uv run pytest`
- coverage floor remains `>=80%`
- `FastAPI TestClient` covers app-level contract behavior where safe

### Gate B: local deployment gate

- prerequisites check requires `kubectl`, `helm`, and Minikube tooling
- `bash scripts/minikube-smoke.sh`
- integration tests pass against deployed endpoint using `METRICS_BASE_URL`

### Gate C: container and runtime gate

- `docker build` succeeds from `metrics/Dockerfile`
- `/healthz` returns `200`
- runtime configuration resolves through `METRICS_*`

### Gate D: release observability gate

- `Cache-Control`, `Date`, `Expires`, and `Last-Modified` headers appear on
  metrics responses
- OTel metrics include request, provider, cache, and compute signals
- debug checklist is attached to rollout review

### Gate E: roadmap review checkpoints

- M1 confirms environment ownership boundaries.
- M2 confirms Kueue runtime contract and version scope.
- M3 confirms architecture cleanup and provider cutover.
- M4 confirms kube-metrics implementation depth remains in-scope.
- M5 confirms user-attribution contract hardening.
- M6 confirms session identity and cardinality guardrails.
- M8 confirms stabilization exit criteria before M9 GitOps staging kickoff.
