# Environment contracts (Metrics service)

This document records how environment names map to runtime behavior and what
this repository owns for local and delivery workflows.

## Canonical `METRICS_ENVIRONMENT` values

The application accepts exactly these runtime tokens (case-insensitive input is
normalized):

| Value | Use |
| --- | --- |
| `dev` | Local Kubernetes-first development and integration setup. |
| `integration` | CI-backed or shared integration clusters. |
| `staging` | Pre-production cluster rollout. |
| `production` | Production cluster rollout. |

Legacy aliases remain accepted for now:

| Legacy input | Normalized to |
| --- | --- |
| `int` | `integration` |
| `prod` | `production` |

Any other value is rejected at settings validation time.

## Environment runtime contract

The service is Kubernetes-first in every environment.

- `dev` requires kind, Helm, and `kubectl`. Test and verification flows assume
  you can create or use a one-node kind cluster, install Kueue charts, apply
  ClusterQueue/Cohort objects, deploy the metrics chart, and run Redis in the
  cluster deployment path.
- `integration`, `staging`, and `production` use an already operating
  Kubernetes cluster. This repository deploys the service via Helm with
  environment-specific values such as queue configuration and Redis endpoint.
  These environments do not assume local kind provisioning.

Docker Compose is not part of the supported development contract.

## 12-factor configuration model

Runtime behavior is driven by `METRICS_*` environment variables, optional YAML
from `METRICS_CONFIG_FILE`, and (when present) Kubernetes secret file sources,
in the order `Settings` defines. For the same field, `METRICS_*` **wins** over
both the optional YAML file and secret-file values (environment is applied
before those lower-priority `Settings` sources).

**Default file path:** `/etc/canfar/metrics/config.yaml`. If the file is
absent, settings fall back to defaults and env unless
`METRICS_REQUIRE_CONFIG_FILE` is set to a true value, in which case startup
fails when the file is missing.

**YAML shape:** When the file exists and the document is non-empty, the top
level must include a `metrics:` mapping (see `docs/examples/metrics.config.yaml`).

**Settings model:** The root model exposes `providers`, `sources`, and `cache`
(not legacy `platform.*` / `user.*` trees). Nested Pydantic fields are set with
`METRICS_` + the nested name using `__` as the delimiter, for example:

- `METRICS_PROVIDERS__KUEUE__COHORT` → `providers.kueue.cohort`
- `METRICS_PROVIDERS__KUEUE__KUBE_API_URL` → `providers.kueue.kube_api_url`
- `METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES` → must be a **JSON array of
  strings** (for example `'["cq-proton","cq-neutron"]'`), not a comma-separated
  plain string
- `METRICS_SOURCES__PLATFORM` → `sources.platform` (which provider key backs
  platform metrics; M4 uses `kueue`)
- `METRICS_CACHE__SCOPE_TTL_SECONDS` → must be a **JSON object** mapping scope
  names to integers (for example `'{"platform":300}'`); the `platform` entry
  overrides the default cache TTL for `GET /api/v1/metrics/platform`
- `METRICS_CACHE__BACKEND` / `METRICS_CACHE__TTL_SECONDS` → `cache` fields

`METRICS_REDIS_URL` and other top-level `Settings` fields use the `METRICS_`
prefix without extra nesting. Legacy flat aliases such as `METRICS_KUEUE_*` and
`KUEUE_METRICS_*` are **not** part of the M4 settings surface; configure Kueue
through `METRICS_PROVIDERS__KUEUE__*`.

`providers.prometheus` and `providers.kube` may be present in YAML and pass
validation, but M4 does not open HTTP clients to them; only the active
platform source (Kueue) runs startup checks and platform aggregation.

## Cluster RBAC (Helm)

When `rbac.create` is true, the chart installs a release-scoped `ClusterRole`
and `ClusterRoleBinding`. Treat leftover cluster-scoped RBAC after uninstall as
an operational cleanup task.

## Repository ownership boundaries

This repository owns:

- application code and tests,
- Helm chart contract for the metrics service,
- local dev and CI scripts for Kubernetes-backed validation, and
- milestone and architecture docs for service behavior.

Higher-environment overlay repositories own promotion pipelines and environment
specific deployment overlays.

## Image and release contract

- Release images: `images.opencadc.org/platform/metrics` on Git tags
  `metrics-v*`, multi-arch `linux/amd64` and `linux/arm64`.
- Non-tag CI does not publish release images.
