# Environment contracts (Metrics service)

This document records how **roadmap environment names** map to **runtime
settings** and what this repository owns versus external deployment
repositories.

## Canonical names (roadmap / operations)

| Roadmap / handoff | `METRICS_ENVIRONMENT` (current) | Notes |
| --- | --- | --- |
| `dev` | `dev` | Local `docker compose` and in-repo `values-dev.yaml` only. |
| `integration` | `int` | Roadmap name is `integration`; the app uses `int` today. |
| `staging` | `staging` | Overlays live outside this repository. |
| `production` | `prod` | Roadmap name is `production`; the app uses `prod` today. |

M2 may align naming (rename settings literals) or keep this mapping as a
documented operator contract. Until then, operators and charts should set
`METRICS_ENVIRONMENT` to the **runtime** value in the right column.

## What this repository provides

- **Local `dev` app + Redis:** `compose.yaml` in `metrics/`; optional `.env` for
  variable substitution (see `env.example`). This stack is for fast API
  iteration; it does not embed a full Kubernetes control plane.
- **Local / CI cluster contract:** `helm/metrics-api` with `values-dev.yaml`
  only. Minikube (or another cluster) is an **external** prerequisite for
  production-like data sources and integration smoke tests.
- **12-factor configuration:** all behavior through `METRICS_*` environment
  variables; no config files required in the container.

## What lives elsewhere

- `integration`, `staging`, and `production` **value overlays** and GitOps
  promotion for those environments are owned by a separate deployment
  repository. This repo documents the **handoff** (chart contract, image
  reference, required env vars) so those overlays can pin versions and
  wire secrets.

## Image and release contract

- Release images: `images.opencadc.org/platform/metrics` on Git tags
  `metric-v*`, multi-arch `linux/amd64` and `linux/arm64` (see
  `../README.md` and root release-please config).
- Non-tag CI does not publish release images.

## Review checkpoint (M1)

Before mode-specific feature work, confirm with stakeholders that the
**environment ownership** model above (in-repo `dev` only, higher envs
elsewhere) matches org process. Record agreement in milestone outcomes or
`docs/plans/PLAN_M1_outcomes.md`.
