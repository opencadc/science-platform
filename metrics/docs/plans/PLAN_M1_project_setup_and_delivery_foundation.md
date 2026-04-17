# Milestone M1: project setup and delivery foundation

This plan defines the first milestone for the CANFAR Metrics API rollout. It
focuses on project setup, quality gates, local integration infrastructure, and
delivery scaffolding in a shared monorepo. You use this milestone to make the
service repeatable, testable, and deployable before adding new feature depth.

## Summary

This milestone establishes a baseline engineering system around the Metrics
service. The output is a containerized service with clear developer workflows,
automated checks, and Kubernetes smoke validation in GitHub Actions, while
preserving explicit separation between repo-wide, `skaha`, and Metrics CI/CD
pathways.

## In scope

This section lists work you execute in this milestone.

- Define and document local developer commands for Metrics-only workflows.
- Partition CI/CD into repo-wide, `skaha`-specific, and Metrics-specific paths.
- Add path-aware routing so `skaha` jobs do not run on Metrics-only changes.
- Add Metrics-specific CI jobs for lint, tests, and minikube smoke validation.
- Add a Metrics-specific Python pre-commit configuration.
- Establish local Kubernetes integration test flow for Metrics.
- Add an in-repo minimal Helm baseline under `metrics/helm` for `dev`.
- Configure root release-please to version Metrics independently with
  `metric-v<semver>` tags.
- Publish Metrics release images only on Metrics release tags.
- Build and publish Metrics release images for `linux/amd64` and `linux/arm64`.
- Confirm 12-factor runtime configuration through environment variables.

## Out of scope

This section lists work you defer to later milestones.

- User-level and session-level metrics behavior changes.
- Prometheus custom-label expansion beyond current contract.
- ArgoCD staging integration and GitOps promotion automation.
- Dashboard and analytics feature expansion.
- `int`, `staging`, and `prod` deployment overlays in this repository.
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

## Constraints

This milestone must satisfy architectural and operational constraints.

- Follow 12-factor app design from day 1.
- Keep process state stateless and cache via configured backends.
- Keep logs on stdout with one process per container.
- Keep local integration flow efficient on small runners.
- Do not require backwards compatibility before first deployment.
- Keep branch protection-compatible check names stable while adding Metrics jobs.

## Core decisions

This section records the baseline decisions for monorepo operation.

- **Three CI/CD pathways:** Workflows are split into repo-wide, `skaha`-
  specific, and Metrics-specific pathways.
- **Repo-wide pathway:** Governance and security workflows such as `codeql` and
  `scorecard` remain repo-wide and do not become component-specific.
- **Skaha pathway:** Existing `skaha` workflows are renamed to explicit `skaha`
  names (for example `cd.skaha.release.yml` and
  `cd.skaha.release.build.yml`) and scoped so Metrics-only changes skip them.
- **Metrics pathway:** Metrics CI runs on Metrics-scoped pull requests and
  pushes to `main`, including lint, tests, and minikube validation.
- **Multiple pre-commit configs:** The repository supports more than one
  pre-commit config file. Root pre-commit remains for shared checks, and
  `metrics/.pre-commit-config.yaml` owns Python-focused hooks for Metrics.
- **Helm ownership boundary:** Metrics keeps a minimal chart under
  `metrics/helm` with `dev` values only. Environment overlays (`int`, `staging`,
  `prod`) move to a separate deployment repository in later milestones.
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
   - Rename `skaha` release/build workflow files to explicit `skaha` names and
     preserve current behavior for `skaha`.
   - Apply path/event routing so Metrics-only changes do not run `skaha`
     workflows.
   - Keep repo-wide governance/security workflows active repository-wide.
   - Validate pull request behavior for Metrics-only, `skaha`-only, and mixed
     changes.
2. **Metrics pre-commit baseline**
   - Add `metrics/.pre-commit-config.yaml` with Python-first hooks (`ruff`,
     `pytest`, YAML/TOML hygiene as needed).
   - Keep root `.pre-commit-config.yaml` as monorepo entrypoint, with explicit
     integration strategy for running Metrics hooks.
   - Document local commands for running root and Metrics configs separately.
3. **Container and runtime baseline**
   - Harden `metrics/Dockerfile`.
   - Add `.dockerignore` and health endpoint expectations.
4. **Metrics CI runtime validation**
   - Use minikube in GitHub Actions for Metrics CI on Metrics-scoped pull
     requests and pushes to `main`.
   - Deploy Metrics + Redis via chart assets under `metrics/helm`.
   - Run black-box smoke checks against the minikube deployment.
5. **Helm baseline for local and dev**
   - Create `metrics/helm/<chart>` with a minimal deployment/service/config map
     baseline.
   - Add `values-dev.yaml` only in this repository.
   - Keep higher-environment overlays out of this milestone and repository.
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
- Confirm Metrics minikube CI runtime validation and smoke checks in GitHub
  Actions.
- Confirm Docker image build and health check behavior for Metrics.
- Confirm root CI behavior:
  - Metrics-only pull request runs repo-wide + Metrics pathways and skips
    `skaha`-specific workflows.
  - `skaha`-only pull request runs repo-wide + `skaha` pathways and skips
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
  - `helm lint` and `helm template` pass for `metrics/helm` with dev values.

## Risks

This section captures known risks and mitigation strategy.

- **Runner resource limits:** Keep integration profile small and deterministic.
- **Toolchain drift:** Pin commands in scripts and CI to avoid local variance.
- **Workflow routing drift:** Enforce path filters and changed-file checks with
  test pull requests.
- **Release coupling risk:** Validate release-please package boundaries before
  enabling automated release builds for Metrics.
- **Minikube CI runtime cost:** Keep minikube profile minimal and smoke checks
  deterministic.
- **Multi-arch build instability:** Pin Buildx/QEMU actions and verify release
  manifest contents.
- **Chart mismatch risk:** Validate rendered chart before cluster deployment.
- **Gate blind spots:** Keep local and CI verification commands aligned.

## Operational controls

This section defines controls to keep rollout quality stable.

- Keep root workflow ownership explicit: shared workflow file, component-scoped
  jobs.
- Keep Metrics pre-commit hooks versioned with Metrics source changes.
- Keep Metrics publish restricted to `metric-v*` tag events.
- Keep Metrics release manifests dual-platform (`linux/amd64`, `linux/arm64`).
- Keep only `dev` Helm values in this repository.
- Require cache and health endpoint checks in smoke validation.
- Require all mandatory gates before milestone handoff.
- Record execution evidence in milestone completion notes.

## Implementer handoff checklist

Use this checklist when you execute and close the milestone.

- [ ] Quality checks are configured and documented.
- [ ] CI/CD pathways are split and verified as repo-wide, `skaha`, and Metrics.
- [ ] `skaha` workflows are renamed and scoped to `skaha` change/events.
- [ ] Root workflows skip `skaha` jobs on Metrics-only changes.
- [ ] Metrics lint/test/minikube jobs run on Metrics pull requests and pushes to
  `main`.
- [ ] Metrics pre-commit config is defined and documented.
- [ ] Metrics minikube smoke validation runs complete successfully.
- [ ] Minimal `metrics/helm` chart with `dev` values is validated.
- [ ] Root release-please config supports Metrics component releases using
  `metric-v<semver>` tags.
- [ ] Metrics release images publish only on tags to
  `images.opencadc.org/platform/metrics`.
- [ ] Metrics release images are published for `linux/amd64` and `linux/arm64`.
- [ ] Required gates from `project-gates.yaml` pass.
- [ ] Milestone outcomes are recorded in `docs/plans`.
