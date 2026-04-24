# M1 outcomes — project setup and delivery foundation

This note records closure evidence for milestone M1
(`PLAN_M1_project_setup_and_delivery_foundation.md`).

> Note: M1 outcomes include historical Docker Compose artifacts. The current
> runtime contract is Kubernetes-first as documented in
> `docs/environment-contracts.md` and the M3 roadmap sequence.

## Delivered

- **CI/CD pathways:** Repo-wide workflows remain global; Skaha workflows use
  explicit names (`cd.platform.release.yml`, `cd.skaha.release.build.yml`) and path
  filters so Metrics-only changes do not trigger Skaha linting, testing, or edge
  builds; Metrics workflows target `metrics/**` plus
  `.github/workflows/ci.metrics.yml` so workflow edits self-trigger.
- **Skaha release gating:** `cd.platform.release.yml` uses bracket step ids for
  hyphenated steps and gates Skaha edge dispatch / release-build dispatches away
  from `metric-v*` Metrics tags; edge dispatch also respects `paths-filter`
  `non_metrics`.
- **Metrics CI:** `.github/workflows/ci.metrics.yml` runs lint, unit tests,
  harness contracts and CLI check, validates `docker compose -f compose.yaml
  config`, builds and loads an image into the cluster-backed CI harness
  (Minikube in the original implementation), deploys with
  `metrics/helm/metrics-api`, and runs integration smoke tests.
- **Pre-commit:** `metrics/.pre-commit-config.yaml` owns Python-first checks;
  root `.pre-commit-config.yaml` invokes it when paths under `metrics/` change.
- **Container:** `metrics/Dockerfile` uses fixed non-root UID/GID 65532;
  `metrics/.dockerignore` trims build context; `/healthz` is exercised by the
  image `HEALTHCHECK`.
- **Local dev (`docker compose`):** `metrics/compose.yaml` runs FastAPI plus
  Redis with static provider defaults; `env.example` documents substitution into
  `.env`; `.gitignore` excludes local `.env` and `compose.override.yaml`.
- **Fail-fast prerequisites:** `metrics/scripts/check-prerequisites.sh` (sourced from
  `minikube-smoke.sh`, `deploy-with-helm.sh`, and teardown). Those scripts `cd` to
  `metrics/` so paths resolve; optional `KUBE_CONTEXT` for Helm and kubectl.
- **Helm:** Minimal chart under `metrics/helm/metrics-api` with `values-dev.yaml`
  only; `deploy-with-helm.sh` defaults to `helm/metrics-api` from `metrics/`.
- **Release automation:** Root `release-please-config.json` includes a `metrics`
  package with `metric-v*` style tags; Skaha package excludes `metrics/` so
  Metrics-only commits do not bump Skaha.
- **Image publishing:** `.github/workflows/cd.metrics.release.build.yml` builds and
  pushes multi-arch images to `images.opencadc.org/platform/metrics` only on
  `metric-v*` tag pushes.
- **Environment contracts:** `metrics/docs/environment-contracts.md` records
  ownership boundaries and the canonical service mode names `dev`, `staging`,
  `integration`, and `production`.

## Verification commands (local)

From `metrics/`:

```bash
docker compose -f compose.yaml config >/dev/null
uv run --group harness pytest -q tests/harness/test_contracts.py
uv run pytest --cov=src --cov-report=term-missing --cov-fail-under=80 -m "not integration"
uv run --group harness python -m harness check
pre-commit run --config .pre-commit-config.yaml --all-files
helm lint helm/metrics-api
helm template metrics-api helm/metrics-api -f helm/metrics-api/values-dev.yaml >/dev/null
bash scripts/check-prerequisites.sh docker helm kubectl minikube
```

## Gates

See `metrics/project-gates.yaml` for required gate ids (`harness-contracts`,
`repository-coverage`, `harness-cli`). The Metrics CI workflow runs coverage plus
harness contract tests and `python -m harness check`; record evidence from CI
logs or local runs before handoff.

## Independent review summary (M1 closure)

Four focused passes were run over routing/CI, Docker/Compose, shell scripts, and
documentation.

**Findings incorporated**

- Compose header comments aligned with `env.example` → `.env`; architecture doc
  link fixed to sibling `environment-contracts.md`.
- Scripts anchored to `metrics/`; deploy script honors optional `KUBE_CONTEXT`;
  `_die` avoids exiting an interactive shell when `check-prerequisites.sh` is
  sourced (returns instead of `exit`).
- `ci.metrics.yml` triggers when the workflow file itself changes.
- `cd.platform.release.yml` uses bracket notation for hyphenated step outputs.
- `PLAN_M1_project_setup_and_delivery_foundation.md` updated so Compose is
  recorded as shipped (no longer described as pending).
- `env.example` carries uncommented safe defaults; README notes `METRICS_PORT`
  vs image `HEALTHCHECK` if operators change internal listen port.

**Reviewer notes not adopted (risk / scope tradeoffs)**

- Adding `docker info` / cluster reachability checks would catch “daemon down”
  sooner but adds latency and flaky CI assumptions; kept binary-only checks per
  M1 fail-fast scope.
- Nightly Skaha lint schedule remains repo-wide by design (policy sweep), not
  tightened to Skaha paths.

**Human follow-up (non-blocking)**

- Confirm with operators that the environment ownership model in
  `environment-contracts.md` matches deployment-repo practice before deep mode
  work (plan checkpoint).

## Roadmap review follow-ups

The M1 milestone plan text and this outcomes note distinguish **repository
facts** from **later milestone targets**. Treat this file as closure evidence for
what shipped in M1; later milestones cover mode-specific validation and naming
refinement for `METRICS_ENVIRONMENT` if needed.
