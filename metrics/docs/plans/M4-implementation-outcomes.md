# M4 provider runtime architecture — implementation outcomes

This document records what was delivered for
`PLAN_M4_provider_runtime_architecture.md`, how verification was run, and the
synthesized review (architecture, performance, maintainability, security,
FastAPI, Pydantic) used to arbitrate a single implementation path.

## Delivered

- **Configuration:** `MetricsProvider`-shaped tree under `Settings` with
  `ProviderConfigs` (kueue, prometheus, kube), `SourceConfig` (platform
  source), and `CacheConfig` (typed per-scope TTL overrides, including
  `scope_ttl_seconds.platform`). Optional YAML is merged from `metrics:` in a
  file; non-empty YAML must use a top-level `metrics` mapping, with stricter
  validation in `yaml_config.py` for shape and `MetricsYamlSettingsSource` merge.
  Default file path: `/etc/canfar/metrics/config.yaml` unless `METRICS_CONFIG_FILE`
  is set. `METRICS_*` environment is registered **before** the YAML
  `MetricsYaml` source so **env vars override the file** for the same keys.
  Legacy flat `METRICS_KUEUE_*` / `KUEUE_METRICS_*` style merges were removed;
  `cluster_queues` in nested env is JSON-array-only (not comma-separated).
  Control env: `METRICS_CONFIG_FILE`, `METRICS_REQUIRE_CONFIG_FILE`. Example
  file: `docs/examples/metrics.config.yaml`.
- **`MetricsRuntime`:** `src/metrics/core/runtime.py` — composition root:
  `from_settings` uses `provider_registry.build_platform_provider_bundle` for
  the active `sources.platform`, builds `build_cache_backend`, owns
  `platform_metrics_cache_key` binding, constructs `PlatformMetricsService`,
  and fail-fast `startup()`. Lifespan calls `shutdown()` so the active platform
  provider can close its HTTP clients. Inactive sources do not get clients
  (M4: only `kueue` is registered for `sources.platform`). `metrics.core`
  uses lazy `__getattr__` re-exports so `create_app` and the router are not
  pulled in when importing `Settings` alone. HTTP routes depend on
  `MetricsRuntime` (`api/v1/routes.py`).
- **Providers:** `src/metrics/providers/kueue.py` consolidates URL building,
  startup checks, and platform `capacity` / `allocated` maps. `base.py` defines
  `MetricScope`, `ProviderMetrics`, and the `Provider` protocol. `prometheus.py`
  and `kube.py` are inert for metric scopes in M4 but keep typed config and
  lifecycle hooks for future work.
- **HTTP:** `kube_http.py` no longer creates its own client for parallel GETs;
  call sites pass a runtime-owned `httpx.AsyncClient` (h2 is **off** by default
  so a plain `httpx` install is enough; enable in YAML when `h2` is present).
- **API:** Only `GET /api/v1/metrics/platform` and `GET /healthz` remain;
  user/session routes and hidden `/metrics` aliases are removed.
- **Dependencies:** `pyyaml` added; `niquests` removed; `requests` added so OTLP
  and other stacks keep a supported `urllib3` after the niquests transitive
  tree was removed.

## Verification (plan gates)

Run the `uv` and `pytest` (and `python -m harness check`) commands **from the
`metrics/` directory** in the science-platform monorepo—the Metrics package
root with `src/` and `tests/`. The repository-wide pre-commit run is
**different:** execute `pre-commit run --all-files` **from the science-platform
repository root** (the parent of `metrics/`).

- `uv run --group harness pytest -q tests/harness/test_contracts.py` — pass
- `uv run --group harness python -m harness check` — pass
- `uv run pytest --cov=src --cov-fail-under=80` — pass (threshold 80%)
- `pre-commit run --all-files` (from the science-platform repository root) — pass
  (first run may reformat Python via Ruff; re-run once if hooks modified files)

## Review consensus (synthesized)

| Area | Outcome |
| ---- | ------- |
| **Architecture / depth** | Single Kueue seam, `MetricsRuntime` as composition root, and explicit provider protocol reduce “horizontal” provider fragments. Inactive Prometheus does not open HTTP—matches M4. |
| **Performance** | One `AsyncClient` per active upstream, configurable pool limits on `HttpClientConfig`, and parallel Kueue GETs unchanged. HTTP/2 is opt-in to avoid an implicit `h2` dependency. |
| **Security** | Tokens and CA paths from config or standard service-account paths; no secrets in committed YAML. TLS verify defaults remain safe for in-cluster. |
| **FastAPI** | `Depends` + app state; lifespan owns startup/shutdown; cache metadata stays on headers for platform responses. |
| **Pydantic** | Strict typed config models; `MetricScope` / `ProviderMetrics` contract; env list fields use JSON in env (documented) where pydantic-settings expects JSON for `list` types. |
| **Maintainability** | Contributor extension steps align with the plan: add config → registry → provider → scopes → tests. |

**Arbitration:** No reviewer deadlock: the plan’s M4 scope (Kueue-only platform,
no user/session) was treated as the contract; any alternative (e.g. keeping
Prometheus in startup) was rejected as out of scope for M4.

**Post-review (security):** A later code pass addressed the security review MUST:
`AppError` for platform load failures no longer includes `details` with full
exception strings (which could include upstream request URLs from httpx);
failures are **logged** in `PlatformMetricsService` instead. Kueue
`httpx.RequestError` handling no longer embeds `str(exc)` in the
`ProviderExecutionError` message.

## Follow-ups (not M4)

- Deeper Kueue API fan-in limits and M5 kube-metrics.
- If HTTP/2 is required in all environments, add the `h2` dependency explicitly
  and default `http2: true` in `HttpClientConfig` once policy allows.
