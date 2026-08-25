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
- `METRICS_CACHE__KEY_SECRET` → required for Redis and at least 32 UTF-8 bytes
- `METRICS_PROVIDERS__PROMQL__ENABLED` and
  `METRICS_PROVIDERS__PROMQL__BASE_URL` → opt into the controlled
  Prometheus-compatible accounting source
- `METRICS_CACHE__REDIS_COMMAND_TIMEOUT_SECONDS` /
  `METRICS_CACHE__FILL_TIMEOUT_SECONDS` /
  `METRICS_CACHE__COLD_GET_TIMEOUT_SECONDS` → finite cache deadlines

`METRICS_REDIS_URL` and other top-level `Settings` fields use the `METRICS_`
prefix without extra nesting. Legacy flat aliases such as `METRICS_KUEUE_*` and
`KUEUE_METRICS_*` are **not** part of the M4 settings surface; configure Kueue
through `METRICS_PROVIDERS__KUEUE__*`.

The implemented provider blocks are `kueue`, `kubernetes`, and optional
`promql`. Unknown provider fields and source names fail settings validation;
`sources.platform` accepts only `kueue`. Kubernetes
endpoint, credentials, and CA are discovered by kr8s from the service account
or kubeconfig (ADR-0001) and are not settings. The reference chart
`values-dev.yaml` and `scripts/kind-values.yaml` use this same closed Settings
surface and are validated by the unit suite.

Redis is the deployed and local-development load boundary. The memory backend
is available only for injected tests. Platform snapshots are fresh for 5
minutes, serviceable through 30 minutes, and retained through 60 minutes; User
and Community use 2/10/15-minute boundaries.

Borrowed/lending response expansion is out of scope for this delivery; platform
responses remain the existing `capacity` and `allocated` maps.

When PromQL is enabled, its base URL is server-owned and Metrics issues only
the built-in User and Community instant-query templates. Optional
`METRICS_PROVIDERS__PROMQL__MIMIR_TENANT_ID` sets the single typed Mimir tenant
header. Raw queries, arbitrary headers, and caller-selected endpoints are not
configuration surfaces.

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
