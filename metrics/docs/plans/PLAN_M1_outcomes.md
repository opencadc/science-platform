# M1 outcomes — project setup and delivery foundation

This note records closure evidence for milestone M1 (`PLAN_M1_project_setup_and_delivery_foundation.md`).

## Delivered

- **CI/CD pathways:** Repo-wide workflows remain global; Skaha workflows are renamed (`cd.skaha.release.yml`, `cd.skaha.release.build.yml`) and gated so Metrics-only paths do not trigger Skaha edge builds or Skaha image release dispatches; Metrics workflows target `metrics/**` only; Skaha CI linting/testing skip `metrics/**` on pull requests and pushes.
- **Metrics CI:** `.github/workflows/ci.metrics.yml` runs lint, unit tests, Minikube deploy using `metrics/helm/metrics-api`, and integration smoke tests.
- **Pre-commit:** `metrics/.pre-commit-config.yaml` owns Python-first checks; root `.pre-commit-config.yaml` invokes it when files under `metrics/` change.
- **Container:** `metrics/Dockerfile` uses fixed non-root UID/GID 65532; `metrics/.dockerignore` trims build context.
- **Helm:** Minimal chart under `metrics/helm/metrics-api` with `values-dev.yaml` only.
- **Release automation:** Root `release-please-config.json` includes a `metrics` package with `metric-v*` style tags; Skaha package excludes the `metrics/` path so Metrics-only commits do not bump Skaha.
- **Image publishing:** `.github/workflows/cd.metrics.release.build.yml` builds and pushes multi-arch images to `images.opencadc.org/platform/metrics` only on `metric-v*` tag pushes.

## Verification commands (local)

From `metrics/`:

```bash
uv run --group harness pytest -q tests/harness/test_contracts.py
uv run pytest --cov=src --cov-report=term-missing --cov-fail-under=80
uv run --group harness python -m harness check
pre-commit run --config .pre-commit-config.yaml --all-files
helm lint helm/metrics-api
helm template metrics-api helm/metrics-api -f helm/metrics-api/values-dev.yaml >/dev/null
```

## Gates

See `metrics/project-gates.yaml` for required gate ids (`harness-contracts`, `repository-coverage`, `harness-cli`). The Metrics CI workflow runs coverage plus harness contract tests and `python -m harness check`; record evidence from CI logs or local runs before handoff.

## Roadmap review follow-ups

The M1 milestone plan was revised after roadmap review to separate **repository
facts** from **planned deliverables** and to document environment naming drift
between roadmap language and runtime settings. Treat this outcomes note as
closure evidence for what shipped in M1, not as a statement that later milestone
targets are already implemented.
