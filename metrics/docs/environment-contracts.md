# Environment contracts (Metrics service)

This document records how environment names map to runtime behavior and what
this repository owns for local and delivery workflows.

## Environments

Deployment environments are `dev`, `integration`, `staging`, and `production`.
They are deployment concepts (values files, clusters), not an application
setting: the former `METRICS_ENVIRONMENT` field was removed as dead
configuration (nothing in the service read it).

## Environment runtime contract

The service is Kubernetes-first in every environment.

- `dev` requires kind, Helm, and `kubectl`. Test and verification flows assume
  you can create or use a one-node kind cluster, install Kueue charts, apply
  ClusterQueue fixture objects, deploy the metrics chart, and run Redis in the
  cluster deployment path.
- `integration`, `staging`, and `production` use an already operating
  Kubernetes cluster. This repository deploys the service via Helm with
  environment-specific values such as queue configuration and Redis endpoint.
  These environments do not assume local kind provisioning.

Docker Compose is not part of the supported development contract.

## 12-factor configuration model

Runtime behavior is driven by `METRICS_*` environment variables and (when
present) Kubernetes secret file sources; environment values override defaults
(ADR-0001). Configuration is environment-only — no file-based config source.

**Settings model:** The root model exposes `providers`, `sources`, and `cache`
(not legacy `platform.*` / `user.*` trees). Nested Pydantic fields are set with
`METRICS_` + the nested name using `__` as the delimiter, for example:

- `METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES` → must be a **JSON array of
  strings** (for example `'["cq-proton","cq-neutron"]'`), not a comma-separated
  plain string
- `METRICS_SOURCES__PLATFORM` → `sources.platform` (which provider key backs
  platform metrics; M4 uses `kueue`)
- `METRICS_CACHE__BACKEND` / `METRICS_CACHE__TTL_SECONDS` → `cache` fields

`METRICS_REDIS_URL` and other top-level `Settings` fields use the `METRICS_`
prefix without extra nesting. Legacy flat aliases such as `METRICS_KUEUE_*` and
`KUEUE_METRICS_*` are **not** part of the M4 settings surface; configure Kueue
through `METRICS_PROVIDERS__KUEUE__*`.

Only `providers.kueue` is accepted. Unknown provider blocks and source names
fail settings validation; `sources.platform` accepts only `kueue`. Kubernetes
endpoint, credentials, and CA are discovered by kr8s from the service account
or kubeconfig (ADR-0001) and are not settings. The reference chart
`values-dev.yaml` and `scripts/kind-values.yaml` use this same closed Settings
surface and are validated by the unit suite.

Borrowed/lending response expansion is out of scope for this delivery; platform
responses remain the existing `capacity` and `allocated` maps.

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
