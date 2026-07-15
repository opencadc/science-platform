# Session label plan + deploy-time migration

**Date:** 2026-07-15  
**Branch / PR:** `feat/label-realignment` ([#1112](https://github.com/opencadc/science-platform/pull/1112))  
**Status:** Approved for implementation (design dialogue 2026-07-15)

## Problem

1. Launch path builds labels from request params, mutates a `V1Job`, then re-reads those labels to label Service/Ingress — false centralization.
2. Legacy `canfar-net-*` → `canfar.net/*` is a hard cutover; in-app dual-read is deferred in favor of a one-shot Helm migration before new Skaha pods start.
3. Org `CONTRIBUTING.md` still says Java 11; this repo’s Skaha module already builds as Java 21.

## Goals

- One immutable label plan built once per launch; projections feed Job, Service, Ingress.
- Apply ponytail shrinks from the label-realignment review.
- Document Java 21 for this repository’s Java modules.
- Ship a gated Helm `pre-upgrade` Job that finds objects with `canfar-net-sessionID` and rewrites labels (and Service selectors) to the canonical set, then removes legacy keys.

## Non-goals

- In-process dual-read / dual-write of both label families in Skaha Java.
- Migrating objects outside the session workload namespace.
- Inventing missing new keys (`flavor`, `accelerator`, …) when legacy objects never had an equivalent.

---

## 1. CONTRIBUTING — Java 21

Update `CONTRIBUTING.md` **Tools** under Java Development Guidelines:

- State that **this repository’s Java modules target Java 21** (`sourceCompatibility` / `targetCompatibility` / toolchain as set in module `build.gradle`).
- Drop or qualify the org-wide “Java 11” wording so it does not contradict Skaha’s build.

---

## 2. Immutable label plan (replace Job round-trip)

### Shape

Introduce a package-private immutable type (name: `SessionLabelPlan`) holding the validated canonical label map for one launch.

Built once in `SessionJobBuilder` after template parameters and resource flavor / GPU count are known:

```text
request params + job resource shape + SKAHA_VERSION
        → SessionLabelPlan
        → applied to Job + pod template
        → passed into launch helpers for Service / Ingress
```

### Projections (boring methods on the plan)

| Method | Purpose |
|--------|---------|
| `jobLabels()` | Full canonical map for Job + pod template metadata |
| `serviceMetadataLabels()` | Canonical keys for Service metadata (same set as today’s `SessionLabels.Key` presence filter) |
| `serviceSelector()` | `canfar.net/id` + `canfar.net/kind` only |
| `ingressMetadataLabels()` | Same as service metadata labels |

No re-parse of Job labels to build Service/Ingress.

### Launch assembly

Collapse `SessionLaunchManifest` (ponytail: one caller / thin wrapper):

- Prefer package helpers, e.g. apply plan projections onto rendered Service YAML / Ingress multi-doc YAML, with Job dump remaining on the builder (or a tiny holder that stores `V1Job` + `SessionLabelPlan` without re-deriving labels from the Job).
- `PostAction` continues: build → stage Job → apply Service/Ingress via plan projections.

### Ponytail shrinks (same change set)

| Current | Replacement |
|---------|-------------|
| `SessionLaunchManifest` as dedicated wrapper | `V1Job` + `SessionLabelPlan` (or helpers taking both); no label re-read from Job |
| `SessionMetadata` six one-line getters | Read map via small static helpers on `SessionLabels` (e.g. required `id` / `username` / `kind`, optional `name` / `appID`, `fixedResources`) used by `SessionBuilder` / selectors |
| `LabelSelectorRequirement` | Format selector clause strings in `labelEquals` / `labelNotEquals`; `selector(String...)` |
| `putRequiredParameterLabel` / `putParameterLabel` varargs plumbing | Inline the fixed parameter→key assignments in plan construction; at most one `firstNonBlank(params, keys...)` helper |

`SessionLabels` remains: key enum, `canonical()`, Kueue helpers, public selector strings, validation.

---

## 3. Helm pre-upgrade label migration Job

### Placement

Skaha Helm chart (`deployments/helm/applications/skaha` when that is the deploy source of truth; keep in sync with any reference chart under `helm/` if both exist in-repo). Gated by values, e.g.:

```yaml
labelMigration:
  enabled: true   # one cutover release; set false afterward
```

### Trigger

- Annotations: `helm.sh/hook: pre-upgrade` (and `pre-install` only if empty-cluster installs need a no-op Job — optional; prefer upgrade-only if first install has nothing to migrate).
- `helm.sh/hook-delete-policy: before-hook-creation,hook-succeeded`
- Weight: before Skaha Deployment upgrade (default hook ordering is enough if the Job is the only pre-upgrade hook of concern; document if other hooks exist).

### Selection

List in the **workload / sessions namespace**:

- `jobs`, `pods`, `services`, `ingresses`

with label selector: **`canfar-net-sessionID`** (existence).  
Kubernetes cannot match “any key with prefix `canfar-net-`”; this key is present on production session surfaces.

### Field mapping

When a legacy key is present on an object, set the canonical key to the same value:

| Legacy | Canonical |
|--------|-----------|
| `canfar-net-sessionID` | `canfar.net/id` |
| `canfar-net-userid` | `canfar.net/username` |
| `canfar-net-sessionName` | `canfar.net/name` |
| `canfar-net-sessionType` | `canfar.net/kind` |
| `canfar-net-appID` | `canfar.net/app-id` |

Then remove the legacy keys from that object.

Do **not** invent `flavor` / `accelerator` / `community` / `project` / `job` / `version` unless a clear legacy equivalent exists on that object. `SessionBuilder.fixedResources` already treats missing flavor as non-fixed; migration must not invent flavor.

### Per-kind updates

| Kind | Updates |
|------|---------|
| Pod | metadata.labels |
| Job | metadata.labels **and** `spec.template.metadata.labels` |
| Service | metadata.labels **and** `spec.selector` (rewrite mapped keys; drop legacy selector keys) |
| Ingress | metadata.labels |

### Ordering (inside the Job, before Skaha rolls)

1. Pods (so endpoints match new selector keys before Services flip)  
2. Jobs (controller + template labels)  
3. Services (metadata + selector)  
4. Ingresses  
5. Fail the Job (block upgrade) on any patch error after retries for transient API errors  

Within each object, prefer a single strategic-merge or JSON patch that adds new keys and removes old keys together.

### RBAC

Hook ServiceAccount (or temporary ClusterRoleBinding in namespace scope) with `get`/`list`/`patch` on `jobs`, `pods`, `services`, `ingresses` in the workload namespace. Prefer not broadening the long-lived Skaha API SA; a hook-local SA is fine.

### Idempotency

- Objects already without `canfar-net-sessionID` are not selected → no-op.  
- Re-run after partial success: only remaining legacy-labeled objects are selected.  
- After cutover, set `labelMigration.enabled: false` and remove the Job templates in a follow-up if desired.

### Ops notes

- Document: upgrade with live sessions requires this Job; drain is an alternative if ops disables the Job.  
- Log counts: selected / patched / skipped per kind.

---

## 4. Tests

- Unit: `SessionLabelPlan` projections (job vs service selector vs metadata).  
- Unit: plan applied to Service YAML selector keys without reading Job.  
- Unit / script smoke: migration mapping function (legacy map → new map + removals) if extracted; otherwise document manual/Dry-run checklist.  
- Existing `SessionJobBuilderTest` / `SessionLabelsTest` updated for helpers (no `SessionMetadata` / `SessionLaunchManifest` as today).

---

## 5. Success criteria

- Launch path never derives Service/Ingress labels by scraping Job metadata.  
- Ponytail targets above are gone or equivalent thinner form.  
- `CONTRIBUTING.md` states Java 21 for this repo’s Java modules.  
- Enabling `labelMigration.enabled` on upgrade rewrites live session objects selected by `canfar-net-sessionID` so post-upgrade Skaha (new selectors only) can manage them without dual-read.

## Open follow-ups (out of this spec)

- Remove migration templates after all environments have cut over.  
- Metrics API documentation alignment called out in PR #1112 summary (separate docs task if still required).
