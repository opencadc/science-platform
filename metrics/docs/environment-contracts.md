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

Docker Compose is no longer part of the supported development contract.

## 12-factor configuration model

All runtime behavior remains environment-driven. Settings use nested Pydantic
models under `platform` and `user` (for example `platform.kueue.cluster_queues`).
`pydantic-settings` reads nested keys using the delimiter `__`, for example
`METRICS_PLATFORM__KUEUE__CLUSTER_QUEUES`. Legacy flat variables such as
`METRICS_KUEUE_CLUSTER_QUEUES`, `METRICS_KUBE_API_URL`, and `METRICS_PROMETHEUS_URL`
are merged at load time when nested values are absent (see
`Settings._merge_legacy_environment` in `src/metrics/core/settings.py`).

## Operator alias precedence (`KUEUE_METRICS_*` versus `METRICS_*`)

The application loads nested `METRICS_*` fields, then applies fill-only aliases
from process environment in `Settings._merge_legacy_environment` in
`src/metrics/core/settings.py`.

| If this nested field is empty | And this env var is set | Then |
| --- | --- | --- |
| `platform.kueue.kube_api_url` | `KUEUE_METRICS_URL` | `kube_api_url` is set from `KUEUE_METRICS_URL`. |
| `platform.kueue.cluster_queues` | `KUEUE_METRICS_CLUSTER_QUEUES` | Queue list parsed from comma-separated value. |
| `platform.kueue.cohort` | `KUEUE_METRICS_COHORT` | Cohort name copied. |

Aliases do not override non-empty primary fields.

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
  `metric-v*`, multi-arch `linux/amd64` and `linux/arm64`.
- Non-tag CI does not publish release images.

## Platform sources note

Platform metrics always use the Kueue HTTP collectors plus Prometheus-backed
usage for user and session routes. Startup validation requires both Kubernetes
(Kueue scope) and Prometheus endpoints to be configured.

## Cluster RBAC (Helm)

When `rbac.create` is true, the chart installs a release-scoped `ClusterRole`
and `ClusterRoleBinding`. Treat leftover cluster-scoped RBAC after uninstall as
an operational cleanup task.
