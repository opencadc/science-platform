# ADR-0008: API-only async core and two-loop development

## Status

Accepted.

## Decision

The initial Metrics runtime is one FastAPI API process over a framework-neutral
application core. Every external I/O and coordination path is asynchronous;
pure deterministic transformations remain synchronous. FastAPI is an inbound
adapter, kr8s is the Kubernetes adapter, and Redis is the required deployed
cache. A GET reads Redis first and, on an unavailable cache entry, calculates
the report through the application core before caching it. A separate refresh
worker and a Kopf operator are future work and are not initial runtime
components.

Concurrency and deadlines use the Python 3.13 standard-library `asyncio`
primitives: `TaskGroup`, `timeout`, `Event`, `Lock`, and `Semaphore`. The core
does not expose an AnyIO or Trio portability interface. Dependencies are
accepted through explicit constructors and composed by the runtime; there is
no framework dependency container or service locator.

Kopf is reserved for a future executable that reconciles Kubernetes-owned
resources such as APIService registration, configuration CRDs, certificates,
or managed workloads. It does not run inside FastAPI and is not used as a
general scheduler for metrics calculation. Future entrypoints reuse the same
application core rather than calling FastAPI or importing HTTP adapters.

Development has two supported loops over one reusable kind cluster: a fast
host process or FastAPI TestClient uses the kind kubeconfig and real local
dependencies, while the authoritative smoke deploys the production container
into kind through Helm and calls it over HTTP. An external `docker run`
container connecting back into kind and Docker Compose are not supported
development contracts.

The developer surface is an installed Python console entrypoint declared in
`metrics/pyproject.toml` under `[project.scripts]` and invoked through `uv`, for
example `uv run metrics-dev up`. One `metrics-dev` command owns explicit
`up`, `run`, `image`, `fixtures`, `smoke`, `down`, `reset`, and `destroy`
subcommands. It may call Python libraries or checked-in declarative manifests;
there is no Bash task dispatcher or invented uv task-table convention.

The canonical check registry and quality gate is `uv run pre-commit`, using the
project-managed development dependencies. The normal commit stage invokes lock
validation, the locked local Ruff and ty commands (`ruff check --fix`,
`ruff format`, and `ty check`), and the fast TestClient contract smoke. Manual
pre-commit stages own the image, kind, and OTel smoke gates; `metrics-dev`
subcommands and CI invoke those stages when required. CI does not maintain a
second hand-written list of equivalent commands.

ty replaces mypy immediately. Its exact beta version is locked, and a ty
upgrade is an explicit toolchain change whose diagnostic differences are
reviewed. Metrics does not maintain parallel type-checker baselines.

The production image standardizes on `python:3.13-slim`, a pinned uv build
tool, and `uv.lock`-derived non-editable production dependencies. It runs as a
non-root user with one Uvicorn worker per container; horizontal concurrency is
provided by Pod replicas.

The supported Kubernetes floor is 1.33. The reproducible local and CI baseline
pins kind `v0.32.0`,
`kindest/node:v1.33.12@sha256:3f5c8443c620245e4d355cfe09e96a91ead32ceaa569d3f1ca9edf0cb2fe2ff4`,
and Kueue `v0.19.2`; version changes are explicit toolchain updates. The fast
`core` profile adds Redis, an OpenTelemetry Collector with a debug export, and
deterministic labelled workload fixtures. The optional `accounting` profile
adds kube-state-metrics, Prometheus, Metrics-owned recording rules, and
accounting fixtures. Mimir, Grafana, Tempo, and Loki are not required for the
default loop; Mimir compatibility is a separate optional or CI profile.

Fixtures own pinned ResourceFlavors, ClusterQueues, LocalQueues, a Cohort, and
a dedicated `canfar-workloads` workload namespace, following the existing
local queue topology. Long-running Kueue Jobs create busy Running Pods carrying
the complete applicable label contract from `skaha/docs/labels.md`. They cover
Platform, User, Community, empty, stale, and incomplete scenarios. Tests may
create namespaced workload instances but do not install Kueue or rewrite
shared cluster configuration.

User and Community inventory calls are restricted to configured workload
namespaces; `app.kubernetes.io/managed-by=skaha`;
`app.kubernetes.io/part-of=canfar`; the exact canonical User or Community
label; and Pods whose phase is `Running`. Every configured namespace must
succeed before its values form a total. The initial design does not fetch an
unscoped cluster-wide Pod inventory or assume every namespace contains CANFAR
workloads.

The workload namespace list is required configuration at
`METRICS_PROVIDERS__KUBERNETES__WORKLOAD_NAMESPACES`, represented as a JSON
array. Namespace values may vary by deployment. Canonical provenance and
subject label keys are fixed application semantics and are not configurable.

Internal provider IDs are `kueue` for Platform quota, `kubernetes` for Running
Pod lifecycle and declared requests, and `promql` for controlled lifetime
accounting queries. They are configuration and telemetry vocabulary only and
never appear in public Metrics responses.

TestClient scenario tests are the dominant application suite, including
lifespan, routing, serialization, errors, and dependency integration. They are
not labelled container end-to-end tests because TestClient bypasses a real
socket. Narrow tests remain for dangerous calculation, cache-coordination,
completeness, and redaction invariants; a small deployed HTTP smoke proves the
image, Helm, service account, networking, Redis, and Kubernetes integration.

`/livez` reports only process and event-loop health. `/readyz` requires Redis
and enough reachable-source or cached-snapshot serviceability to honor each
enabled API surface. Invalid configuration fails startup. Redis unavailability
fails startup and readiness. Kubernetes, Kueue, or an optional accounting
source may be unavailable while the process remains live, but a surface with
neither a reachable source nor a serviceable snapshot is not ready and its
request returns the agreed partial response or 503. `/healthz` may exist only
as a temporary migration alias.

## Consequences

- API scaling does not introduce a second runtime role in this phase.
- Request-triggered cache fills still require cross-replica coalescing so a
  cache miss cannot multiply downstream Kubernetes or PromQL load.
- The host loop optimizes iteration speed; the in-kind loop remains the
  production-parity release gate.
- `metrics-refresh` and `metrics-operator` must be proposed as explicit future
  capabilities rather than hidden background tasks in the API process.
- `uv run metrics-dev reset` is the normal fast cleanup boundary; destructive
  cluster removal remains an explicit `uv run metrics-dev destroy` action.
- On a warm reusable local stack, each supported smoke-test command has a
  nominal completion budget below two minutes. Cold cluster creation and first
  image downloads are measured and reported separately.

## References

- [`0001-runtime-architecture.md`](0001-runtime-architecture.md)
- [`0005-redis-freshness-and-outages.md`](0005-redis-freshness-and-outages.md)
- [`0006-opentelemetry-contract.md`](0006-opentelemetry-contract.md)
- [FastAPI TestClient](https://fastapi.tiangolo.com/reference/testclient/)
- [Python project scripts](https://packaging.python.org/en/latest/specifications/pyproject-toml/#entry-points)
- [kind v0.32.0 release](https://github.com/kubernetes-sigs/kind/releases/tag/v0.32.0)
