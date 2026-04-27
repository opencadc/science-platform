# Milestone M4: provider runtime architecture

This plan defines the fourth milestone for the CANFAR Metrics API roadmap. It
simplifies the runtime around complete metric source providers, async lifecycle
management, and a single active platform metric contract.

## Repository snapshot versus milestone target

The current code still spreads Kueue startup validation, object loading,
aggregation, and CPU or memory capacity behavior across startup, provider, and
service modules. It also exposes user and session routes that rely on partial
Kueue plus Prometheus composition. M4 closes when the runtime has one provider
seam per source, Kueue owns its complete platform implementation, and inactive
user and session surfaces are removed.

## Summary

M4 introduces `MetricsRuntime` as the app-level module that selects configured
sources, owns cache and HTTP client lifecycle, validates provider capabilities,
and exposes async metric methods to the FastAPI routes.

The default active source set is intentionally small:

```yaml
metrics:
  providers:
    kueue:
      kube_api_url: https://kubernetes.default.svc
      cluster_queues:
        - cq-proton
      cohort: cohort-atom
      token_file: /var/run/secrets/kubernetes.io/serviceaccount/token
      ca_file: /var/run/secrets/kubernetes.io/serviceaccount/ca.crt
      http:
        max_connections: 100
        max_keepalive_connections: 20
        keepalive_expiry_seconds: 30
        http2: true
  sources:
    platform: kueue
  cache:
    backend: redis
    ttl_seconds: 300
    scope_ttl_seconds:
      platform: 300
```

## In scope

This milestone delivers a smaller runtime interface and removes old partial
composition paths.

- Add `MetricsRuntime` as the FastAPI dependency behind platform routes.
- Replace partial provider protocols with complete metric provider contracts.
- Collapse `kueue.py`, `kueue_platform.py`, `kueue_api.py`, and `kueue_spec.py`
  into one deep Kueue source module, or keep helpers private to that module.
- Keep only `GET /api/v1/metrics/platform` and `GET /healthz` active.
- Delete user and session routes, hidden `/metrics` aliases, and old composed
  user/session orchestration.
- Add YAML configuration loading from `/etc/canfar/metrics/config.yaml`.
- Keep environment overrides as the highest-precedence configuration layer.
- Add `docs/examples/metrics.config.yaml` as a committed deployer example.
- Add `pyyaml` with `uv add pyyaml` during implementation.
- Remove `niquests` if no active code uses it after the async HTTP reset.

## Out of scope

This milestone is an architecture reset, not a feature expansion.

- Kubernetes-backed quota and workload metric implementation. The first
  follow-on scope is M5 interactive quota.
- User, session, or capacity metric APIs. Future milestones add complete metric
  contracts when a provider can return the full model.
- External plugin loading for arbitrary provider packages.
- Provider-specific cache backends or provider-owned cache key formats.
- Long-lived watch or informer based Kueue synchronization.

## Dependencies

This milestone builds on the platform contract and current package realignment.

- M1 delivery foundation.
- M2 Kueue platform behavior and quantity contract.
- M3 layered FastAPI package structure.
- Current Kubernetes-first deployment path through Helm.

## Configuration model

The runtime configuration hierarchy is hardcoded defaults, YAML file, then
environment overrides. Missing YAML is allowed by default so local and CI
workflows can run from defaults plus environment variables.

The canonical file path is `/etc/canfar/metrics/config.yaml`. The YAML file
must contain a top-level `metrics:` mapping. Set
`METRICS_REQUIRE_CONFIG_FILE=true` when an environment must fail if the file is
missing.

Environment variables use the `METRICS_` prefix and nested `__` delimiters. For
example, `METRICS_PROVIDERS__KUEUE__COHORT=cohort-atom` overrides the YAML
value. M4 removes legacy flat aliases such as `METRICS_KUEUE_COHORT` and
`KUEUE_METRICS_COHORT`.

Provider configuration stays typed. `ProviderConfigs` contains `kueue`,
`prometheus`, and `kube` fields, each with a provider-specific Pydantic config
model. `SourceConfig` contains only active M4 scopes, starting with typed
`platform` source selection.

Raw secrets must not be placed in ConfigMap-backed YAML. Provider config can
reference `token_file` and `ca_file`, and direct environment overrides can
provide emergency values when needed.

## Target module map

The implementation must make the runtime seams explicit and keep Kueue
semantics local to the Kueue source module.

- `src/metrics/core/settings.py`: `MetricsConfig`, `ProviderConfigs`,
  `SourceConfig`, `CacheConfig`, `HttpClientConfig`, and YAML source loading.
- `src/metrics/core/runtime.py`: `MetricsRuntime`, async startup/shutdown,
  source validation, client lifecycle, cache policy, telemetry, and error
  mapping.
- `src/metrics/providers/base.py`: `MetricScope`, `ProviderMetrics`,
  `UnsupportedMetricScope`, and the provider protocol.
- `src/metrics/providers/kueue.py`: `KueueProviderConfig`, `KueueProvider`,
  `KueueMetrics`, Kueue object loading, startup checks, platform aggregation,
  and provider fingerprinting.
- `src/metrics/providers/prometheus.py`: `PrometheusProviderConfig`,
  `PrometheusProvider`, and `PrometheusMetrics` with no supported scopes in M4.
- `src/metrics/providers/kube.py`: `KubeProviderConfig`, `KubeProvider`, and
  `KubeMetrics` reserved for follow-on Kubernetes-backed scopes.
- `src/metrics/api/v1/routes.py`: platform route only, depending on
  `MetricsRuntime`.

## Provider contract

Providers must return complete internal metric models for every supported
scope. The runtime must not compose partial capacity and usage results across
providers.

The base metrics class provides explicit capability metadata and concrete
unsupported defaults:

```python
class MetricScope(StrEnum):
    PLATFORM = "platform"


class ProviderMetrics:
    supported_scopes: frozenset[MetricScope] = frozenset()

    async def platform(self) -> PlatformMetricsData:
        raise UnsupportedMetricScope(MetricScope.PLATFORM)
```

`KueueMetrics` advertises `platform` and returns `PlatformMetricsData`.
`PrometheusMetrics` has no supported scopes in M4. Future `user`, `session`,
or `capacity` scopes extend `MetricScope`, `ProviderMetrics`, schemas, routes,
cache TTL config, and tests together.

## Async runtime lifecycle

All provider lifecycle and metric methods are async from day one. Constructors
must remain synchronous and must not perform network I/O.

`MetricsRuntime` creates one long-lived `httpx.AsyncClient` per active upstream
provider. Clients are opened before active provider startup checks and closed
during app shutdown. Providers receive their client through construction and
must not instantiate `httpx.AsyncClient` internally.

Startup validation runs active providers concurrently with deterministic error
reporting. The same client instance is used for startup checks and request-time
metrics so auth, TLS, timeout, and pooling policy stay behind one seam.

Runtime instantiates providers only when they are configured or referenced by an
active source. M4 creates HTTP clients only for providers referenced by active
sources. A configured but unused Prometheus provider must validate its config
shape without opening a client or running startup checks.

## HTTP pool policy

Provider config owns upstream HTTP pool settings, while runtime owns client
construction. Kueue and Prometheus use separate clients because they have
different URLs, auth, TLS, headers, timeouts, and pool sizing needs.

Kueue fan-out can create many simultaneous Kubernetes API requests. M4 must
make pool sizing configurable, but deeper optimizations such as list-and-filter
reads, per-provider concurrency limits, or Kubernetes API backpressure controls
are deferred future work and must be recorded in implementation notes.

## Cache policy

Cache remains one app-level module selected by `metrics.cache`. Providers do
not build or own cache backends.

Runtime owns cache key format and providers supply only a stable fingerprint.
Cache TTL is typed by metric scope with a global default:

```yaml
metrics:
  cache:
    backend: redis
    ttl_seconds: 300
    scope_ttl_seconds:
      platform: 300
```

Future scopes add typed fields to the scope TTL model when the public metric
contract is added.

## Route surface

M4 narrows the public interface to implemented contracts only.

- Keep `GET /api/v1/metrics/platform`.
- Keep `GET /healthz`.
- Delete `GET /api/v1/metrics/users/{user}`.
- Delete `GET /api/v1/metrics/users/{user}/sessions/{uuid}`.
- Delete hidden aliases under `/metrics`.

Future user, session, or capacity APIs must be added as complete metric
contracts with provider support and source configuration in the same milestone.

## Contributor extension guide

The implementation must include a clear example for adding a provider, metric,
or API. The guide can live in this plan first and move to a dedicated developer
document after implementation.

To add a new provider source, a contributor must:

1. Add a typed provider config under `ProviderConfigs`.
2. Add a provider factory entry to the typed registry.
3. Implement `Provider`, `ProviderMetrics`, and async startup behavior.
4. Advertise only complete supported scopes.
5. Add source capability tests and config validation tests.

To add a new metric scope, a contributor must:

1. Add the scope to `MetricScope`.
2. Add a typed field to `SourceConfig`.
3. Add a complete internal data model and response envelope.
4. Add a route that depends on `MetricsRuntime`.
5. Add scope-specific cache TTL config.
6. Add provider support and capability validation tests.
7. Update architecture, design, specs, and example config docs.

To add a new API route, a contributor must map it to one complete provider
metric method. Partial source composition is not allowed at the runtime seam.

## Implementation phases

These phases sequence the architecture reset without preserving obsolete
interfaces.

1. Define the new config tree and YAML source loading.
2. Add the provider base contract and typed provider registry.
3. Implement `MetricsRuntime` with async lifecycle, cache, telemetry, source
   validation, and long-lived HTTP clients.
4. Deepen the Kueue provider so startup, object loading, aggregation, platform
   metrics, and fingerprinting live behind one source seam.
5. Replace route dependencies with `MetricsRuntime` and delete user/session
   routes and hidden aliases.
6. Update tests to fake providers or runtime seams instead of helper call sites.
7. Update docs and run review checkpoints before milestone closure.

## Validation plan

Validation must prove that the new seam is smaller and that behavior remains
correct for the platform contract.

- Run gate `harness-contracts`.
- Run gate `repository-coverage`.
- Run gate `harness-cli`.
- Run `uv run pytest -q`.
- Validate missing required Kueue config fails at startup.
- Validate unsupported configured sources fail at startup.
- Validate inactive configured providers do not create HTTP clients or run
  startup checks.
- Validate platform capacity and allocated units remain comparable.
- Validate YAML, environment override, and missing-file behavior.
- Validate route surface contains no user, session, or `/metrics` aliases.
- Validate `rg niquests src tests` is empty before removing the dependency.

## Review checkpoints

The milestone must run review before closure because it changes runtime
structure and public route surface.

- Architecture and depth review: confirms old partial provider seams are
  deleted and Kueue locality improved.
- API contract review: confirms user/session removal is intentional and
  platform response behavior remains stable.
- Operations review: confirms config precedence, default path, secret handling,
  async startup, and HTTP pool settings are workable.
- Test review: confirms tests target provider and runtime seams.

If reviewer disagreement blocks closure, ask for human arbitration instead of
encoding a compromise that weakens the module interfaces.

## Risks

This milestone deliberately removes existing route surface and changes runtime
configuration.

- **Consumer surprise:** User and session routes disappear. Mitigate by noting
  that the service is pre-release and by documenting the future extension path.
- **Config migration errors:** Legacy flat aliases are removed. Mitigate with a
  detailed example config and focused config tests.
- **Kubernetes API pressure:** Kueue fan-out can increase request bursts.
  Mitigate in M4 with configurable pool limits and record deeper fan-out
  optimization as future work.
- **Overbroad runtime facade:** `MetricsRuntime` can become shallow if provider
  logic leaks out. Mitigate by requiring complete provider metric methods.

## Operational controls

Operational controls keep the reset deployable in Kubernetes-first
environments.

- Default config path is `/etc/canfar/metrics/config.yaml`.
- Config file is optional unless `METRICS_REQUIRE_CONFIG_FILE=true`.
- Secrets come from Secret-backed files or environment overrides, not YAML.
- Active provider startup is fail-fast before the API accepts traffic.
- HTTP clients use configured connection pools and close during shutdown.
- Cache TTLs use typed per-scope config with a global default.

## Implementer handoff checklist

Use this checklist to close the milestone.

- [ ] New M4 config model, YAML loading, and example config are complete.
- [ ] `uv add pyyaml` has added YAML parsing to runtime dependencies.
- [ ] Runtime-owned `httpx.AsyncClient` instances are long-lived per active
  upstream.
- [ ] Kueue startup and platform aggregation use one source module.
- [ ] User/session routes and hidden aliases are removed.
- [ ] `CapacityProvider` and `UsageProvider` public protocols are deleted.
- [ ] Prometheus remains configurable with no supported scopes.
- [ ] `niquests` is removed if unused.
- [ ] Contributor extension guidance is present.
- [ ] Architecture, design, specs, environment, and Kueue docs match the
  delivered system.
- [ ] Review checkpoints and required gates pass.
