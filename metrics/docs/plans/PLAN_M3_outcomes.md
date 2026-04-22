# M3 outcomes — app structure and platform sources realignment

This note records closure evidence for milestone M3
(`PLAN_M3_app_structure_and_platform_sources.md`), including multi-perspective
review and arbitration.

## Delivered

- **Layered package layout:** `metrics/api/v1/routes.py` (HTTP), `core/settings.py`,
  `core/factory.py`, `core/startup.py` (composition and validation),
  `schemas/metrics.py` (Pydantic contracts), `services/platform_metrics.py`
  (orchestration), and `providers/` (Kueue + Prometheus adapters). Supporting
  modules remain at the package root (`cache`, `errors`, `telemetry`, `kueue_api`,
  `kueue_spec`, `quantity`, `http_cache`) without compatibility re-exports of removed
  paths.
- **Removed legacy providers:** `static`, `node`, and `composite` (`FallbackCapacityProvider`)
  implementations and their tests are deleted. `METRICS_PROVIDER_MODE` and all static
  configuration fields are removed.
- **Nested settings:** `Settings.platform` (kueue, prometheus, kube_metrics) and
  `Settings.user.prometheus` (label keys) with `env_nested_delimiter="__"`, plus
  `Settings._merge_legacy_environment` (`mode="before"`) to fold flat `METRICS_*`,
  `KUEUE_METRICS_*`, and legacy Prometheus keys when nested values are absent.
  `PlatformKubeMetricsSettings.enabled=true` raises until M4.
- **Single composed runtime:** `create_app` always wires `KueuePlatformEngine.collect`,
  `KueueCapacityProvider`, and `PrometheusUsageProvider`. Platform cache fingerprinting
  always reflects configured queue list and cohort.
- **Startup contract:** `validate_application_startup` requires Prometheus URL and
  performs existing Kueue HTTP validation before traffic. `KueueStartupError` is still
  the raised type for all failures (rename to a neutral startup error is a documented
  follow-up).
- **Tests:** `tests/conftest.py` autouse noop for non-integration tests avoids real
  cluster calls when constructing `TestClient`; unit tests cover nested settings,
  legacy env merge, kube-metrics gate, and updated provider/app paths.
- **Ops samples:** `scripts/minikube-values.yaml`, `helm/metrics-api/values-dev.yaml`,
  `compose.yaml`, `env.example`, `README.md`, and environment docs updated for nested
  env and Prometheus requirement.

## Verification commands (local)

From `metrics/`:

```bash
uv run ruff check src tests
uv run pytest --cov=src --cov-report=term-missing --cov-fail-under=80 -m "not integration"
uv run --group harness pytest -q tests/harness/test_contracts.py
uv run --group harness python -m harness check
helm lint helm/metrics-api
helm template metrics-api helm/metrics-api -f scripts/minikube-values.yaml >/dev/null
helm template metrics-api helm/metrics-api -f helm/metrics-api/values-dev.yaml >/dev/null
```

Gates in `project-gates.yaml` remain `harness-contracts`, `repository-coverage`,
and `harness-cli`.

## Review rounds (persona-driven)

### Round 1 — Architecture reviewer

**Focus:** Module boundaries versus `PLAN_M3`, extension path for `user` metrics
config, kube-metrics placeholder.

**Findings**

- User legacy env vars could override nested user Prometheus keys (precedence bug).
- Prometheus URL validated only as non-empty string, not HTTP-reachable (asymmetric
  vs Kueue checks).
- `KueueStartupError` name overload for Prometheus misconfig.
- `validate_kueue_mode_startup` shim retained for tests (acceptable; not a provider shim).
- `providers/__all__` lists two adapters; third source is config-only until M4 — OK.

**Arbitration**

- **Accepted / fixed:** User `METRICS_USER_LABEL_KEY` / `METRICS_SESSION_LABEL_KEY`
  merge is now fill-only (same rule as platform operator aliases): applied only when
  the nested dict from constructor/env does not already define the key.
- **Documented deferral:** Optional Prometheus HTTP probe at startup is out of scope
  for M3 (add to M4 ops hardening or a small hotfix if operators request parity).
- **Backlog:** Rename `KueueStartupError` → neutral `StartupValidationError` (or split
  types) when touching startup next; update log playbooks.

### Round 2 — Reliability / operations reviewer

**Focus:** CrashLoop causes, Redis not in startup path, conftest coverage gaps,
Compose empty-env footgun, default `main` import behavior.

**Findings**

- Transient K8s errors at startup can still loop pods (existing class of issue).
- Redis misconfig surfaces at first cache use, not lifespan (unchanged pattern).
- Autouse startup noop means most tests never run real validation (by design; document).
- Compose requires explicit `.env` for platform keys or lifespan fails (expected).
- No in-repo test runs full HTTP validation against mock Prometheus (acceptable for M3).

**Arbitration**

- No code change for Redis-at-startup: cost and false positives outweigh benefit until
  requested.
- Outcomes and `environment-contracts.md` already stress Prometheus + Kueue at boot;
  Compose comments and `env.example` reinforce the footgun.

### Round 3 — Maintainability reviewer

**Focus:** `_merge_legacy_environment` complexity, dual Prometheus env paths,
`before` validator non-dict early return, dataclass nuance vs plan wording.

**Findings**

- Legacy merge is a single high-churn function (documented risk).
- Samples mixed nested vs flat Prometheus URL (operator confusion).
- Non-dict `data` early-return is unlikely but undocumented.
- `core.factory` pulls many layers (composition root, not tiny “core”).
- Internal `@dataclass` for `CachedMetrics` / `ServiceResult` is outside the plan’s
  “no dataclass config/schema” rule; grep-based reviewers may need the nuance in
  `docs/architecture.md`.

**Arbitration**

- **Accepted / fixed:** `minikube-values.yaml` now uses canonical nested
  `METRICS_PLATFORM__PROMETHEUS__URL`; provider error text cites legacy alias.
- **Docs:** Architecture file states internal dataclass exception explicitly.

## Consensus / sign-off criteria (M3)

- [x] Physical layered layout under `src/metrics/` per plan (api v1, core, schemas,
  services, providers).
- [x] Static and node provider code paths removed; composite fallback removed.
- [x] Nested Pydantic settings with environment-driven parsing and documented legacy merge.
- [x] Platform sources limited to Kueue + Prometheus + reserved kube-metrics config.
- [x] Gates `harness-contracts`, `repository-coverage`, `harness-cli` pass with evidence.
- [x] Multi-reviewer findings triaged; material fixes applied or deferred with owner notes.

## Follow-ups (not M3)

- Optional Prometheus TCP/HTTP readiness check at startup.
- Rename startup exception type for mixed Kueue/Prometheus failures.
- Declarative env-alias table or smaller validators if `_merge_legacy_environment` grows.
- M4: implement `kube_metrics` runtime behind `platform.kube_metrics`.
