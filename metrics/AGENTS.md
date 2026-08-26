# Metrics agent instructions

## Scope

- `src/metrics/` — the single asynchronous FastAPI Metrics service.
- `tests/` — unit and integration tests.
- `helm/metrics-api/` — the Metrics application chart. Redis, KSM,
  Prometheus/Mimir, and OTLP metrics receivers are external dependencies; disposable
  test fixtures may provide them.
- `docs/` — the Metrics glossary, ADRs, implementation contract, environment
  contract, and operator guidance.

Confluence remains canonical for the approved product design. Git documents
must describe the implementation of that design and must not resurrect the
superseded accounting, Cohort, or Running-Pod source model.

## Validation

From `metrics/`:

```bash
uv run ruff check src tests
uv run pytest --cov=src --cov-report=term-missing -m "not integration"
```

Cluster-backed checks use the pinned kind/Kueue environment and the supported
local lifecycle documented in [`docs/dev-setup.md`](docs/dev-setup.md).

## Runtime vocabulary

- User requests come from LocalQueues in the configured namespaces, selected by
  `canfar.net/username` and restricted to the configured ClusterQueues.
- Community requests come from configured ClusterQueues selected by
  `canfar.net/community`.
- Platform totals include every configured ClusterQueue. Cohorts are not a
  Metrics source.
- `flavorsReservation` is the source for public request totals;
  `reservingWorkloads` is the public workload count.
- Prometheus/Mimir is optional and receives fixed, server-owned PromQL for
  current CPU and memory efficiency. The presence of
  `METRICS_PROVIDERS__PROMQL__BASE_URL` enables it; absence disables it.
  Callers never provide PromQL.

## Configuration names

List-valued environment variables use JSON arrays. The two Kueue lists are:

```text
METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES
METRICS_PROVIDERS__KUEUE__NAMESPACES
```

Use one shared external Redis deployment for cache snapshots and distributed
single-flight. Do not add a producer, accounting database, second cache, or
production-owned monitoring stack.

## Documentation changes

When changing the domain model, update `CONTEXT.md`, the relevant ADR, and the
implementation-backed documents together. Use Google-style docstrings for
Python source changes and Conventional Commits for commits; this worker does
not create commits.
