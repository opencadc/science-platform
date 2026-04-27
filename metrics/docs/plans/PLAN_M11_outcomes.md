# M11 outcomes — local Kubernetes workflow simplification

Closure for M11 (see `PLAN_M11_local_k8s_workflow_simplification.md`).

## Checkpoints (B–D)

- **B** — Upstream Kueue Helm chart; **C** — `skaffold.yaml` + `helm/metrics-api` only; **D** — `docs/dev-setup.md` (later simplified further: one script, one `scripts/test-setup.yaml`).

## Current layout (post-follow-up)

- **`scripts/minikube-smoke.sh`** — full local smoke; **`MINIKUBE_SMOKE_CI=1`** for the CI segment after preloads.
- **`scripts/test-setup.yaml`** — all Kueue smoke YAML in one file.
- **`scripts/check-prerequisites.sh`**, **`scripts/teardown-dev-kube-setup.sh`**, **`scripts/deploy-with-helm.sh`**, **`scripts/minikube-values.yaml`** — supporting only.

## Verification (from `metrics/`)

```bash
uv run ruff check src tests
uv run pytest --cov=src --cov-report=term-missing --cov-fail-under=80 -m "not integration"
uv run --group harness pytest -q tests/harness/test_contracts.py
uv run --group harness python -m harness check
kubectl apply --dry-run=client -f scripts/test-setup.yaml
helm lint helm/metrics-api -f scripts/minikube-values.yaml
```

Gates: `harness-contracts`, `repository-coverage`, `harness-cli` (`project-gates.yaml`).
