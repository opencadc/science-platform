# Milestone M1: project setup and delivery foundation

This plan defines the first milestone for the CANFAR Metrics API rollout. It
focuses on project setup, quality gates, local developer workflow, and
environment contracts in a shared monorepo. You use this milestone to make the
service repeatable, testable, and deployable before adding mode-specific
feature depth.

> Note: This document captures historical M1 scope. Docker Compose references in
> this file are superseded by the Kubernetes-first environment contract recorded
> in `docs/environment-contracts.md` and M3+ roadmap plans.

## Summary

This milestone establishes the delivery baseline for the Metrics service. The
output is a containerized FastAPI service with clear developer workflows,
explicit separation between local app wiring and external Kubernetes data
sources, and automated checks that preserve explicit separation between
repo-wide, `skaha`, and Metrics CI/CD pathways.

The local `dev` workflow for this roadmap is `docker compose` for the app and
its local dependencies, such as Redis. An already running Kubernetes cluster
that is reachable through `kubectl` is an external prerequisite that provides
live data sources for later milestones. The `integration`, `staging`, and
`production` environments run the service in Kubernetes, but their deployment
overlays remain outside this repository.

## How to read this milestone after the roadmap review

This milestone mixes **delivery targets** with the **current repository
snapshot**. Anything described as a local workflow must either exist as a
checked-in artifact or be explicitly labeled as a planned deliverable with a
clear acceptance check.

The **local app + Redis** workflow is implemented as `compose.yaml` under
`metrics/` (see `README.md`). The **cluster-backed** loop remains Helm plus the
existing Kubernetes harness, currently implemented in
`.github/workflows/ci.metrics.yml` and `scripts/run-minikube-integration.sh`.
Treat Kubernetes data sources as external to Compose. Existing artifact names
may still mention Minikube, but the roadmap contract is "already running dev
cluster reachable through `kubectl`," not "create a cluster in-repo."

## In scope

This section lists work you execute in this milestone.

- Define and document local developer commands for Metrics-only workflows.
- Partition CI/CD into repo-wide, `skaha`-specific, and Metrics-specific paths.
- Add path-aware routing so `skaha` jobs do not run on Metrics-only changes.
- Add Metrics-specific CI jobs for lint, tests, prerequisite checks, and
  cluster-backed smoke validation.
- Add a Metrics-specific Python pre-commit configuration.
- Establish a local `dev` workflow where the app and Redis run via
  `docker compose`.
- Establish a local Kubernetes integration path where an already running
  Kubernetes cluster is treated as an external prerequisite for data sources.
- Keep an in-repo minimal Helm baseline under `metrics/helm` for cluster
  deployment contracts, with `dev` values only.
- Document environment contracts for `dev`, `integration`, `staging`, and
  `production`.
- Configure root release-please to version Metrics independently with
  `metric-v<semver>` tags.
- Publish Metrics release images only on Metrics release tags.
- Build and publish Metrics release images for `linux/amd64` and `linux/arm64`.
- Confirm 12-factor runtime configuration through environment variables.

## Out of scope

This section lists work you defer to later milestones.

- User-level and session-level metrics behavior changes.
- Mode-specific startup validation for Kueue or kube-metrics.
- Kueue or kube-metrics contract design.
- Prometheus custom-label expansion beyond the current contract.
- ArgoCD staging integration and GitOps promotion automation.
- Dashboard and analytics feature expansion.
- `integration`, `staging`, and `production` deployment overlays in this
  repository.
- Cross-repository promotion pipeline for environment-specific deployment repos.

## Dependencies

This milestone depends on existing repository assets and tooling contracts.

- `metrics/src/metrics` service structure.
- Root repository workflows under `.github/workflows`.
- Root repository release automation (`release-please-config.json` and
  `.release-please-manifest.json`).
- Root and Metrics pre-commit integration points.
- Existing container registry and credentials:
  `images.opencadc.org`, `SKAHA_REGISTRY_USERNAME`, and
  `SKAHA_REGISTRY_TOKEN`.
- `project-gates.yaml` gate definitions.
- Local prerequisites for the developer and CI loops: `docker`, `helm`,
  `kubectl`, and access to an already running cluster.

## Constraints

This milestone must satisfy architectural and operational constraints.

- Follow 12-factor app design from day 1.
- Keep process state stateless and cache via configured backends.
- Keep logs on stdout with one process per container.
- Keep the local `dev` loop efficient on small runners and laptops.
- Treat Kubernetes data sources as external dependencies rather than embedding
  them into the local app container stack.
- Fail early when required tools such as `docker`, `helm`, or `kubectl` are
  unavailable, or when the intended cluster context is not reachable.
- Keep only `dev` runtime artifacts in this repository.
- Do not require backwards compatibility before first deployment.
- Keep branch protection-compatible check names stable while adding Metrics
  jobs.

## Core decisions

This section records the baseline decisions for monorepo operation.

- **Three CI/CD pathways:** Workflows are split into repo-wide, `skaha`-
  specific, and Metrics-specific pathways.
- **Repo-wide pathway:** Governance and security workflows such as `codeql` and
  `scorecard` remain repo-wide and do not become component-specific.
- **Skaha pathway:** Existing `skaha` workflows are renamed to explicit `skaha`
  names and scoped so Metrics-only changes skip them.
- **Metrics pathway:** Metrics CI runs on Metrics-scoped pull requests and
  pushes to `main`, including lint, tests, prerequisite checks, and
  cluster-backed validation.
- **Multiple pre-commit configs:** The repository supports more than one
  pre-commit config file. Root pre-commit remains for shared checks, and
  `metrics/.pre-commit-config.yaml` owns Python-focused hooks for Metrics.
- **Local workflow split:** Local `dev` runs the FastAPI app and Redis through
  `docker compose`, while Kubernetes-backed data sources are provided by an
  already running cluster outside the compose stack.
- **Environment contract:** `dev`, `staging`, `integration`, and `production`
  are the canonical environment names for roadmap and deployment planning.
- **Environment token alignment:** Runtime settings, roadmap docs, and charts
  must converge on the canonical names `dev`, `staging`, `integration`, and
  `production`.
- **Deployment ownership boundary:** This repository owns local `dev` artifacts
  and cluster deployment contracts. Higher-environment overlays live in a
  separate deployment repository.
- **Helm ownership boundary:** Metrics keeps a minimal chart under
  `metrics/helm` with `dev` values only. The chart documents the cluster
  deployment contract that higher environments consume elsewhere.
- **Fail-fast tool contract:** Local scripts and CI checks must stop
  immediately, with actionable feedback, when `docker`, `helm`, or `kubectl`
  are missing, or when the active cluster context is wrong.
- **Metrics release contract:** Metrics release tags and changelog entries use
  `metric-v0.1.1` style. `skaha` keeps its current tag style in this milestone.
- **Metrics image publish contract:** Metrics images publish to
  `images.opencadc.org/platform/metrics` using
  `SKAHA_REGISTRY_USERNAME` and `SKAHA_REGISTRY_TOKEN`.
- **Release-only image publish:** Metrics container images are built and pushed
  only on `metric-v*` tag events. No Metrics edge image push is used.
- **Multi-arch release images:** Metrics release publishes a single manifest
  containing `linux/amd64` and `linux/arm64`.

## Implementation phases

This section breaks work into execution phases.

1. **Workflow routing and CI partitioning**
   - Classify workflows into repo-wide, `skaha`-specific, and Metrics-specific
     pathways.
   - Rename `skaha` release and build workflow files to explicit `skaha` names
     and preserve current behavior for `skaha`.
   - Apply path and event routing so Metrics-only changes do not run `skaha`
     workflows.
   - Keep repo-wide governance and security workflows active repository-wide.
   - Validate pull request behavior for Metrics-only, `skaha`-only, and mixed
     changes.
2. **Metrics pre-commit and prerequisite baseline**
   - Add `metrics/.pre-commit-config.yaml` with Python-first hooks (`ruff`,
     `pytest`, YAML and TOML hygiene as needed).
   - Keep root `.pre-commit-config.yaml` as the monorepo entrypoint, with an
     explicit integration strategy for running Metrics hooks.
   - Add prerequisite checks for `docker`, `helm`, and `kubectl` to local
     scripts and CI entrypoints.
   - Document local commands for running root and Metrics configs separately.
3. **Container and local `dev` runtime baseline**
   - Harden `metrics/Dockerfile`.
   - Add `.dockerignore` and health endpoint expectations.
   - Add a `docker compose` specification under `metrics/` for the FastAPI app
     and Redis, including documented ports, env file conventions, and a single
     command entrypoint for local onboarding. **Delivered:** `compose.yaml`,
     `env.example`, README instructions.
   - Keep the Helm and existing-cluster loop as the supported **cluster
     integration** path and document both Compose (app stack) and Kubernetes
     data sources without conflating them.
   - Document how later mode-specific milestones connect the compose workflow
     to an externally managed Kubernetes cluster.
4. **Metrics CI runtime validation**
   - Use the cluster-backed Kubernetes harness in GitHub Actions for Metrics CI
     on Metrics-scoped pull requests and pushes to `main`.
   - Treat the Kubernetes cluster as an external prerequisite for cluster-backed
     data sources.
   - Deploy Metrics through chart assets under `metrics/helm`.
   - Run black-box smoke checks against the cluster-backed deployment.
5. **Helm baseline and environment contracts**
   - Keep `metrics/helm/<chart>` as the minimal cluster deployment contract.
   - Keep `values-dev.yaml` only in this repository.
   - Document the expected handoff contract for `integration`, `staging`, and
     `production` overlays managed elsewhere.
   - Add a review checkpoint after this phase to confirm the environment
     ownership model before mode-specific implementation starts.
6. **Release automation alignment**
   - Extend root `release-please-config.json` with a Metrics package path and
     `metric-v*` release tag semantics.
   - Update `.release-please-manifest.json` to track Metrics version state.
   - Ensure Metrics-only commits do not trigger `skaha` version bumps.
7. **Metrics release image publishing**
   - Build and publish Metrics images only on `metric-v*` tag events.
   - Configure Buildx and QEMU for `linux/amd64` and `linux/arm64` release
     manifests.
   - Push only release tags to `images.opencadc.org/platform/metrics`.

## Validation plan

This section defines gate checks and required evidence for milestone closure.

- Run gate `harness-contracts` from `project-gates.yaml`.
- Run gate `repository-coverage` from `project-gates.yaml`.
- Run gate `harness-cli` from `project-gates.yaml`.
- Confirm local scripts and CI fail immediately, with actionable feedback, when
  `docker`, `helm`, or `kubectl` are unavailable, or when the intended cluster
  context is unreachable.
- Confirm the local `dev` workflow starts the FastAPI app and Redis through
  `docker compose` once the compose specification is checked in.
- Confirm the Helm and cluster-backed workflow remains green while the compose
  path is still in flight.
- Confirm Metrics cluster-backed CI runtime validation and smoke checks in
  GitHub Actions.
- Confirm Docker image build and health check behavior for Metrics.
- Confirm root CI behavior:
  - Metrics-only pull request runs repo-wide and Metrics pathways and skips
    `skaha`-specific workflows.
  - `skaha`-only pull request runs repo-wide and `skaha` pathways and skips
    Metrics-specific workflows.
  - Mixed pull request runs all applicable pathway checks.
- Confirm pre-commit behavior:
  - `pre-commit run --config metrics/.pre-commit-config.yaml --all-files`
    passes.
  - Root pre-commit run remains green for non-Metrics paths.
- Confirm release-please behavior:
  - Metrics-only conventional-commit change creates or updates a Metrics
    release PR with `metric-v<semver>` tag intent.
  - Metrics-only changes do not bump the `skaha` root package.
- Confirm Metrics image publish behavior:
  - `metric-v*` tag push publishes `images.opencadc.org/platform/metrics`.
  - Published release manifests contain both `linux/amd64` and `linux/arm64`.
  - Non-tag Metrics CI does not push container images.
- Confirm Helm behavior:
  - `helm lint` and `helm template` pass for `metrics/helm` with `dev` values.
- Confirm the milestone documentation describes `dev`, `integration`,
  `staging`, and `production` consistently.

## Risks

This section captures known risks and mitigation strategy.

- **Runner resource limits:** Keep the integration profile small and
  deterministic.
- **Toolchain drift:** Pin commands in scripts and CI to avoid local variance.
- **Workflow routing drift:** Enforce path filters and changed-file checks with
  test pull requests.
- **Release coupling risk:** Validate release-please package boundaries before
  enabling automated release builds for Metrics.
- **Local workflow confusion:** Developers can mix up `docker compose` and
  Kubernetes responsibilities. Mitigate this by documenting the local app stack
  and external cluster prerequisites separately.
- **Higher-environment ambiguity:** `integration`, `staging`, and `production`
  overlays live outside this repository. Mitigate this by documenting the
  deployment contract and review checkpoint before mode work starts.
- **Cluster CI runtime cost:** Keep the cluster-backed profile minimal and smoke
  checks deterministic.
- **Multi-arch build instability:** Pin Buildx and QEMU actions and verify
  release manifest contents.
- **Chart mismatch risk:** Validate the rendered chart before cluster
  deployment.
- **Gate blind spots:** Keep local and CI verification commands aligned.

## Operational controls

This section defines controls to keep rollout quality stable.

- Keep root workflow ownership explicit through shared workflow files and
  component-scoped jobs.
- Keep Metrics pre-commit hooks versioned with Metrics source changes.
- Keep Metrics publish restricted to `metric-v*` tag events.
- Keep Metrics release manifests dual-platform (`linux/amd64`, `linux/arm64`).
- Keep only `dev` runtime artifacts in this repository.
- Require prerequisite checks before local or CI cluster validation starts.
- Require cache and health endpoint checks in smoke validation.
- Require the environment ownership review checkpoint before mode-specific
  implementation begins.
- Require all mandatory gates before milestone handoff.
- Record execution evidence in milestone completion notes.

## Implementer handoff checklist

Use this checklist when you execute and close the milestone.

- [ ] Quality checks are configured and documented.
- [ ] CI/CD pathways are split and verified as repo-wide, `skaha`, and Metrics.
- [ ] `skaha` workflows are renamed and scoped to `skaha` change and events.
- [ ] Root workflows skip `skaha` jobs on Metrics-only changes.
- [ ] Metrics lint, test, prerequisite-check, and cluster-backed jobs run on Metrics
  pull requests and pushes to `main`.
- [ ] Metrics pre-commit config is defined and documented.
- [ ] Local scripts fail early when `docker`, `helm`, or `kubectl` are
  missing, or when the intended cluster context is unreachable.
- [ ] The local `dev` workflow runs the app and Redis through `docker compose`.
- [ ] Metrics cluster-backed smoke validation runs complete successfully.
- [ ] Minimal `metrics/helm` chart with `dev` values is validated.
- [ ] The `integration`, `staging`, and `production` deployment contract is
  documented for the external deployment repository.
- [ ] The environment ownership review checkpoint is completed and recorded.
- [ ] Root release-please config supports Metrics component releases using
  `metric-v<semver>` tags.
- [ ] Metrics release images publish only on tags to
  `images.opencadc.org/platform/metrics`.
- [ ] Metrics release images are published for `linux/amd64` and `linux/arm64`.
- [ ] Required gates from `project-gates.yaml` pass.
- [ ] Milestone outcomes are recorded in `docs/plans`.
