# Environment contracts (Metrics service)

This document records how **roadmap environment names** map to **runtime
settings** and what this repository owns versus external deployment
repositories.

## Canonical `METRICS_ENVIRONMENT` values

The application accepts **exactly** these runtime tokens (case-insensitive input
is normalized):

| Value | Use |
| --- | --- |
| `dev` | Local `docker compose` and in-repo chart smoke defaults. |
| `integration` | CI-backed or shared integration clusters. |
| `staging` | Pre-production; overlays typically live outside this repository. |
| `production` | Production deployments. |

**Legacy aliases (still accepted, normalized to the canonical value above):**

| Legacy input | Normalized to |
| --- | --- |
| `int` | `integration` |
| `prod` | `production` |

Any other value is rejected at settings validation time.

## Operator alias precedence (`KUEUE_METRICS_*` versus `METRICS_*`)

The application loads `METRICS_*` fields first, then applies **fill-only** aliases
from the process environment inside
`Settings._apply_operator_kueue_env_aliases` in
`metrics/src/metrics/config.py`:

| If this field is empty | And this env var is set | Then |
| --- | --- | --- |
| `kube_api_url` | `KUEUE_METRICS_URL` | `kube_api_url` is set from `KUEUE_METRICS_URL`. |
| `kueue_cluster_queues` | `KUEUE_METRICS_CLUSTER_QUEUES` | Queue list parsed from comma-separated value. |
| `kueue_cohort` | `KUEUE_METRICS_COHORT` | Cohort name copied. |

**Important:** Aliases do **not** override non-empty primary fields. Setting a
wrong `METRICS_KUBE_API_URL` cannot be corrected later by adding
`KUEUE_METRICS_URL` in the same process. Fix the primary variable or clear it.
Token, TLS, and timeout settings remain `METRICS_*` only unless separately
documented.

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

## Kueue mode note

With `METRICS_PROVIDER_MODE=kueue`, the platform route reads the Kubernetes API
directly. User and session routes still require **Prometheus** when callers use
them; `METRICS_PROMETHEUS_URL` is not validated at startup. Health checks and
platform-only smoke tests can succeed without Prometheus configured.

## Cluster RBAC (Helm)

When `rbac.create` is true, the chart installs a `ClusterRole` and
`ClusterRoleBinding` scoped to this release. Uninstalling the Helm release may
leave cluster-scoped RBAC unless your pipeline runs `helm uninstall` with
resources pruned; treat orphaned bindings as an operational cleanup item, not
an application defect.
