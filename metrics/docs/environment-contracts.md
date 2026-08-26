# Metrics environment contract

Metrics is deployed as one asynchronous FastAPI service. Environment-specific
overlays supply its external dependencies and configuration. The approved
product design is canonical in the [Confluence API Contract](https://herzberg.atlassian.net/wiki/spaces/C/pages/2690809875/API+Contract).
Queue identity, admission stamping, and optional efficiency label propagation
are defined in [`metadata-labels.md`](metadata-labels.md).

## Environment names

The deployment environments are `dev`, `integration`, `staging`, and
`production`. `dev` may use the reusable kind cluster; higher environments use
an existing Kubernetes cluster and environment-owned overlays. Docker Compose
is not a supported runtime contract.

## Required Kueue configuration

Settings use the `METRICS_` prefix and `__` as the nested delimiter. List values
are JSON arrays, not comma-separated strings.

```bash
export METRICS_CLUSTER_NAME='kind-metrics'
export METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES='["cq-astronomy","cq-physics"]'
export METRICS_PROVIDERS__KUEUE__NAMESPACES='["canfar-workloads","canfar-workloads-extra"]'
```

`METRICS_CLUSTER_NAME` is mandatory at deployment time. It must be a real
lower-case DNS identity, not an `unknown` sentinel. The value is part of every
cache identity and must match the `cluster` identity used by the Prometheus or
Mimir series queried by the optional efficiency provider.

`CLUSTER_QUEUES` is the complete Platform set. Each named ClusterQueue must be
readable and maps to one Community. `NAMESPACES` is the complete namespace set
searched for User LocalQueues. The service lists LocalQueues in every
configured namespace and selects exact `canfar.net/username` labels.

Kubernetes endpoint, credentials, and CA trust come from the in-cluster
ServiceAccount or kubeconfig. The Kueue API contract is
`kueue.x-k8s.io/v1beta2`.

## External dependencies

```bash
export METRICS_CLUSTER_NAME='cluster.example'
export METRICS_REDIS_URL='rediss://redis.example/0'
export METRICS_CACHE__KEY_SECRET='<secret-reference-or-injected-value>'
```

Redis is one shared external cache for every Metrics replica and every surface.
Production supplies its availability, persistence, replication, backup, and
eviction policy. The production Helm charts consume an operator-provided Redis
URL Secret and cache-integrity Secret; they do not chart-own Redis or accept a
plaintext URL/key fallback. There is no cache backend selector: Redis is the
only supported runtime cache, and `METRICS_CACHE__BACKEND` is not an application
setting.

Optional Prometheus/Mimir support is activated solely by the presence of its
endpoint. Set `METRICS_PROVIDERS__PROMQL__BASE_URL` to enable the fixed
server-owned query catalog; leave it absent to disable efficiency. There is no
separate PromQL enable setting:

```bash
export METRICS_PROVIDERS__PROMQL__BASE_URL='https://mimir.example'
export METRICS_PROVIDERS__PROMQL__MIMIR_TENANT_ID='canfar'
```

The endpoint must be an HTTP(S) origin. Metrics appends the supported instant
query path and never accepts a caller-provided PromQL expression, URL, or
header map. The Mimir tenant value is one typed deployment setting, not an
arbitrary proxy header.

## Optional OTLP metrics

```bash
export METRICS_OTEL__METRICS_ENABLED='true'
export METRICS_OTEL__EXPORTER_OTLP_ENDPOINT='https://otel.example/v1/metrics'
```

The app exports application-state metrics only when metrics export is enabled
and an endpoint is provided. It does not export OTLP traces or logs, and there
are no trace/log enable settings. The endpoint may be an external Collector,
Alloy, or compatible OTLP metrics receiver. The production chart does not
install any receiver.

## Cache windows

Freshness and serviceability are fixed by surface:

| Surface | Fresh | Serviceable stale | Retained for recovery |
| --- | ---: | ---: | ---: |
| User | 2m | 3m | 5m |
| Community | 5m | 10m | 15m |
| Platform | 5m | 30m | 60m |

The service uses one stable Redis lease per surface and subject. Different
subjects may fill concurrently. Stale serviceable snapshots may be returned
while one request refreshes them; cold requests share the winner's result.
Missing User and Community subjects use a bounded authenticated terminal outcome
with the same subject retention policy, so followers reproduce the winner's 404.

## Ownership boundary

The production Helm chart owns the Metrics Deployment, Service, dedicated
ServiceAccount, least-privilege RBAC, configuration references, and probes. It
does not own:

- Redis;
- kube-state-metrics;
- Prometheus or Mimir; or
- an OpenTelemetry Collector/Alloy OTLP metrics receiver.

Disposable test profiles may provision those dependencies to validate the
integration. A test fixture is not a production dependency claim.

## Failure behavior

- Missing User LocalQueues: 404.
- Missing Community-labelled configured ClusterQueues: 404.
- Missing, inaccessible, or malformed configured primary ClusterQueues or
  LocalQueue dependencies: use a serviceable snapshot when available,
  otherwise 503.
- Prometheus/Mimir failure while `METRICS_PROVIDERS__PROMQL__BASE_URL` is
  present: HTTP 200 with Kueue values, efficiency omitted, and
  `Ready=False`/`PartialData`.
- Redis outage: serve only a known serviceable snapshot; otherwise 503. Do not
  bypass Redis with an uncoordinated fill per request.

## Evidence boundary

Repository tests prove only the checks they run. A local Prometheus-compatible
fixture does not prove Mimir behavior, production Redis durability, cluster
RBAC, or deployment readiness until those gates are run in their target
environment.
