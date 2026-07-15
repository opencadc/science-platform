# Task 6 Report

## Outcome

Changed the label-migration hook to a two-phase cutover that keeps Pods and Jobs dual-labeled until Services have switched their selectors to `canfar.net/id` and `canfar.net/kind`.

## Changes

- Updated `helm/templates/label-migration-job.yaml` to log and execute four migration phases:
- Phase A: add canonical labels to Pod metadata, Job metadata, and Job templates without deleting legacy keys.
- Phase B: rewrite Service metadata and `spec.selector` to canonical keys, while removing the legacy Service metadata and selector keys in the same patch.
- Phase C: rewrite Ingress metadata to canonical keys and strip legacy keys.
- Phase D: strip legacy labels from Pod metadata, Job metadata, and Job templates after Services already select the canonical labels.
- Kept the existing label map and `set -euo pipefail` behavior.
- Fixed the hook loop to preserve the real `patch_object` exit code for skip handling.
- Updated `helm/README.md.gotmpl`, `helm/README.md`, and `helm/values.yaml` prose to describe the phased migration instead of a single rewrite step.

## Verification

Ran:

```bash
helm lint /Users/brars/Workspace/csp/science-platform/helm
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

