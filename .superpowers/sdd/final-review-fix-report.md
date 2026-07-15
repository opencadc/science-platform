# Final Review Fix Report

## Finding Addressed

`helm/templates/label-migration-job.yaml` was attempting to patch `Job.spec.template.metadata.labels`.
Kubernetes treats Job pod templates as immutable, so patching live session Jobs would fail the hook
and block the Helm upgrade.

## Fix

- Updated the label-migration hook to patch `jobs` only at `metadata.labels`.
- Kept the separate `pods` migration unchanged so live Pod labels still receive the phased add/strip
  cutover.
- Added a warning log that Job pod templates are immutable, are not rewritten, and may recreate Pods
  with legacy template labels during the upgrade window.
- Updated `helm/README.md.gotmpl`, regenerated `helm/README.md`, and revised `helm/values.yaml`
  comments so the chart docs no longer claim Job template labels are rewritten.

## Verification

Ran:

```bash
cd /Users/brars/Workspace/csp/science-platform/helm && helm-docs --chart-search-root . && helm lint .
```

Result:

- `level=WARN msg="missing required value" message=".Values.deployment.skaha.sessions.userStorage.nodeURIPrefix nodeURIPrefix is required."`
- `level=INFO msg="funcMap fail" message="deployment.skaha.sessions.userStorage.nodeURIPrefix must be a vos:// URI (e.g. vos://example.org~cavern)"`
- `1 chart(s) linted, 0 chart(s) failed`

## Files Changed

- `helm/templates/label-migration-job.yaml`
- `helm/README.md.gotmpl`
- `helm/README.md`
- `helm/values.yaml`
