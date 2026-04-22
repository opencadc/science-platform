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

- `dev` requires Minikube, Helm, and `kubectl`. Test and verification flows
  assume you can create or use a Minikube cluster, install Kueue charts, apply
  ClusterQueue/Cohort objects, deploy the metrics chart, and run Redis in the
  cluster deployment path.
- `integration`, `staging`, and `production` use an already operating
  Kubernetes cluster. This repository deploys the service via Helm with
  environment-specific values such as queue configuration and Redis endpoint.
  These environments do not assume local Minikube provisioning.

Docker Compose is no longer part of the supported development contract.

## 12-factor configuration model

All runtime behavior remains environment-driven. Current settings are supplied
through `METRICS_*` variables. M3 introduces the roadmap direction for nested
Pydantic configuration domains (`metrics.platform.*`, `metrics.user.*`) while
keeping deployment wiring environment-based through Helm values and env vars.

## Operator alias precedence (`KUEUE_METRICS_*` versus `METRICS_*`)

The application loads `METRICS_*` fields first, then applies fill-only aliases
from process environment in
`Settings._apply_operator_kueue_env_aliases` in `src/metrics/config.py`.

| If this field is empty | And this env var is set | Then |
| --- | --- | --- |
| `kube_api_url` | `KUEUE_METRICS_URL` | `kube_api_url` is set from `KUEUE_METRICS_URL`. |
| `kueue_cluster_queues` | `KUEUE_METRICS_CLUSTER_QUEUES` | Queue list parsed from comma-separated value. |
| `kueue_cohort` | `KUEUE_METRICS_COHORT` | Cohort name copied. |

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

## Kueue mode note

With `METRICS_PROVIDER_MODE=kueue`, platform metrics read Kubernetes/Kueue APIs.
User and session paths still rely on Prometheus configuration when those routes
are used.

## Cluster RBAC (Helm)

When `rbac.create` is true, the chart installs a release-scoped `ClusterRole`
and `ClusterRoleBinding`. Treat leftover cluster-scoped RBAC after uninstall as
an operational cleanup task.
