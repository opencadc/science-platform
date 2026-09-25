# Metrics environment contract

Metrics is deployed as one asynchronous FastAPI service. Environment-specific
overlays supply its external dependencies and configuration. The wire contract
lives in [`specs.md`](specs.md). The
[Confluence API Contract](https://herzberg.atlassian.net/wiki/spaces/C/pages/2690809875/API+Contract)
remains product-design authority; git specs may lead Confluence when they
diverge. Platform-owned labels live in
[`skaha/docs/labels.md`](../../skaha/docs/labels.md).

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
export METRICS_PLATFORM_NAME='canfar'
export METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES='["cq-astronomy","cq-physics"]'
export METRICS_PROVIDERS__KUEUE__NAMESPACES='["canfar-workloads","canfar-workloads-extra"]'
```

`METRICS_CLUSTER_NAME` is mandatory at deployment time. It must be a real
lower-case DNS identity, not an `unknown` sentinel. The value is part of every
cache identity and must match the `cluster` identity used by the Prometheus or
Mimir series queried by the optional efficiency provider.

`METRICS_PLATFORM_NAME` (default `canfar`) is the only accepted
`/metrics/platform/{platform}` path value; a mismatch returns 404.

`CLUSTER_QUEUES` is the complete Platform set. Each named ClusterQueue must be
readable and maps to one Community. `NAMESPACES` is the complete namespace set
searched for User LocalQueues, Session Jobs and Pods, and PodMetrics. The service lists LocalQueues
in every configured namespace and selects exact `canfar.net/username` labels.

Kubernetes endpoint, credentials, and CA trust come from the in-cluster
ServiceAccount or kubeconfig. The Kueue API contract is
`kueue.x-k8s.io/v1beta2`.

## External dependencies

```bash
export METRICS_CLUSTER_NAME='cluster.example'
export METRICS_REDIS_URL='rediss://redis.example/0'
export METRICS_CACHE__KEY_SECRET="$(python -c 'import secrets; print(secrets.token_urlsafe(32))')"
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

The endpoint must be an HTTP(S) origin. Metrics POSTs to `{BASE_URL}/api/v1/query`
and never accepts a caller-provided PromQL expression, URL, or header map. The
Mimir tenant value is one typed deployment setting, not an arbitrary proxy
header.

## Optional OTLP metrics

```bash
export METRICS_OTEL__METRICS_ENABLED='true'
export METRICS_OTEL__EXPORTER_OTLP_ENDPOINT='https://otel.example/v1/metrics'
```

The app exports application-state metrics only when metrics export is enabled
**and** an endpoint is provided; enabling export without an endpoint is a
startup error. It does not export OTLP traces or logs, and
there are no trace/log enable settings. The endpoint may be an external
Collector, Alloy, or compatible OTLP metrics receiver. The production chart
does not install any receiver.

## Other settings

| Variable | Default | Bound | Meaning |
| --- | --- | --- | --- |
| `METRICS_HOST` | `0.0.0.0` | host name or IP | Listen address |
| `METRICS_PORT` | 8000 | 1–65535 | Listen port |
| `METRICS_LOG_LEVEL` | `info` | `critical`, `error`, `warning`, `info`, `debug`, `trace` | Log level |
| `METRICS_STARTUP_VALIDATION_TIMEOUT_SECONDS` | 60 | ≤ 300 | Bound on each startup probe and readiness validation |
| `METRICS_PROVIDERS__KUEUE__KUBE_REQUEST_TIMEOUT_SECONDS` | 5 | ≤ 300 | One Kubernetes API request |
| `METRICS_PROVIDERS__KUEUE__KUEUE_API_VERSION` | `kueue.x-k8s.io/v1beta2` | that value only | Pinned Kueue API |
| `METRICS_PROVIDERS__PROMQL__REQUEST_TIMEOUT_SECONDS` | 5 | ≤ 300 | Upper bound of one efficiency read (also limited by the fill) |
| `METRICS_PROVIDERS__PROMQL__MAX_SAMPLE_AGE_SECONDS` | 300 | ≤ 7 days | Oldest accepted instant sample |
| `METRICS_PROVIDERS__PROMQL__FUTURE_SAMPLE_TOLERANCE_SECONDS` | 30 | ≤ 1 hour | Accepted clock skew for sample times |
| `METRICS_PROVIDERS__PROMQL__MAX_RESPONSE_BYTES` | 4 MiB | ≤ 16 MiB | Largest accepted PromQL response |
| `METRICS_PROVIDERS__PROMQL__MIMIR_TENANT_ID` | — | 1–150 characters | Sent as `X-Scope-OrgID` |
| `METRICS_OTEL__SERVICE_NAME` | `canfar-metrics` | 1–128 characters | OTLP `service.name` |
| `METRICS_OTEL__EXPORT_INTERVAL_MILLIS` | 60000 | ≤ 1 hour | OTLP export interval |
| `METRICS_OTEL__DEPLOYMENT_ENVIRONMENT` | `unknown` | 1–63 characters | OTLP `deployment.environment.name` |
| `METRICS_OTEL__KUBERNETES_NAMESPACE` | `unknown` | DNS label | OTLP `k8s.namespace.name` |
| `METRICS_OTEL__POD_UID` | host name | 1–128 characters | OTLP `service.instance.id`; the charts set the pod UID |

## Cache windows

Windows are fixed by surface in `FRESHNESS_POLICIES`
(`src/metrics/cache/models.py`) and are not environment settings:

| Surface | Fresh | Stale (Redis TTL) |
| --- | ---: | ---: |
| Platform | 5m | 10m |
| User | 2m | 4m |
| Community | 5m | 10m |
| Session | 30s | 60s |

A snapshot has two stages only. It is written with a Redis TTL equal to its
stale window, measured from the start of its fill, and is gone when that TTL
ends; nothing is retained for recovery. A stale snapshot is served while
exactly one request, on one replica, refreshes it. Cold requests share one
fill. Missing User, Community, and Session subjects are published as an
authenticated not-found for the fresh window, so every replica returns the
same 404 without re-reading the source. The full request flow is in
[`specs.md`](specs.md#cache).

## Cache tuning

| Variable | Default | Bound | Meaning |
| --- | ---: | --- | --- |
| `METRICS_CACHE__KEY_SECRET` | — | ≥ 32 bytes, not a placeholder or one repeated character | Key digests and payload MACs. Rotating it moves every subject to new keys: the first requests refill, and old keys expire within their stale window |
| `METRICS_CACHE__REDIS_COMMAND_TIMEOUT_SECONDS` | 0.5 | ≤ 30 | Connect and socket timeout of each Redis command; a dropped connection is retried once |
| `METRICS_CACHE__FILL_TIMEOUT_SECONDS` | 10 | ≤ 300 | One source fill, including optional enrichment |
| `METRICS_CACHE__COLD_GET_TIMEOUT_SECONDS` | 15 | ≤ 600 | How long a cold request waits for another replica's fill |
| `METRICS_CACHE__FAILURE_COOLDOWN_SECONDS` | 5 | ≤ 60 | Pause after a failed fill; capped at the surface's fresh window |
| `METRICS_CACHE__L1_MAX_ENTRIES` | 128 | ≤ 10000 | Snapshots each surface keeps per replica for Redis outages (four surfaces per replica) |
| `METRICS_REDIS_KEY_PREFIX` | `metrics:` | 1–128 printable characters, no whitespace | Prefix of every key |

The fill lease is `FILL_TIMEOUT + 2 × REDIS_COMMAND_TIMEOUT + 2` seconds
(13 seconds by default). Startup rejects a configuration where the lease is
not shorter than `COLD_GET_TIMEOUT` (waiters could never replace a crashed
owner) or not shorter than 60 seconds (the Session stale window).

Keys look like `metrics:<revision>:<source>:<query>:<surface>:{<digest>}:value`
and `…:lease`. The hash tag keeps both keys of a subject in one Redis Cluster
slot. The Lua scripts run with `EVALSHA`, falling back to `EVAL` when Redis
does not have the script cached (first use, or after a restart); Redis must
allow both.

HTTP status codes and `Ready`/`Cached` reasons are defined in
[`specs.md`](specs.md). During a Redis outage a replica serves only snapshots
it already holds, never past their Redis TTL; otherwise 503 with
`Retry-After: 1`. The source is never read without a Redis lease.

## Startup validation

The process exits with status 2 before serving when a retired name is set,
when a setting is invalid, or when `METRICS_CACHE__KEY_SECRET` is a
placeholder. It prints one line per problem and never a value. Retired names
are reported first, alone; fix them and the remaining problems are reported
on the next start. Cache-contract problems (placeholder secret, lease longer
than the cold wait) name `METRICS_CACHE`. Three separate runs:

```text
metrics: invalid configuration: METRICS_OTEL_METRICS_ENABLED is no longer read; use METRICS_OTEL__METRICS_ENABLED
```

```text
metrics: invalid configuration: METRICS_CACHE__KEY_SECRET: Field required
```

```text
metrics: invalid configuration: METRICS_CACHE: Value error, key_secret is a placeholder; generate one with python -c 'import secrets; print(secrets.token_urlsafe(32))'
```

| Retired name | Replacement |
| --- | --- |
| `METRICS_OTEL_<FIELD>` (single underscore) | `METRICS_OTEL__<FIELD>` |
| `METRICS_ENVIRONMENT` | `METRICS_OTEL__DEPLOYMENT_ENVIRONMENT` |
| `METRICS_LOGLEVEL` | `METRICS_LOG_LEVEL` |
| `METRICS_CACHE__BACKEND`, `METRICS_CACHE__TTL_SECONDS`, `METRICS_CACHE__SCOPE_TTL_SECONDS` | removed; Redis and the windows are fixed |
| `METRICS_PROVIDERS__PROMQL__MAX_SERIES` | removed |
| `METRICS_PROVIDERS__KUEUE__KUBE_API_URL`, `__KUBE_API_TOKEN`, `__KUBE_VERIFY_TLS`, `__TOKEN_FILE`, `__CA_FILE`, `__KUBE_CLUSTERQUEUE_PATH` | removed; the ServiceAccount or kubeconfig supplies the API |
| `METRICS_CONFIG_FILE`, `METRICS_API_GROUP`, `METRICS_CACHE_CONTROL_PUBLIC`, `METRICS_SOURCES__PLATFORM` | removed |

`/readyz` becomes ready once Redis answers and every configured ClusterQueue
is readable and well formed, then stays ready until shutdown
([ADR-0012](adr/0012-latched-readiness.md)). A pod that cannot reach Redis
at all during startup exits instead, and Kubernetes restarts it. LocalQueue
and Job list access are checked at startup and logged, but do not gate
readiness; PromQL is not contacted until the first efficiency read.

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

In the `metrics-api` chart, the Kueue lists (`kueue.clusterQueues`,
`kueue.namespaces`), `clusterName`, `platformName`, the Redis and cache-key
Secret references, `promql.*`, and `otel.endpoint` are structured values. The
chart uses the Kueue lists for RBAC and renders all of them into the
environment. `env` passes any other `METRICS_*` setting through unchanged, and
setting one of the rendered keys there fails the render. The chart only
requires values; Metrics validates them at startup.

## Evidence boundary

Repository tests prove only the checks they run. A local Prometheus-compatible
fixture does not prove Mimir behavior, production Redis durability, cluster
RBAC, or deployment readiness until those gates are run in their target
environment.
