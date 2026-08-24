# ADR-0009: Incremental deepening of the existing Python package

## Status

Accepted.

## Decision

Evolve the existing `metrics/src/metrics` package in place. Keep its established
top-level modules—`api`, `core`, `providers`, `schemas`, and `services`—rather
than introducing a parallel `domain/application/ports/adapters` hierarchy.
The migration deepens existing modules behind smaller interfaces and removes
obsolete Platform-specific implementations after callers move.

The target grows from the current tree as follows:

```text
metrics/
  api/
    v1alpha1/          # replaces api/v1 at the hard cutover
  cache/               # grows from cache.py
    coordination.py    # freshness, L1, lease, waiter, and fill policy
    memory.py           # test adapter
    models.py           # versioned internal JSON snapshots
    redis.py            # production adapter and atomic operations
  core/
    factory.py          # FastAPI composition and exception mapping
    runtime.py          # owned resource lifecycle and readiness
    settings.py         # strict METRICS_* configuration
  dev/                  # new metrics-dev project entrypoint
  providers/
    kueue.py            # existing Platform source, narrowed to Kueue
    kubernetes.py       # Running Pod inventory and requests
    promql.py           # controlled phase-2 query transport
  schemas/
    metrics.py          # public v1alpha1 wire models only
    status.py           # Kubernetes Status wire model
  services/
    metrics.py          # deep get-Metrics use case
    models.py           # transport-neutral subjects and observations
    resources.py        # pure quantity and request arithmetic
    sources.py          # narrow source interfaces used by the service
  errors.py
  http_cache.py
  main.py
  telemetry/            # grows from telemetry.py as signals are added
```

The tree is directional:

```text
api -> services -> cache/source interfaces
                  ^
                  |
        providers and cache adapters

core composes and owns all of the above
```

`schemas` contains serialized HTTP models and does not flow into providers.
Providers normalize upstream documents into transport-neutral models from
`services.models`. FastAPI types remain inside `api` and `core.factory`.
Redis, kr8s, HTTPX, and OpenTelemetry SDK types do not cross the service
interfaces.

The primary application module exposes one deep interface equivalent to:

```python
await metrics_service.get(subject) -> MetricsResult
```

It hides source selection, namespace completeness, cache state, stale policy,
coalesced fills, report assembly, and condition derivation. The cache module
exposes one deep get-or-fill interface and hides Redis key construction, HMAC
subject digests, immutable snapshots, latest pointers, leases, waiter
coordination, L1 fallback, and expiry states.

Source interfaces are narrow and owned by the calling service: Platform quota,
Running workload observation, and lifetime accounting. Production kr8s/HTTPX
adapters and test fakes make these real seams. Do not introduce registries,
generic DAOs, managers, or a framework dependency container.

Use explicit constructor composition in `MetricsRuntime.from_settings`.
Standard Python 3.13 `asyncio` primitives own concurrency and deadlines. Pure
quantity, resource-request, aggregation, condition, and serialization-input
transformations remain synchronous.

### Migration mapping

| Current module | In-place evolution |
| --- | --- |
| `api/v1/routes.py` | Hard rename to `api/v1alpha1/routes.py`; route calls only the shared Metrics service. |
| `core/factory.py` | Retain as FastAPI composition root, lifespan, probes, instrumentation, and Status exception mapping. |
| `core/runtime.py` | Retain for resource ownership/readiness; move cache algorithms into the cache module. |
| `core/settings.py` | Retain and extend with strict `kueue`, `kubernetes`, `promql`, cache, timeout, and OTel settings. |
| `providers/kueue.py` | Retain Kueue transport/normalization; move reusable resource arithmetic to `services/resources.py`. |
| `schemas/metrics.py` | Replace legacy Platform wire types with the shared public `Metrics` models; providers stop importing it. |
| `services/platform.py` | Replace with `services/metrics.py`; delete the Platform-only service after the hard cutover. |
| `cache.py` | Convert to a package when lease/snapshot coordination lands; preserve temporary re-exports only within the migration ticket. |
| `telemetry.py` | Convert to a package when traces, correlated logging, and multiple instruments land. |
| `errors.py`, `http_cache.py`, `main.py` | Retain and evolve without gratuitous relocation. |

No empty future `operator` package is created. When there is a concrete Kopf
reconciliation target, add a separate entrypoint that calls the same service
interfaces and does not import FastAPI routes.

## Consequences

- Existing code, tests, and maintainer knowledge have a direct migration path.
- The service and cache modules gain depth: callers learn one interface while
  complex coordination remains local.
- Tests migrate to the same interfaces used by callers. Tests coupled to
  obsolete Platform internals are deleted as the deeper interface replaces
  them; compatibility wrappers do not become permanent architecture.
- Package moves happen only when an existing module becomes too deep for one
  file (`cache.py` and `telemetry.py`), not to satisfy an abstract layering
  template.

## References

- [`0008-api-only-async-core.md`](0008-api-only-async-core.md)
- [`../canfar-metrics-v1alpha1-design.md`](../canfar-metrics-v1alpha1-design.md)
