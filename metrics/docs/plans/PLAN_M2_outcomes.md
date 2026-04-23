# M2 outcomes — Kueue mode platform metrics initial release

This note records closure evidence for milestone M2
(`PLAN_M2_platform_metrics_initial_release.md`), including three deliberate
**implement → review → update** cycles using distinct reviewer lenses from
`docs/harness/personas/`.

## Delivered

- **Single-mode runtime:** `METRICS_PROVIDER_MODE` is `static` or `kueue` only;
  the former `live` mode and `FallbackCapacityProvider` / node fallback wiring
  for the default app factory are removed.
- **Platform contract:** `GET /api/v1/metrics/platform` exposes open-ended
  `capacity` and `allocated` string maps (Kubernetes-style quantity strings);
  JSON metadata includes `created`, and HTTP headers carry cache semantics.
  User and session payloads keep the prior snapshot shape.
- **Kueue aggregation:** `KueuePlatformEngine` sums nominal quota from each
  configured `ClusterQueue`, adds the shared `Cohort` nominal quota **once**,
  and sets `allocated` from `status.flavorsUsage` **total + borrowed** per
  resource (documented choice over `flavorsReservation`; see research below).
- **Startup validation:** In `kueue` mode, lifespan awaits `validate_kueue_mode_startup`
  (API reachability, cohort object presence, each queue exists, and
  `spec.cohort` matches `METRICS_KUEUE_COHORT`) before serving traffic; failures
  raise `KueueStartupError` and prevent app startup.
- **Configuration:** `METRICS_KUEUE_CLUSTER_QUEUES` (comma-separated),
  `METRICS_KUEUE_COHORT`, default `METRICS_CACHE_TTL_SECONDS=300`, optional
  operator aliases `KUEUE_METRICS_URL`, `KUEUE_METRICS_CLUSTER_QUEUES`,
  `KUEUE_METRICS_COHORT`, `METRICS_KUBE_SA_TOKEN_PATH` / `METRICS_KUBE_SA_CA_PATH`
  for in-cluster auth, and `METRICS_ENVIRONMENT` aligned with the canonical
  service modes `dev` / `staging` / `integration` / `production`.
- **Fixtures and automation (M2 closure):** Kueue smoke objects originally lived in the
  `metrics-test-infra` Helm subchart. **Superseded in M10:** fixtures are now
  (Superseded) Smoke YAML now **`scripts/test-setup.yaml`**, run via **`scripts/minikube-smoke.sh`**
  and Skaffold; see `docs/dev-setup.md`.
- **Helm:** Optional `serviceAccount.create` and `rbac.create` install a
  namespace `ServiceAccount` plus `ClusterRole` / `ClusterRoleBinding` for
  read-only `kueue.x-k8s.io` `clusterqueues` and `cohorts`;
  `scripts/minikube-values.yaml` enables Kueue mode with in-cluster API URL and
  five-minute cache TTL. The file name is historical; future plan docs treat it
  as a dev-cluster values file rather than a provisioning contract.
- **Tests:** `FastAPI TestClient` coverage for Kueue lifespan validation,
  `KueuePlatformEngine` aggregation, updated platform route and service tests,
  and refreshed Kueue capacity provider unit tests.

## Allocated semantic (research outcome)

M2 compared `flavorsReservation` (reserved quota for assigned workloads) with
`flavorsUsage` (admitted usage). **Contract default:** `allocated` uses
`flavorsUsage` **total + borrowed** per resource, summed across the configured
`ClusterQueue` set only (cohort object does not contribute usage rows). This
matches “resources consumed by admitted workloads” without mixing reservation
semantics into the same map. Operators needing reservation-shaped totals should
treat that as a follow-up metric or a future contract flag—not part of this
milestone’s payload.

## Verification commands (local)

From `metrics/`:

```bash
uv run ruff check src tests
uv run pytest --cov=src --cov-report=term-missing --cov-fail-under=80 -m "not integration"
uv run --group harness pytest -q tests/harness/test_contracts.py
uv run --group harness python -m harness check
helm lint helm/metrics-api
helm template metrics-api helm/metrics-api -f scripts/minikube-values.yaml >/dev/null
bash scripts/check-prerequisites.sh docker helm kubectl minikube
bash scripts/minikube-smoke.sh
```

Gates in `project-gates.yaml` remain `harness-contracts`, `repository-coverage`,
and `harness-cli`.

## Review rounds (persona-driven)

### Round 1 — Architecture reviewer (`architecture-reviewer.md`)

**Focus:** Mode boundary, fail-fast startup vs request path, fixture ownership,
single responsibility between `kueue_startup`, `kueue_api`, `kueue_platform`,
and `PlatformMetricsService`.

**Findings addressed**

- Kept Kueue HTTP URL construction in `kueue_api.py` so startup and collectors
  share one definition of `v1beta2` paths.
- Left user/session metrics on the existing capacity + usage provider path so
  platform contract work does not entangle Prometheus changes.
- Documented the `flavorsUsage` choice for `allocated` here to satisfy the plan’s
  “explicit research outcome” requirement.

### Round 2 — Design / implementation reviewer (implementation + reliability lenses)

**Focus:** Correct quantity parsing, cohort double-count prevention, error
mapping, and test realism.

**Findings addressed**

- Cohort nominal quota is added once after per-queue sums; queue iteration does
  not add cohort quota per member.
- `kube_http.resolve_kube_token` / TLS CA paths support in-cluster pods without
  duplicating secret wiring in Helm for local smoke.
- `FastAPI TestClient` tests assert lifespan validation is invoked and that
  `KueueStartupError` blocks startup when validation fails.
- Kueue capacity provider tests now hit per-queue GETs aligned with production
  behavior.

### Round 3 — Tech spec + scalability reviewers (`reliability-reviewer.md`, `scale-reviewer.md`)

**Focus:** Bounded outbound calls, CRD version scope, and operability at queue-list cardinality.

**Findings addressed**

- Each platform refresh performs **O(number of configured queues + 1 cohort)**
  GETs; no cluster-wide list at request time. Timeout comes from
  `METRICS_KUBE_REQUEST_TIMEOUT_SECONDS` per call (documented trade-off: many
  queues multiply latency).
- Explicit **Kueue 0.17.x** install script default; contract stays on
  `kueue.x-k8s.io/v1beta2`.
- ClusterRole name includes release namespace to reduce collision risk for
  `ClusterRoleBinding` in shared dev clusters.

## M2 closure backlog (post-consensus review)

Before declaring the milestone closed for operators, execute the bite-sized
checklist in
`docs/plans/PLAN_M2_post_review_feedback.md` (legacy
`live` → `kueue` normalization, changelog entries, env alias precedence
documentation, warning logs for unparseable generic quantities, Helm RBAC verb
narrowing, README pointer). Performance-only feedback (HTTP client pooling,
Prometheus query batching) stays out of that closure plan unless M2 scope is
explicitly widened.

## Follow-ups (not M2)

- Optional informer/watch, mixed modes, kube-metrics mode, and richer
  utilization analytics remain out of scope per the plan.
- Prometheus error-path hardening (`_query_scalar` / `_build_usage_reading`) and
  import-surface cleanup (`metrics.providers` exports, unused dependencies) are
  **M4 or later** unless pulled into scope by a new plan.
- If webhook validation requires additional Cohort fields for your Kueue
  configuration, edit `scripts/test-setup.yaml` (and values/docs) and re-run
  `scripts/minikube-smoke.sh` or the CI workflow.
