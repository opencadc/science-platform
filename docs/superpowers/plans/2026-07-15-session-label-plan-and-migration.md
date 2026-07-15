# Session Label Plan + Deploy-Time Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build session labels once as an immutable `SessionLabelPlan`, project them onto Job/Service/Ingress without round-tripping through the Job, document Java 21, and add a gated Helm `pre-upgrade` Job that rewrites live `canfar-net-*` labels to `canfar.net/*`.

**Architecture:** `SessionJobBuilder` constructs a validated `SessionLabelPlan` from request params + resource shape + `SKAHA_VERSION`, applies `jobLabels()` to the Job/pod template, and returns `(V1Job, SessionLabelPlan)`. Service/Ingress YAML helpers consume plan projections only. A one-shot Helm hook Job selects objects with `canfar-net-sessionID` and patches mapped keys before Skaha rolls.

**Tech Stack:** Java 21, JUnit 4, Kubernetes Java client, Helm 3 hooks, `bitnami/kubectl` (or chart-pinned kubectl) for the migration Job.

**Spec:** `docs/superpowers/specs/2026-07-15-session-label-plan-and-migration-design.md`

## Global Constraints

- No in-app dual-read of `canfar-net-*` and `canfar.net/*`.
- Do not invent `flavor` / `accelerator` / `community` / `project` / `job` / `version` during migration when absent.
- Legacy→canonical map (verbatim): `canfar-net-sessionID`→`canfar.net/id`, `canfar-net-userid`→`canfar.net/username`, `canfar-net-sessionName`→`canfar.net/name`, `canfar-net-sessionType`→`canfar.net/kind`, `canfar-net-appID`→`canfar.net/app-id`.
- Migration selector: `canfar-net-sessionID` (existence) on jobs, pods, services, ingresses in the workload namespace.
- Hook order inside Job: Pods → Jobs → Services → Ingresses; fail upgrade on patch error.
- Chart gate: `labelMigration.enabled` (default `true` for cutover; ops set `false` after).
- Repo Java modules: Java 21 (Skaha already `VERSION_21`).
- Keep behavior of new-session labeling unchanged except structural simplification.

## File map

| Path | Role |
|------|------|
| `CONTRIBUTING.md` | Java 21 wording |
| `skaha/.../session/SessionLabelPlan.java` | Immutable plan + projections |
| `skaha/.../session/SessionJobBuilder.java` | Build plan; apply job labels; return job+plan |
| `skaha/.../session/SessionLaunchManifest.java` | Delete; replace with helpers or builder return type |
| `skaha/.../session/SessionLabels.java` | Drop `SessionMetadata` / `LabelSelectorRequirement`; keep keys/selectors/canonical |
| `skaha/.../session/SessionBuilder.java` | Read labels via static helpers |
| `skaha/.../session/PostAction.java` | Use new launch return type / helpers |
| `skaha/src/test/.../SessionLabelPlanTest.java` | Projection tests |
| `skaha/src/test/.../SessionJobBuilderTest.java` | Update for plan API |
| `skaha/src/test/.../SessionLabelsTest.java` | Update for helper shrink |
| `helm/values.yaml` | `labelMigration.*` |
| `helm/templates/label-migration-*.yaml` | Hook Job, SA, Role, RoleBinding |
| `helm/README.md` / `README.md.gotmpl` | Document gate + ordering |

---

### Task 1: Document Java 21 in CONTRIBUTING

**Files:**
- Modify: `CONTRIBUTING.md` (Tools section under Java Development Guidelines)

**Interfaces:**
- Consumes: none
- Produces: CONTRIBUTING states this repo’s Java modules target Java 21

- [ ] **Step 1: Update Tools paragraph**

Replace the Java 11 sentence with:

```markdown
#### Tools
This repository’s Java modules target **Java 21** runtime and source/target compatibility
(see each module’s `build.gradle`, e.g. `sourceCompatibility = JavaVersion.VERSION_21`).
```

Keep the existing gradle-wrapper / directory-structure sentences that follow; only replace the outdated Java 11 / 1.8 toolchain claim for this repo.

- [ ] **Step 2: Commit**

```bash
git add CONTRIBUTING.md
git commit -m "$(cat <<'EOF'
docs: target Java 21 for this repository's Java modules

EOF
)"
```

---

### Task 2: `SessionLabelPlan` + projection tests (TDD)

**Files:**
- Create: `skaha/src/main/java/org/opencadc/skaha/session/SessionLabelPlan.java`
- Create: `skaha/src/test/java/org/opencadc/skaha/session/SessionLabelPlanTest.java`

**Interfaces:**
- Consumes: `SessionLabels.canonical(Map<Key,String>)`, `SessionLabels.Key`, `SessionLabels.version(String)`
- Produces:
  - `static SessionLabelPlan of(Map<String,String> canonicalLabels)`
  - `Map<String,String> jobLabels()`
  - `Map<String,String> serviceMetadataLabels()`
  - `Map<String,String> serviceSelector()` — exactly `canfar.net/id` + `canfar.net/kind`
  - `Map<String,String> ingressMetadataLabels()` — same as `serviceMetadataLabels()`

- [ ] **Step 1: Write failing tests**

```java
package org.opencadc.skaha.session;

import java.util.EnumMap;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

public class SessionLabelPlanTest {
    @Test
    public void projectionsSplitJobMetadataAndSelector() {
        final Map<SessionLabels.Key, String> values = new EnumMap<>(SessionLabels.Key.class);
        values.put(SessionLabels.Key.ID, "session-123");
        values.put(SessionLabels.Key.USERNAME, "alice");
        values.put(SessionLabels.Key.NAME, "Analysis");
        values.put(SessionLabels.Key.KIND, "notebook");
        values.put(SessionLabels.Key.JOB, "notebook-alice-session-123");
        values.put(SessionLabels.Key.FLAVOR, "fixed");
        values.put(SessionLabels.Key.ACCELERATOR, "none");

        final Map<String, String> canonical = new java.util.HashMap<>(SessionLabels.canonical(values));
        canonical.put(SessionLabels.Key.VERSION.label(), SessionLabels.version("1.2.3"));
        final SessionLabelPlan plan = SessionLabelPlan.of(canonical);

        Assert.assertEquals("session-123", plan.jobLabels().get("canfar.net/id"));
        Assert.assertEquals("1.2.3", plan.jobLabels().get("app.kubernetes.io/version"));
        Assert.assertEquals("session-123", plan.serviceMetadataLabels().get("canfar.net/id"));
        Assert.assertEquals("1.2.3", plan.serviceMetadataLabels().get("app.kubernetes.io/version"));
        Assert.assertEquals(2, plan.serviceSelector().size());
        Assert.assertEquals("session-123", plan.serviceSelector().get("canfar.net/id"));
        Assert.assertEquals("notebook", plan.serviceSelector().get("canfar.net/kind"));
        Assert.assertEquals(plan.serviceMetadataLabels(), plan.ingressMetadataLabels());
    }
}
```

- [ ] **Step 2: Run test — expect fail**

```bash
cd skaha && ./gradlew test --tests org.opencadc.skaha.session.SessionLabelPlanTest
```

Expected: compile failure (`SessionLabelPlan` missing).

- [ ] **Step 3: Implement `SessionLabelPlan`**

```java
package org.opencadc.skaha.session;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

final class SessionLabelPlan {
    private final Map<String, String> jobLabels;

    private SessionLabelPlan(final Map<String, String> jobLabels) {
        this.jobLabels = Map.copyOf(jobLabels);
    }

    static SessionLabelPlan of(final Map<String, String> canonicalLabels) {
        return new SessionLabelPlan(Objects.requireNonNull(canonicalLabels, "canonicalLabels cannot be null"));
    }

    Map<String, String> jobLabels() {
        return jobLabels;
    }

    Map<String, String> serviceMetadataLabels() {
        final Map<String, String> metadataLabels = new LinkedHashMap<>();
        for (final SessionLabels.Key key : SessionLabels.Key.values()) {
            final String value = jobLabels.get(key.label());
            if (value != null) {
                metadataLabels.put(key.label(), value);
            }
        }
        return Map.copyOf(metadataLabels);
    }

    Map<String, String> serviceSelector() {
        final Map<String, String> selector = new LinkedHashMap<>();
        selector.put(SessionLabels.Key.ID.label(), required(SessionLabels.Key.ID));
        selector.put(SessionLabels.Key.KIND.label(), required(SessionLabels.Key.KIND));
        return Map.copyOf(selector);
    }

    Map<String, String> ingressMetadataLabels() {
        return serviceMetadataLabels();
    }

    private String required(final SessionLabels.Key key) {
        return Objects.requireNonNull(jobLabels.get(key.label()), key.label() + " label is required");
    }
}
```

- [ ] **Step 4: Run tests — expect pass**

```bash
cd skaha && ./gradlew test --tests org.opencadc.skaha.session.SessionLabelPlanTest
```

- [ ] **Step 5: Commit**

```bash
git add skaha/src/main/java/org/opencadc/skaha/session/SessionLabelPlan.java \
  skaha/src/test/java/org/opencadc/skaha/session/SessionLabelPlanTest.java
git commit -m "$(cat <<'EOF'
feat(skaha): add SessionLabelPlan projections for launch labels

EOF
)"
```

---

### Task 3: Build plan in `SessionJobBuilder`; drop varargs label put helpers

**Files:**
- Modify: `skaha/src/main/java/org/opencadc/skaha/session/SessionJobBuilder.java`
- Modify: `skaha/src/test/java/org/opencadc/skaha/session/SessionJobBuilderTest.java`
- Delete or gut: `SessionLaunchManifest.java` (finished in Task 4; keep compiling until Task 4)

**Interfaces:**
- Consumes: `SessionLabelPlan.of`, `SessionLabels.canonical`
- Produces:
  - `record LaunchArtifacts(V1Job job, SessionLabelPlan labels)` (nested in `SessionJobBuilder` or top-level package-private)
  - `LaunchArtifacts buildLaunch()` — replaces `buildManifest()` return of `SessionLaunchManifest`
  - `String build()` still returns `Yaml.dump(job)` for any callers that only need Job YAML
  - Private `firstNonBlank(String... keys)` for param lookup

- [ ] **Step 1: Adjust tests that call `buildManifest()`**

Update `SessionJobBuilderTest` to:

```java
final SessionJobBuilder.LaunchArtifacts launch =
        SessionJobBuilder.fromPath(testBaseValuesPath)...buildLaunch();
final Map<String, String> jobLabels = launch.job().getMetadata().getLabels();
// assert canfar.net/* as today
final Map<String, String> serviceSelector = launch.labels().serviceSelector();
Assert.assertEquals("session-123", serviceSelector.get("canfar.net/id"));
```

Keep existing assertion values; only change how artifacts are obtained. For Service YAML tests, call the Task 4 helper once available; until then assert on `launch.labels().serviceMetadataLabels()` / `serviceSelector()`.

- [ ] **Step 2: Run tests — expect fail on missing `buildLaunch` / `LaunchArtifacts`**

```bash
cd skaha && ./gradlew test --tests org.opencadc.skaha.session.SessionJobBuilderTest
```

- [ ] **Step 3: Implement plan construction + Job apply**

In `SessionJobBuilder`:

```java
record LaunchArtifacts(V1Job job, SessionLabelPlan labels) {}

LaunchArtifacts buildLaunch() throws IOException {
    // existing template read + parameter substitution
    final V1Job launchJob = (V1Job) Yaml.load(jobFileString);
    final SessionLabelPlan plan = buildLabelPlan(launchJob);
    applyJobLabels(launchJob, plan);
    mergeQueue(launchJob);
    mergeAffinity(launchJob);
    mergeImagePullSecret(launchJob);
    return new LaunchArtifacts(launchJob, plan);
}

String build() throws IOException {
    return Yaml.dump(buildLaunch().job());
}

private SessionLabelPlan buildLabelPlan(final V1Job launchJob) {
    final Map<SessionLabels.Key, String> labelValues = new EnumMap<>(SessionLabels.Key.class);
    labelValues.put(SessionLabels.Key.ID, requireParam(PostAction.SKAHA_SESSIONID));
    labelValues.put(SessionLabels.Key.USERNAME, requireParam(PostAction.SKAHA_USERID));
    labelValues.put(SessionLabels.Key.NAME, requireParam(PostAction.SKAHA_SESSIONNAME));
    labelValues.put(SessionLabels.Key.KIND, requireParam(PostAction.SKAHA_SESSIONTYPE));
    final String appId = firstNonBlank(PostAction.SOFTWARE_APPID);
    if (appId != null) {
        labelValues.put(SessionLabels.Key.APP_ID, appId);
    }
    labelValues.put(
            SessionLabels.Key.JOB,
            requireFirstNonBlank(PostAction.SOFTWARE_JOBNAME, PostAction.SKAHA_JOBNAME));
    labelValues.put(SessionLabels.Key.FLAVOR, getResourceFlavor(launchJob));
    labelValues.put(SessionLabels.Key.ACCELERATOR, this.gpuCount > 0 ? "gpu" : "none");

    final Map<String, String> labels = new HashMap<>(SessionLabels.canonical(labelValues));
    final String skahaVersion = K8SUtil.getSkahaVersion();
    if (StringUtil.hasText(skahaVersion)) {
        labels.put(SessionLabels.Key.VERSION.label(), SessionLabels.version(skahaVersion));
    }
    return SessionLabelPlan.of(labels);
}

private void applyJobLabels(final V1Job launchJob, final SessionLabelPlan plan) {
    final Map<String, String> labels = plan.jobLabels();
    getOrCreateJobLabels(launchJob).putAll(labels);
    // same pod-template label putAll as today's mergeSessionLabels
}

private String firstNonBlank(final String... keys) {
    for (final String key : keys) {
        final String value = this.parameters.get(key);
        if (StringUtil.hasText(value)) {
            return value;
        }
    }
    return null;
}

private String requireParam(final String key) {
    return requireFirstNonBlank(key);
}

private String requireFirstNonBlank(final String... keys) {
    final String value = firstNonBlank(keys);
    if (value == null) {
        throw new IllegalArgumentException("requires one of parameters " + String.join(", ", keys));
    }
    return value;
}
```

Delete `putRequiredParameterLabel`, `putParameterLabel`, and `mergeSessionLabels` once callers are gone. Temporarily keep `buildManifest()` as:

```java
@Deprecated
SessionLaunchManifest buildManifest() throws IOException {
    final LaunchArtifacts launch = buildLaunch();
    return SessionLaunchManifest.fromJobAndPlan(launch.job(), launch.labels());
}
```

only if Task 4 is not completed in the same commit; prefer completing Task 4 in the same change set if tests allow.

- [ ] **Step 4: Run `SessionJobBuilderTest` — pass**

```bash
cd skaha && ./gradlew test --tests org.opencadc.skaha.session.SessionJobBuilderTest
```

- [ ] **Step 5: Commit**

```bash
git add skaha/src/main/java/org/opencadc/skaha/session/SessionJobBuilder.java \
  skaha/src/test/java/org/opencadc/skaha/session/SessionJobBuilderTest.java
git commit -m "$(cat <<'EOF'
refactor(skaha): build SessionLabelPlan once in SessionJobBuilder

EOF
)"
```

---

### Task 4: Remove `SessionLaunchManifest`; wire `PostAction` to plan helpers

**Files:**
- Delete: `skaha/src/main/java/org/opencadc/skaha/session/SessionLaunchManifest.java`
- Modify: `skaha/src/main/java/org/opencadc/skaha/session/SessionJobBuilder.java` (add static YAML helpers)
- Modify: `skaha/src/main/java/org/opencadc/skaha/session/PostAction.java`
- Modify: `SessionJobBuilderTest.java` (service/ingress labeling assertions via helpers)

**Interfaces:**
- Consumes: `LaunchArtifacts`, `SessionLabelPlan` projections
- Produces:
  - `static String labelService(String serviceYaml, SessionLabelPlan plan) throws IOException`
  - `static String labelIngress(String ingressYaml, SessionLabelPlan plan)`
  - `PostAction.createSession` uses `buildLaunch()` then `Yaml.dump(job)` / `labelService` / `labelIngress`

- [ ] **Step 1: Move YAML apply logic onto builder (or `SessionLabelYaml` helper)**

Port current `SessionLaunchManifest.service` / `ingress` bodies to:

```java
static String labelService(final String serviceString, final SessionLabelPlan plan) throws IOException {
    final V1Service service = (V1Service) Yaml.load(serviceString);
    // putAll plan.serviceMetadataLabels() onto metadata.labels
    // set spec.selector to plan.serviceSelector()
    return Yaml.dump(service);
}

static String labelIngress(final String ingressString, final SessionLabelPlan plan) {
    // same snakeyaml multi-doc walk as today, putAll plan.ingressMetadataLabels()
}
```

Do **not** call `SessionLabels.fromMetadata` or read Job labels.

- [ ] **Step 2: Update `PostAction`**

```java
final SessionJobBuilder.LaunchArtifacts launch = sessionJobBuilder.buildLaunch();
String jobLaunchString = Yaml.dump(launch.job());
// ...
serviceString = SessionJobBuilder.labelService(serviceString, launch.labels());
// ...
ingressString = SessionJobBuilder.labelIngress(ingressString, launch.labels());
```

Add `import io.kubernetes.client.util.Yaml` if missing.

- [ ] **Step 3: Delete `SessionLaunchManifest.java` and any imports**

- [ ] **Step 4: Run session tests**

```bash
cd skaha && ./gradlew test --tests org.opencadc.skaha.session.SessionJobBuilderTest \
  --tests org.opencadc.skaha.session.SessionLabelsTest \
  --tests org.opencadc.skaha.session.SessionBuilderTest
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add -A skaha/src/main/java/org/opencadc/skaha/session/ \
  skaha/src/test/java/org/opencadc/skaha/session/
git commit -m "$(cat <<'EOF'
refactor(skaha): drop SessionLaunchManifest; label Service/Ingress from plan

EOF
)"
```

---

### Task 5: Shrink `SessionLabels` (ponytail: `SessionMetadata`, selector requirement type)

**Files:**
- Modify: `skaha/src/main/java/org/opencadc/skaha/session/SessionLabels.java`
- Modify: `skaha/src/main/java/org/opencadc/skaha/session/SessionBuilder.java`
- Modify: `skaha/src/test/java/org/opencadc/skaha/session/SessionLabelsTest.java`
- Modify: any other `SessionMetadata` / `fromMetadata` call sites

**Interfaces:**
- Consumes: label maps on Jobs
- Produces static helpers:
  - `static String require(Map<String,String> labels, Key key)`
  - `static String get(Map<String,String> labels, Key key)`
  - `static boolean fixedResources(Map<String,String> labels)`
- Selector helpers return strings directly:

```java
private static String labelEquals(final Key key, final String value) {
    return key.label + "=" + validateLabelValue(key.label, value);
}

private static String labelNotEquals(final Key key, final String value) {
    return key.label + "!=" + validateLabelValue(key.label, value);
}

private static String selector(final String... requirements) {
    return String.join(",", requirements);
}
```

- [ ] **Step 1: Update `SessionBuilder.fromJob` to use map helpers**

```java
final Map<String, String> labels = jobMetadata.getLabels();
Objects.requireNonNull(labels, "Invalid Job with null Labels");
final String sessionID = SessionLabels.require(labels, SessionLabels.Key.ID);
// username, kind via require; name/appID via get; fixedResources(labels)
```

Remove `SessionLabels.fromMetadata` / nested `SessionMetadata`.

- [ ] **Step 2: Delete `LabelSelectorRequirement`; fix `forUserSessions` list to `List<String>`**

- [ ] **Step 3: Run tests**

```bash
cd skaha && ./gradlew test --tests org.opencadc.skaha.session.*
```

- [ ] **Step 4: Commit**

```bash
git add skaha/src/main/java/org/opencadc/skaha/session/SessionLabels.java \
  skaha/src/main/java/org/opencadc/skaha/session/SessionBuilder.java \
  skaha/src/test/java/org/opencadc/skaha/session/
git commit -m "$(cat <<'EOF'
refactor(skaha): shrink SessionLabels metadata and selector helpers

EOF
)"
```

---

### Task 6: Helm `pre-upgrade` label migration Job

**Files:**
- Modify: `helm/values.yaml` — add `labelMigration` block near top-level (after `experimentalFeatures` or beside session config)
- Create: `helm/templates/label-migration-rbac.yaml`
- Create: `helm/templates/label-migration-job.yaml`
- Modify: `helm/README.md.gotmpl` (prose after values section) and regenerate README via helm-docs if that is the chart’s process; if science-platform chart uses hand-maintained README, update `helm/README.md` with the same notes.

**Interfaces:**
- Consumes: workload namespace from chart (same helper as sessions / `skaha.namespace`)
- Produces: gated hook resources when `labelMigration.enabled`

Values:

```yaml
labelMigration:
  enabled: true
  image: bitnami/kubectl:1.29.0
  backoffLimit: 1
```

- [ ] **Step 1: Add RBAC template** (`helm/templates/label-migration-rbac.yaml`)

```yaml
{{- if .Values.labelMigration.enabled }}
apiVersion: v1
kind: ServiceAccount
metadata:
  name: {{ include "skaha.fullname" . }}-label-migration
  annotations:
    helm.sh/hook: pre-upgrade
    helm.sh/hook-weight: "-20"
    helm.sh/hook-delete-policy: before-hook-creation,hook-succeeded
---
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: {{ include "skaha.fullname" . }}-label-migration
  annotations:
    helm.sh/hook: pre-upgrade
    helm.sh/hook-weight: "-20"
    helm.sh/hook-delete-policy: before-hook-creation,hook-succeeded
rules:
  - apiGroups: ["batch"]
    resources: ["jobs"]
    verbs: ["get", "list", "patch"]
  - apiGroups: [""]
    resources: ["pods", "services"]
    verbs: ["get", "list", "patch"]
  - apiGroups: ["networking.k8s.io"]
    resources: ["ingresses"]
    verbs: ["get", "list", "patch"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: {{ include "skaha.fullname" . }}-label-migration
  annotations:
    helm.sh/hook: pre-upgrade
    helm.sh/hook-weight: "-20"
    helm.sh/hook-delete-policy: before-hook-creation,hook-succeeded
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: Role
  name: {{ include "skaha.fullname" . }}-label-migration
subjects:
  - kind: ServiceAccount
    name: {{ include "skaha.fullname" . }}-label-migration
{{- end }}
```

Use the chart’s real `fullname` helper name (match `_helpers.tpl`, e.g. `skaha.fullname` vs `common.names.fullname`).

- [ ] **Step 2: Add Job template** with embedded script

Job annotations: `helm.sh/hook: pre-upgrade`, `hook-weight: "-10"`, same delete policy.  
`serviceAccountName`: label-migration SA.  
Container command runs bash that:

1. Sets `NS` to workload namespace.
2. Defines associative map of legacy→canonical keys.
3. For kinds in order `pod`, `job`, `service`, `ingress`:
   - `kubectl get <kind> -n "$NS" -l canfar-net-sessionID -o json`
   - For each item, build a JSON patch / use `kubectl label --overwrite` for adds and `kubectl label ... canfar-net-sessionID-` style removals **and** for Jobs also patch `spec.template.metadata.labels`; for Services also rewrite `spec.selector`.
4. Prefer a single Python/`kubectl patch` strategy that merges labels maps: copy mapped keys, delete legacy keys. Example approach: `kubectl get -o json | jq` rewrite | `kubectl apply --server-side` **or** per-object `kubectl patch --type=merge`.
5. `set -euo pipefail`; log counts; non-zero exit on failure.

Minimal working pattern (document in template comments): for Services, after label migration, ensure `spec.selector` uses `canfar.net/id` / `canfar.net/kind` when those values exist (from old `canfar-net-sessionID` / `canfar-net-sessionType`).

- [ ] **Step 3: Document in README.gotmpl / README**

After values section prose:

```markdown
### Session label migration

When `labelMigration.enabled` is true, a Helm `pre-upgrade` Job selects Jobs/Pods/Services/Ingresses
with `canfar-net-sessionID` and rewrites mapped labels to `canfar.net/*` before Skaha rolls.
Disable after all environments have cut over. Alternative: drain sessions and upgrade with the Job disabled.
```

- [ ] **Step 4: Helm lint**

```bash
cd helm && helm lint .
```

Expected: no ERROR for the new templates (warnings OK if pre-existing).

- [ ] **Step 5: Commit**

```bash
git add helm/values.yaml helm/templates/label-migration-rbac.yaml \
  helm/templates/label-migration-job.yaml helm/README.md helm/README.md.gotmpl
git commit -m "$(cat <<'EOF'
feat(helm): add pre-upgrade job to migrate canfar-net session labels

EOF
)"
```

---

### Task 7: Full Skaha test suite + self-check vs spec

**Files:** none new

- [ ] **Step 1: Run full Skaha tests**

```bash
cd skaha && ./gradlew test
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Spec checklist**

Confirm against `docs/superpowers/specs/2026-07-15-session-label-plan-and-migration-design.md`:

- [ ] Launch path never scrapes Job labels for Service/Ingress
- [ ] `SessionLaunchManifest` / `SessionMetadata` / `LabelSelectorRequirement` / varargs put helpers gone
- [ ] CONTRIBUTING Java 21
- [ ] Hook Job gated, selector `canfar-net-sessionID`, mapping table exact, Pod→Job→Service→Ingress order
- [ ] No dual-read in Java

- [ ] **Step 3: Commit only if Step 2 fixes were needed**; otherwise done.

---

## Spec coverage (self-review)

| Spec section | Task |
|--------------|------|
| §1 CONTRIBUTING Java 21 | Task 1 |
| §2 SessionLabelPlan + projections | Tasks 2–3 |
| §2 collapse SessionLaunchManifest | Task 4 |
| §2 ponytail shrinks | Tasks 3–5 |
| §3 Helm migration Job | Task 6 |
| §4 Tests | Tasks 2–5, 7 |
| Non-goal: dual-read | Global Constraints |
