# Milestone M10: local Kubernetes workflow simplification (kind)

This document is the active plan for the local and CI Kubernetes smoke
workflow. It replaces the earlier Minikube-first draft with a single `kind`
path that has a smaller tool footprint, faster setup, and stronger GitHub
Actions ergonomics.

## Decision summary

The supported smoke workflow uses:

- one-node `kind` cluster (`kind create cluster`)
- Helm install for Kueue (upstream chart)
- Helm deploy for the Metrics API chart (`helm/metrics-api`)
- `scripts/test-setup.yaml` for Kueue smoke fixtures
- integration tests from `tests/integration`

The entry point is `bash scripts/kind-smoke.sh` locally and in CI.

## Why this direction

This direction keeps behavior stable while reducing complexity.

- Smaller local dependency surface than Minikube.
- No Minikube profile/addon lifecycle management.
- Cleaner GitHub Actions setup for ephemeral one-node clusters.
- Better image-layer caching in CI through Buildx GHA cache.

## In scope

This milestone delivers the following.

- Add a kind-native smoke script and teardown flow.
- Move CI smoke from Minikube to kind.
- Keep Kueue smoke object contract names unchanged.
- Keep `helm/metrics-api` as the deployment source of truth.
- Keep local run instructions in `docs/dev-setup.md` as the single guide.
- Remove obsolete Minikube smoke workflow artifacts and references.

## Out of scope

This milestone does not include the following.

- Runtime API contract changes.
- Replacing Helm chart deployment with raw manifests.
- New Kueue topology or queue naming changes.
- Production/staging deployment model changes.

## Target implementation

### Script entry points

- `scripts/kind-smoke.sh`: full setup, deploy, and integration run.
- `scripts/kind-smoke-teardown.sh`: stop port-forward, optional cleanup.
- `scripts/teardown-dev-kube-setup.sh`: uninstall releases and fixtures.

### Deploy and test flow

The smoke flow is:

1. Ensure a one-node kind cluster exists.
2. Install Kueue from upstream Helm chart.
3. Apply `scripts/test-setup.yaml`.
4. Build service image (`canfar-metrics-local:<tag>`).
5. Load image into kind (`kind load docker-image`).
6. Deploy chart with `scripts/kind-values.yaml`.
7. Wait for Kueue admission and API rollouts.
8. Port-forward API and run integration tests.

### Caching strategy

CI uses these caches:

- Buildx `type=gha` layer cache for Docker builds.
- `astral-sh/setup-uv` dependency cache for Python dependencies.
- Optional small cache for Helm download/cache directories.

Local runs rely on Docker local layer cache by default.

## CI workflow review and change plan

`../.github/workflows/ci.metrics.yml` must move from
`metrics-minikube-smoke` to a kind smoke job.

Required updates:

1. Replace Minikube setup and image preload steps with `helm/kind-action`.
2. Add Buildx setup and `docker/build-push-action` with GHA cache.
3. Run `scripts/kind-smoke.sh` in CI mode with prebuilt image tag.
4. Replace Minikube teardown with `scripts/kind-smoke-teardown.sh --all --kind`.
5. Update job names and cache keys to reflect kind-based execution.

## Verification plan

Run these checks after implementation.

1. `bash -n scripts/kind-smoke.sh scripts/kind-smoke-teardown.sh`
2. `helm lint helm/metrics-api -f scripts/kind-values.yaml`
3. `uv run pytest -m "not integration" -q`
4. Local smoke:
   `bash scripts/kind-smoke.sh`
5. Local teardown:
   `bash scripts/kind-smoke-teardown.sh --all --kind`

For CI verification, confirm the updated `ci.metrics.yml` job passes end to end
on both pull request and push triggers.

## Cleanup requirements

After migration, remove old smoke-path artifacts that are no longer needed.

- Minikube smoke script and teardown script.
- Minikube smoke helper library.
- Minikube-specific values file used only by retired smoke path.
- Minikube/Skaffold smoke references in docs and workflow.

Only keep Minikube files if they are still required by another active,
documented workflow.

## Acceptance checklist

- [x] `scripts/kind-smoke.sh` is the supported smoke entry point.
- [x] `scripts/kind-smoke-teardown.sh` supports `--all --kind` cleanup.
- [x] CI uses kind, not Minikube, for smoke integration.
- [x] CI image builds use cache-enabled Buildx.
- [x] `docs/dev-setup.md` matches the working kind commands.
- [x] Obsolete Minikube smoke artifacts are removed.
