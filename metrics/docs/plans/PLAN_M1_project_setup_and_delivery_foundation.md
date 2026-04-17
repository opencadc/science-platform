# Milestone M1: project setup and delivery foundation

This plan defines the first milestone for the CANFAR Metrics API rollout. It
focuses on project setup, quality gates, local integration infrastructure, and
delivery scaffolding in a shared monorepo. You use this milestone to make the
service repeatable, testable, and deployable before adding new feature depth.

## Summary

This milestone establishes a baseline engineering system around the Metrics
service. The output is a containerized service with clear developer workflows,
automated checks, and a local Kubernetes loop that includes Redis and endpoint
smoke tests, while preserving clean separation from existing `skaha` CI/CD.

## In scope

This section lists work you execute in this milestone.

- Define and document local developer commands for Metrics-only workflows.
- Add path-aware CI routing so `skaha` jobs do not run on Metrics-only changes.
- Add Metrics-specific CI jobs for lint, tests, and image build.
- Add a Metrics-specific Python pre-commit configuration.
- Establish local Kubernetes integration test flow for Metrics.
- Add an in-repo minimal Helm baseline under `metrics/helm` for `dev`.
- Configure root release-please to version Metrics independently.
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

- **CI routing:** Root `skaha` jobs remain in existing workflows, but they are
  conditionally skipped for Metrics-only changes. Metrics jobs run from
  Metrics-scoped triggers and/or Metrics-scoped jobs in shared workflows.
- **Multiple pre-commit configs:** The repository supports more than one
  pre-commit config file. Root pre-commit remains for shared checks, and
  `metrics/.pre-commit-config.yaml` owns Python-focused hooks for Metrics.
- **Helm ownership boundary:** Metrics keeps a minimal chart under
  `metrics/helm` with `dev` values only. Environment overlays (`int`, `staging`,
  `prod`) move to a separate deployment repository in later milestones.
- **Release ownership:** Root `release-please-config.json` is upgraded to a
  multi-component layout so Metrics changes do not cut a `skaha` release and can
  produce an independent Metrics release stream.
- **Workflow composition:** Shared workflows such as `ci.linting.yml` continue
  to exist, but include Metrics-specific jobs with path/job filters instead of
  forcing Java/Gradle jobs for Metrics-only pull requests.

## Implementation phases

This section breaks work into execution phases.

1. **Workflow routing and CI partitioning**
   - Add path-aware filters in root workflows so `skaha` CI/CD does not run for
     Metrics-only changes.
   - Keep shared workflow files, and add Metrics-specific jobs where reuse is
     useful (for example linting).
   - Validate pull request check behavior for three scenarios: Metrics-only,
     `skaha`-only, and mixed changes.
2. **Metrics pre-commit baseline**
   - Add `metrics/.pre-commit-config.yaml` with Python-first hooks (`ruff`,
     `pytest`, YAML/TOML hygiene as needed).
   - Keep root `.pre-commit-config.yaml` as monorepo entrypoint, with explicit
     integration strategy for running Metrics hooks.
   - Document local commands for running root and Metrics configs separately.
3. **Container and runtime baseline**
   - Harden `metrics/Dockerfile`.
   - Add `.dockerignore` and health endpoint expectations.
4. **Local Kubernetes integration harness**
   - Add deterministic local cluster script.
   - Deploy Metrics + Redis via chart assets under `metrics/helm`.
   - Run black-box integration tests from CI-compatible scripts.
5. **Helm baseline for local and dev**
   - Create `metrics/helm/<chart>` with a minimal deployment/service/config map
     baseline.
   - Add `values-dev.yaml` only in this repository.
   - Keep higher-environment overlays out of this milestone and repository.
6. **Release automation alignment**
   - Extend root `release-please-config.json` with a Metrics package path.
   - Update `.release-please-manifest.json` to track Metrics version state.
   - Ensure release workflows can distinguish `skaha` and Metrics release events
     for downstream build/publish behavior.

## Validation plan

This section defines gate checks and required evidence for milestone closure.

- Run gate `harness-contracts` from `project-gates.yaml`.
- Run gate `repository-coverage` from `project-gates.yaml`.
- Run gate `harness-cli` from `project-gates.yaml`.
- Run local kind integration script and confirm endpoint smoke tests.
- Confirm Docker image build and health check behavior.
- Confirm root CI behavior:
  - Metrics-only pull request skips `skaha` CI/CD jobs.
  - Metrics-specific lint/test/build checks run and report status.
  - Mixed pull request runs both `skaha` and Metrics job sets.
- Confirm pre-commit behavior:
  - `pre-commit run --config metrics/.pre-commit-config.yaml --all-files`
    passes.
  - Root pre-commit run remains green for non-Metrics paths.
- Confirm release-please behavior:
  - Metrics-only conventional-commit change creates or updates a Metrics release
    PR.
  - Metrics-only changes do not bump the `skaha` root package.
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
- **Chart mismatch risk:** Validate rendered chart before cluster deployment.
- **Gate blind spots:** Keep local and CI verification commands aligned.

## Operational controls

This section defines controls to keep rollout quality stable.

- Keep root workflow ownership explicit: shared workflow file, component-scoped
  jobs.
- Keep Metrics pre-commit hooks versioned with Metrics source changes.
- Keep only `dev` Helm values in this repository.
- Require cache and health endpoint checks in smoke validation.
- Require all mandatory gates before milestone handoff.
- Record execution evidence in milestone completion notes.

## Implementer handoff checklist

Use this checklist when you execute and close the milestone.

- [ ] Quality checks are configured and documented.
- [ ] Root workflows skip `skaha` jobs on Metrics-only changes.
- [ ] Metrics lint/test/build jobs run on Metrics-scoped changes.
- [ ] Metrics pre-commit config is defined and documented.
- [ ] Local kind integration runs complete successfully.
- [ ] Minimal `metrics/helm` chart with `dev` values is validated.
- [ ] Root release-please config supports a Metrics component release.
- [ ] Required gates from `project-gates.yaml` pass.
- [ ] Milestone outcomes are recorded in `docs/plans`.
