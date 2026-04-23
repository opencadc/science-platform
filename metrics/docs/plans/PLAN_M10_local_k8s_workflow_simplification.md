# Milestone M10: local Kubernetes workflow simplification

> **Implemented layout:** `scripts/minikube-smoke.sh` (full smoke; `MINIKUBE_SMOKE_CI=1` in GitHub Actions), `scripts/test-setup.yaml` (all Kueue smoke objects). The rest of this document is the original milestone draft; see `PLAN_M10_outcomes.md` for closure.

This plan defines the tenth milestone for the CANFAR Metrics API roadmap. It
reduces local and CI workflow complexity around Minikube, Kueue test fixtures,
and application deployment without changing the API contract or runtime source
model.

## Repository snapshot versus milestone target

The current repository uses Minikube for local and CI smoke checks, installs
the Kueue controller through a dedicated helper script, and deploys
`helm/metrics-api` with an in-repo `metrics-test-infra` chart dependency. The
main smoke loop still rebuilds the chart dependency, builds an image on the
host, loads that image into Minikube, and then runs `helm upgrade --install`.
The current human-facing setup guidance is also spread across multiple files.

M10 closes when Minikube remains the standard local and CI cluster, Kueue test
fixtures live under `tests/fixtures/`, one setup script owns Kueue controller
installation plus fixture apply behavior, the metrics test-infra subchart is
removed, Skaffold becomes the standard build-and-deploy entry point for the
metrics API stack, and human-facing dev setup instructions consolidate into
`docs/dev-setup.md`.

## Summary

This milestone simplifies the local Kubernetes developer loop while preserving
the current Metrics API runtime contract, startup validation, Redis-backed
caching, and Minikube-based smoke coverage.

The milestone keeps Minikube and the metrics-server addon, keeps the default
upstream Kueue chart, moves Kueue smoke fixtures into `tests/fixtures/`,
introduces `scripts/setup-test-infra.sh` as the canonical
cluster setup entry point, and uses Skaffold for metrics API image build and
deployment.

## In scope

This section defines the work that M10 must deliver.

- Keep Minikube as the supported local and CI cluster for this milestone.
- Keep the upstream default Kueue chart as the controller installation path.
- Add or standardize `scripts/setup-test-infra.sh` as the single
  setup entry point for local and CI test infrastructure bring-up.
- Keep all Kueue smoke fixture manifests under `tests/fixtures/manifests/`.
- Use `tests/fixtures/manifests/kueue-setup.yaml` for the cluster-scoped Kueue
  smoke topology.
- Use `tests/fixtures/manifests/kueue-workload.yaml` for the namespaced sample
  workload and its `LocalQueue`.
- Keep the smoke object names aligned with the current contract:
  `default-flavor`, `cohort-atom`, `cq-proton`, `cq-neutron`, `cq-electron`,
  `lq-smoke`, and `integration-idle`.
- Set the sample workload request to `cpu: "100m"` and `memory: "100Mi"`.
- Standardize the default Minikube cluster/profile name and
  `METRICS_CLUSTER_NAME` value on `minikube`, not `minikube-local`.
- Remove the `metrics-test-infra` dependency from
  `helm/metrics-api/Chart.yaml` and delete the in-repo dependency chart.
- Add `skaffold.yaml` at the `metrics/` repository root and use it as the
  standard app deployment entry point for local and CI Minikube flows.
- Keep `helm/metrics-api` as the deployment backend for the metrics API stack
  in M10, but make it dependency-free.
- Consolidate human-facing setup and testing instructions into
  `docs/dev-setup.md`.

## Out of scope

This section records work that is intentionally deferred so the milestone stays
small and reviewable.

- Replacing Minikube with `kind`, `k3d`, or another cluster manager.
- Introducing a custom forked Kueue chart or maintained Helm values overlay for
  the controller when the default upstream chart is sufficient.
- Removing Helm entirely from the metrics API stack in M10.
- Rewriting the metrics API deployment into a second raw YAML source of truth.
- Changing the `/healthz` or `/api/v1/metrics/platform` contract.
- Expanding the Kueue smoke topology beyond the current queue and cohort set
  except where a contract bug requires it.

## Dependencies

This milestone builds on existing roadmap work and current repository facts.

- M2 platform metrics delivery, including the Kueue platform contract and
  `v1beta2` Kueue resources.
- M3 architecture realignment, including startup validation and current
  environment-driven configuration.
- The current Minikube smoke workflow in
  `scripts/run-minikube-integration.sh` and
  `.github/workflows/ci.metrics.yml`.
- The existing `helm/metrics-api` chart, which still owns the metrics API,
  Redis, RBAC, service account, and service objects.

## Constraints

This section captures non-negotiable design boundaries for M10.

- Use Minikube, not another local cluster manager, for M10.
- Use the existing Minikube context/profile unless the user explicitly asks for
  a dedicated profile.
- Treat `minikube` as the default cluster/profile name in scripts, values, CI,
  and docs unless a caller explicitly overrides it.
- Keep the metrics-server addon available in local and CI flows because
  kube-metrics work depends on the Kubernetes resource metrics API.
- Keep Kueue resources on `kueue.x-k8s.io/v1beta2`.
- Keep startup behavior fail-fast when Kueue, Prometheus, or Kubernetes API
  dependencies are missing or misconfigured.
- Avoid a split-brain deployment model. M10 must not introduce a second
  hand-maintained application manifest set for the metrics API stack.
- Keep fixture manifests and helper scripts co-located under `tests/fixtures/`.
- Keep human-facing setup instructions in `docs/dev-setup.md` rather than
  spreading step-by-step procedures across multiple docs.
- Keep the queue and cohort names that `scripts/minikube-values.yaml` and the
  runtime environment expect unless the milestone updates all related settings,
  docs, and tests in the same change.

## Environment model

This section defines how M10 applies across environments.

- `dev`: use the existing Minikube cluster, enable metrics-server, install
  Kueue through `scripts/setup-test-infra.sh`, and run the
  metrics API stack through Skaffold.
- `integration`: use the one-shot Minikube smoke path in CI or local
  verification, run the same setup script, and run Skaffold in non-watch mode
  before black-box integration tests.
- `staging` and `production`: remain unchanged in M10. This milestone does not
  require Skaffold outside local and CI Minikube flows.

## Runtime dependency and version constraints

This section records the tool and cluster requirements that the milestone must
keep explicit.

- Minikube, `kubectl`, Helm, Docker, and Skaffold must be documented as local
  prerequisites for the simplified workflow.
- CI must pin one validated Skaffold version instead of floating `latest`.
- The Kueue controller installation path must use the upstream default chart,
  not an in-repo Kueue chart variant.
- The Minikube flow must continue to preload any third-party images that are
  needed for reliable startup, including `alpine:3.20`, the metrics-server
  image used by the addon, and `redis:7-alpine` if in-cluster pull latency
  remains a rollout risk.
- If the metrics API Deployment keeps `imagePullPolicy: Never`, Skaffold must
  build into the Minikube-visible image store or otherwise guarantee that the
  node can resolve the generated tag without a registry push.

## Fixture ownership and test layout

This section defines the source of truth for Kueue smoke objects and how it
relates to tests.

- `scripts/setup-test-infra.sh` becomes the canonical setup
  entry point for the local and CI Kueue smoke environment.
- `tests/fixtures/manifests/kueue-setup.yaml` becomes the source of truth for
  the cluster-scoped Kueue smoke objects.
- `tests/fixtures/manifests/kueue-workload.yaml` becomes the source of truth
  for the namespaced `LocalQueue` and sample `Workload`.
- `scripts/wait-kueue-smoke-workload.sh` and any teardown helper must target
  the names defined in those fixture manifests.
- Integration tests remain under `tests/integration` and continue to validate
  the deployed API over `METRICS_BASE_URL`.

## Documentation model

This section defines the documentation simplification target for M10.

- `docs/dev-setup.md` becomes the single human-facing setup and testing guide
  for local and CI-style Minikube workflows.
- `docs/dev-kueue-cluster-setup.md` is retired or folded into
  `docs/dev-setup.md`.
- Other user-facing docs may link to `docs/dev-setup.md`, but they must not
  duplicate step-by-step setup or test procedures.

## Implementation phases

This section breaks the milestone into reviewable delivery phases with explicit
validation goals.

1. **Fixtures and setup script.** Add `tests/fixtures/manifests/kueue-setup.yaml`,
   `tests/fixtures/manifests/kueue-workload.yaml`, and `scripts/setup-test-infra.sh`. Keep the Kueue object names
   and sample workload contract stable. Validate with
   `kubectl apply --dry-run=client -f tests/fixtures/manifests/kueue-setup.yaml`
   and
   `kubectl apply --dry-run=client -f tests/fixtures/manifests/kueue-workload.yaml`.
2. **Switch test infrastructure setup to the new script.** Update the script to
   install the default upstream Kueue chart and apply the two fixture manifests
   in the expected order. Validate with a full Minikube setup cycle that
   results in the Kueue controller, queues, local queue, and sample workload
   being present.
3. **Remove the Helm test-infra dependency.** Remove the
   `metrics-test-infra` chart dependency from `helm/metrics-api/Chart.yaml`,
   delete `helm/metrics-api/charts/metrics-test-infra/`, and keep
   `helm/metrics-api` as the only app deployment source of truth. Validate with
   `helm lint helm/metrics-api -f scripts/minikube-values.yaml`.
4. **Introduce Skaffold as the app deployment entry point.** Add
   `skaffold.yaml` with a Minikube profile, wire it to the dependency-free
   `helm/metrics-api` chart, and keep Redis plus API rollout behavior intact.
   Validate with `skaffold render --profile=minikube`.
5. **Update local and CI workflow entry points.** Replace the image
   build/load-plus-Helm section in `scripts/run-minikube-integration.sh` and
   `.github/workflows/ci.metrics.yml` with the new sequence: Minikube setup,
   metrics-server, `scripts/setup-test-infra.sh`, Skaffold
   deploy, workload wait, rollout wait, and integration tests. Validate by
   running the updated smoke path end-to-end.
6. **Consolidate setup documentation.** Move the supported setup and test flow
   into `docs/dev-setup.md`, remove duplicated procedures from other docs, and
   keep only minimal references elsewhere if needed. Validate by checking that
   the commands in `docs/dev-setup.md` match the updated script and CI
   workflow.

## Review checkpoints

This section captures design decisions that require explicit human review
before milestone close.

- **Checkpoint A:** Approve the new fixture/script layout under
  `tests/fixtures/manifests/` and `scripts/setup-test-infra.sh`.
- **Checkpoint B:** Confirm that `scripts/setup-test-infra.sh`
  installs the default upstream Kueue chart rather than introducing a custom
  Kueue packaging path.
- **Checkpoint C:** Confirm that M10 keeps `helm/metrics-api` as the single app
  deployment source of truth under Skaffold, rather than introducing parallel
  raw manifests for Redis, RBAC, and the API deployment.
- **Checkpoint D:** Confirm that `docs/dev-setup.md` is the only human-facing
  setup guide after the consolidation.

## Validation plan

This section defines the evidence required to accept the milestone.

- Run gate `harness-contracts`.
- Run gate `repository-coverage`.
- Run gate `harness-cli`.
- Run
  `kubectl apply --dry-run=client -f tests/fixtures/manifests/kueue-setup.yaml`.
- Run
  `kubectl apply --dry-run=client -f tests/fixtures/manifests/kueue-workload.yaml`.
- Run `helm lint helm/metrics-api -f scripts/minikube-values.yaml`.
- Run `skaffold render --profile=minikube`.
- Run the updated one-shot Minikube smoke flow and confirm that:
  - the Kueue sample workload admits successfully;
  - `kubectl -n metrics rollout status deploy/metrics-api-redis` succeeds;
  - `kubectl -n metrics rollout status deploy/metrics-api-metrics-api`
    succeeds; and
  - `uv run pytest tests/integration -m integration -q` passes against the
    port-forwarded endpoint.

## Risks

This section captures the main delivery risks for M10.

- Skaffold can hide image-resolution problems if it is not configured against
  the same image store that Minikube uses.
- The fixture split across `kueue-setup.yaml` and `kueue-workload.yaml` can
  drift if object names change in only one file.
- CI can still fail due to addon image drift or slow in-cluster pulls if
  required third-party images are not preloaded consistently.
- Documentation drift can reappear if commands change in scripts but
  `docs/dev-setup.md` is not updated in the same change.

## Operational controls

This section defines the rollout controls that keep the simplified workflow
stable after implementation.

- Treat the names and quantities in
  `tests/fixtures/manifests/kueue-setup.yaml` and
  `tests/fixtures/manifests/kueue-workload.yaml` as contract-bearing test
  fixtures.
- Require the fixture manifests, `scripts/setup-test-infra.sh`,
  `scripts/minikube-values.yaml`, wait script, and docs to change together when
  queue or cohort names change.
- Require scripts, values, CI, and docs to use `minikube` as the default
  cluster/profile name after M10 lands.
- Require a pinned Skaffold version in CI and a documented minimum version for
  local development.
- Keep the Minikube smoke path green before any future milestone attempts to
  remove Helm from the metrics API stack.
- Require a recorded review outcome for Checkpoints B, C, and D before closing
  M10.

## Implementer handoff checklist

This section lists the concrete closure conditions for the milestone.

- [ ] `tests/fixtures/manifests/kueue-setup.yaml` exists and owns the
      cluster-scoped Kueue smoke topology.
- [ ] `tests/fixtures/manifests/kueue-workload.yaml` exists and owns the
      namespaced local queue plus sample workload.
- [ ] `scripts/setup-test-infra.sh` installs the default Kueue
      chart and applies the fixture manifests.
- [ ] `helm/metrics-api` no longer depends on the in-repo
      `metrics-test-infra` chart.
- [ ] Default cluster/profile references use `minikube`, including
      `METRICS_CLUSTER_NAME`.
- [ ] `skaffold.yaml` exists and deploys the metrics API stack into Minikube.
- [ ] `scripts/run-minikube-integration.sh` and
      `.github/workflows/ci.metrics.yml` use the new command flow.
- [ ] `docs/dev-setup.md` is the single human-facing setup guide.
- [ ] Required gates and updated Minikube smoke checks pass.
