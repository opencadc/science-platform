# CANFAR Batch

Status: specification for review. This document does not authorize implementation.
Updated: 2026-11-13.
Depends on: opencadc/science-platform#1249 (per-user LocalQueue routing). This work starts after that change merges.

A batch is one session and one `batch/v1` Job. The caller sets how many indexed jobs to run. Skaha asks Kueue for that many pods at once, with a minimum of one. Kueue starts as many as the queue can hold. The Job controller leaves the rest uncreated until a running pod finishes. Each index succeeds or fails once.

`GET /v1/session` lists a batch as a short entry in the existing session format. `GET /v1/session/{id}` returns the full Batch document. That document has `apiVersion`, `kind`, `metadata`, `spec`, and `status`, the same fields as a Metrics report. `kind` is `Batch`.

## 1. Terms

| Term | Meaning |
|---|---|
| Replica | One indexed job. `replicas` is how many to run, from 1 to 256 |
| Index | Replica number `0` through `replicas - 1`. The pod receives it as `CANFAR_REPLICA_INDEX` and as `JOB_COMPLETION_INDEX` |
| Admitted parallelism | The `spec.parallelism` Kueue writes before it clears `suspend`. That many pods run at once |
| Held | One pod's request times the pods the batch can still run at once: `min(parallelism, replicas - succeeded)`. A failed index stays held, as Kueue reclaims quota only for succeeded indexes. Zero while suspended or finished |
| Total | One pod's request times `replicas`. The demand of the whole run. It does not change |
| `maxfail` | A number from 0 to 1, or absent. The Job stops when the failed fraction of replicas reaches `maxfail` |

Skaha writes `completionMode`, `completions`, and `parallelism` on the Job. Callers do not send them.

## 2. Indexed jobs

`completionMode` is `Indexed`. Skaha does not write `NonIndexed`. Indexed mode assigns each replica an index, one success or failure, an exit code, and `maxFailedIndexes`.

| Job field | Value |
|---|---|
| `completionMode` | `Indexed` |
| `completions` | `replicas` |
| `parallelism` | `replicas` |
| `kueue.x-k8s.io/job-min-parallelism` | `"1"` when `replicas` is greater than 1. Omitted when `replicas` is 1, because Kueue requires the minimum to be lower than the requested parallelism |

Kueue 0.17 enables partial admission by default for a Job. The JobSet integration in that release records a pod-set count and records no minimum. This version creates a Job so Kueue can apply the minimum. A later object can group Jobs for dependencies or a leader.

For 50 replicas and room for 10 pods:

1. Skaha creates the Job with `suspend: true`, `completions: 50`, `parallelism: 50`, and `job-min-parallelism: "1"`.
2. Kueue rewrites `parallelism` to 10 and clears `suspend`. `completions` stays 50.
3. The Job controller creates indexes 0 through 9. Indexes 10 through 49 stay uncreated.
4. When one pod finishes, the controller creates the next index. Admitted parallelism stays 10 for the rest of the run.
5. Later indexes reuse those 10 queue slots. Another workload releasing quota leaves this Job at parallelism 10. Kueue writes a new parallelism only when it preempts the Job and admits it again.

When the queue has no free slot, the Job stays suspended. The Job controller creates no pods. `replicas` 1 requests one pod. Kueue admits it when one slot is free.

Admitted parallelism is fixed for the run. A batch submitted while the cluster is busy can run narrow for its whole life while the cluster empties. The user docs say so and suggest deleting and resubmitting a batch that is stuck narrow.

Each index succeeds or fails once. `backoffLimitPerIndex` is 0 and `restartPolicy` is `Never`. After the container exits, Kubernetes does not start it again. An index can still **run** more than once:

- Kueue preemption suspends the Job and deletes its running pods. Batch has `low` priority (section 8), so interactive sessions can preempt it. When Kueue admits the Job again, those indexes start from the beginning, possibly at a smaller parallelism.
- A node drain or a kubelet eviction sets `DisruptionTarget`. `podFailurePolicy` ignores that condition, and Kubernetes replaces the pod. The replacement does not count as a failed index.

Workers must be safe to run again. The user docs and the canfar skill say so.

## 3. Session API

Create uses the session form. Skaha reads the existing `type` parameter. The batch value is `type=batch`. Python and the CLI call that value `kind`.

Batch routes use the same authentication as the other session routes. Create returns `text/plain`, the session id. List and info return JSON. Batch errors use the Metrics failure document (section 3.7).

### 3.1 Routes

| Method | Path | Success |
|---|---|---|
| POST | `/v1/session` with `type=batch` | 200, `text/plain`, one id |
| GET | `/v1/session` | 200, JSON array. Each batch is one short entry (section 3.3) |
| GET | `/v1/session?type=batch` | 200, JSON array of short batch entries only |
| GET | `/v1/session/{id}` | 200, one `Batch` document (section 3.4) |
| DELETE | `/v1/session/{id}` | 200. Background delete. A later GET returns 404 |
| GET | `/v1/session/{id}?view=logs&replica={n}` | 200, `text/plain`, the log of replica `n` |

`GET /v1/session?view=interactive` omits batches. The interactive session cap ignores batches. The one-session-per-type rule ignores batches. A user may own more than one batch.

Delete uses background propagation, the same as other sessions. The Job object is gone at once, and the next GET returns 404. Pods can run for a few seconds after that while Kubernetes removes them. There is no `Terminating` phase.

### 3.2 Create parameters

`name`, `image`, `cmd`, `args`, `env`, `cores`, `ram`, and `gpus` match a headless create. `cmd`, `args`, and `env` are valid for `headless` and `batch`. `cores`, `ram`, and `gpus` are the request for one pod. Skaha writes those values onto one pod.

`maxfail` is the only parameter this version adds. Skaha ignores unknown parameters, `parallelism` included.

| Parameter | Required | Default | Constraints |
|---|---|---|---|
| `replicas` | no | 1 | integer 1–256. Valid above 1 only for `type=batch` |
| `maxfail` | no | absent | decimal from 0 to 1 inclusive, at most 32 decimal places. Valid only for `type=batch` |
| `env` | no | none | `NAME=value`. A name that starts with `CANFAR_`, in any case, is rejected for every session type (section 5.3) |

When the form omits `maxfail`, the Batch document sets it to JSON `null`. The server parses a present value as a decimal. 0.25 stays 0.25. The client omits the form field when the caller leaves it unset.

| `type` | `replicas` greater than 1 | `maxfail` |
|---|---|---|
| `batch` | One POST, one id, that many indexes | Accepted |
| `headless` | Deprecated on the client. `FutureWarning`, then one POST per replica. A later release removes that path | 400 |
| every other type | 400. Desktop and Firefly already reject it | 400 |

### 3.3 List entry

`GET /v1/session` keeps its current JSON array. A batch is one entry per Job, in the existing session format, plus a `replicas` block and `completionTime`. Other session types do not change.

```json
{
  "id": "k7nq2wpx",
  "userid": "bob",
  "runAsUID": "20001",
  "runAsGID": "20001",
  "supplementalGroups": [20001],
  "appid": "<none>",
  "image": "images.canfar.net/skaha/astroml:latest",
  "type": "batch",
  "status": "Running",
  "name": "field-reduction",
  "startTime": "2026-11-13T17:06:02Z",
  "expiryTime": "2026-11-27T17:06:02Z",
  "connectURL": "",
  "requestedCPUCores": "10.000",
  "requestedRAM": "42.95",
  "requestedGPUCores": "0",
  "cpuCoresInUse": "6.300",
  "ramInUse": "23.19",
  "isFixedResources": true,
  "replicas": {"total": 50, "succeeded": 12, "failed": 1, "active": 10, "incomplete": 27}
}
```

| Field | Source |
|---|---|
| `status` | the Batch `status.phase`: `Pending`, `Running`, `Completed`, or `Failed` |
| `startTime` | the Job `status.startTime`. Absent until Kueue admits the Job |
| `expiryTime` | `startTime` plus `activeDeadlineSeconds` (14 days). Batches cannot be renewed; a renew request returns 400 |
| `completionTime` | the Job `status.completionTime`, or, when Kubernetes leaves that unset (it does for a failed Job), the time of the true `Complete` or `Failed` condition. Absent until the Job finishes, like other unset list fields |
| `connectURL` | `""`. `SessionDAO.getConnectURL` already returns that for a type without a Service |
| `requestedCPUCores`, `requestedRAM`, `requestedGPUCores` | the Metrics session report `requests`, which is what the batch holds now. Not the total and not one pod. Formatted like the same fields of other sessions. `requestedGPUCores` is `"0"` when the report has no GPU row |
| `cpuCoresInUse`, `ramInUse` | the Metrics session report `usage` |
| `replicas` | `spec.replicas` and the Job counters, as in the Batch document |

Batch entries read the Metrics session report whatever `SKAHA_POD_METRICS_SOURCE` is set to. The list uses the same bounded fan-out as backend pod metrics (8 concurrent, 30 s overall). When the report fails, the requested and in-use fields are empty, and the entry is still returned.

### 3.4 Info

`GET /v1/session/{id}` for a batch returns this document. `metadata.name` is the session id. `metadata.labels` copies the standard session labels from the Job. `spec.resources` is one pod's request, in the Metrics quantity format: CPU is a decimal, and memory is a `Gi` string. `status.resources` is copied from the Metrics session report (section 4).

The snapshot: 50 replicas, each pod requesting 1 CPU and 4Gi. Kueue admitted 10. 12 indexes have succeeded and 1 has failed. Of the 10 active pods, 9 are `Running` and 1 is not yet scheduled. 27 indexes have no pod.

```json
{
  "apiVersion": "canfar.net/v1alpha1",
  "kind": "Batch",
  "metadata": {
    "name": "k7nq2wpx",
    "creationTimestamp": "2026-11-13T17:06:00Z",
    "labels": {
      "canfar.net/id": "k7nq2wpx",
      "canfar.net/kind": "batch",
      "canfar.net/name": "field-reduction",
      "canfar.net/username": "bob",
      "canfar.net/community": "cadc",
      "canfar.net/project": "default"
    }
  },
  "spec": {
    "replicas": 50,
    "maxfail": 0.25,
    "image": "images.canfar.net/skaha/astroml:latest",
    "command": ["python"],
    "args": ["/arc/projects/demo/worker.py"],
    "resources": {"cpu": "1", "memory": "4Gi"}
  },
  "status": {
    "observedAt": "2026-11-13T18:20:00Z",
    "phase": "Running",
    "startTime": "2026-11-13T17:06:02Z",
    "completionTime": null,
    "succeeded": 12,
    "failed": 1,
    "active": 10,
    "incomplete": 27,
    "resources": [
      {"name": "cpu", "total": "50", "requests": "10", "usage": "6.3", "efficiency": "0.68"},
      {"name": "memory", "total": "200Gi", "requests": "40Gi", "usage": "21.6Gi", "efficiency": "0.55"}
    ],
    "replicas": [
      {"id": 0, "phase": "Succeeded", "exitCode": 0, "startTime": "2026-11-13T17:06:04Z", "completionTime": "2026-11-13T17:21:10Z"},
      {"id": 1, "phase": "Failed", "exitCode": 2, "startTime": "2026-11-13T17:06:04Z", "completionTime": "2026-11-13T17:09:40Z"},
      {"id": 13, "phase": "Running", "exitCode": null, "startTime": "2026-11-13T18:02:11Z", "completionTime": null},
      {"id": 22, "phase": "Pending", "exitCode": null, "startTime": null, "completionTime": null},
      {"id": 23, "phase": "Pending", "exitCode": null, "startTime": null, "completionTime": null},
      {"id": 49, "phase": "Pending", "exitCode": null, "startTime": null, "completionTime": null}
    ],
    "conditions": [
      {"type": "Admitted", "status": "True", "reason": "PartialAdmission", "lastTransitionTime": "2026-11-13T17:06:02Z"},
      {"type": "Complete", "status": "False", "reason": "IndexesIncomplete", "lastTransitionTime": "2026-11-13T17:06:00Z"},
      {"type": "Failed", "status": "False", "reason": "IndexesIncomplete", "lastTransitionTime": "2026-11-13T17:06:00Z"},
      {"type": "Resources", "status": "True", "reason": "Available", "lastTransitionTime": "2026-11-13T18:20:00Z"}
    ]
  }
}
```

The real document has all 50 `status.replicas` entries, in index order; this example shows six. Index 22 has a pod that is not yet scheduled. It counts in `active`, and its row says `Pending`.

| Field | Source |
|---|---|
| `spec.replicas`, `spec.maxfail`, `spec.image`, `spec.command`, `spec.args`, `spec.resources` | the stored request. `maxfail` is `null` when the form omitted it |
| `status.phase` | `Pending` while suspended or before any pod runs, `Running` after admission, `Completed` once the Job has a true `Complete` or `SuccessCriteriaMet` condition, `Failed` once it has a true `Failed` or `FailureTarget` condition. Same rule as the session `status` of other types |
| `status.startTime` | the Job `status.startTime`. `null` until Kueue admits the Job |
| `status.completionTime` | the Job `status.completionTime`, else the time of the true `Complete` or `Failed` condition. `null` until the Job finishes |
| `succeeded`, `failed`, `active` | the Job status counters, unchanged. `active` includes pods that are not yet scheduled |
| `incomplete` | `replicas - succeeded - failed - active`. These indexes have no pod |
| `status.resources` | the Metrics session report rows for this id, copied unchanged. Empty when the read fails |
| `status.replicas` | one row per index. `id` is the completion index. `phase` is the phase of the newest pod for that index, or `Pending` when the index has no pod. `exitCode` is the app container's terminated exit code on that pod, or `null` |

`exitCode` stays readable while the pod exists: during the run and for the Job TTL after it finishes (section 5.2).

Skaha builds the Batch conditions itself. The `Failed` reasons are copied from the Job; the other reasons are Skaha's.

| Condition | Status / reason | When |
|---|---|---|
| `Admitted` | `False` / `Suspended` | `spec.suspend` is true |
| `Admitted` | `True` / `PartialAdmission` | live `parallelism` is below `replicas` |
| `Admitted` | `True` / `Ready` | live `parallelism` equals `replicas` |
| `Complete` | `True` / `AllIndexesSucceeded` | the Job has a true `Complete` condition |
| `Complete` | `False` / `IndexesIncomplete` | otherwise |
| `Failed` | `True` / the Job reason | the Job has a true `Failed` condition: `MaxFailedIndexesExceeded`, `FailedIndexes`, `DeadlineExceeded`, `BackoffLimitExceeded`, or `PodFailurePolicy` |
| `Failed` | `False` / `IndexesIncomplete` | otherwise |
| `Resources` | `True` / `Available` | the Metrics read succeeded |
| `Resources` | `False` / `Unavailable` | the Metrics read failed. Info still returns `status.replicas` |

The Batch document leaves out the Metrics `Ready` and `Cached` conditions, which describe the Metrics cache.

### 3.5 Logs

`GET /v1/session/{id}?view=logs&replica=1` returns the app-container log of the newest pod whose label `batch.kubernetes.io/job-completion-index` is `1`. The body is `text/plain`.

A batch has one log per replica. The request names the replica. Skaha does not concatenate the pods, and it does not default to replica 0. The route is the existing `view=logs` route with one new parameter. Interactive and headless logs keep their current behavior.

| Status | When |
|---|---|
| 200 | the replica has a pod |
| 400 | `replica` is missing, `replica` is outside `0` through `replicas - 1`, or `replica` is sent for a session that is not a batch |
| 404 | the replica has no pod |

### 3.6 maxfail

The server multiplies by `replicas` and computes the limit at create time. An absent `maxfail` leaves `maxFailedIndexes` unset. The Job controller starts every index, up to the admitted parallelism. When the Job finishes, any failed index sets `phase` to `Failed` and the `Failed` reason to `FailedIndexes`.

For a value below 1, the server uses decimal arithmetic and rounds up:

```text
K = max(1, ceil(maxfail * replicas))
maxFailedIndexes = K - 1
```

The Job stops at the K-th failed index, when the failed fraction reaches `maxfail`. A `maxfail` of 1 also leaves `maxFailedIndexes` unset: every index may fail, and the Job still ends `Failed` if any index failed.

| `maxfail` | Replicas | `maxFailedIndexes` | Result |
|---|---|---|---|
| absent | 50 | omitted | all 50 indexes run |
| 0 | 50 | 0 | the first failed index stops the Job |
| 0.25 | 50 | 12 | the 13th failed index stops the Job |
| 0.25 | 4 | 0 | the first failed index stops the Job |
| 0.25 | 3 | 0 | the first failed index stops the Job |
| 1 | 50 | omitted | all 50 indexes run |

0.25 of 50 is 12.5, which rounds up to 13. 0.25 of 4 is exactly 1, so the first failure reaches the fraction.

### 3.7 Errors

Batch requests use the Metrics status document for errors. A batch request is any request with `type=batch`, any request that sends `maxfail`, and any request that names a batch session. Other session errors keep their current format, so the 400s below for `replicas` above 1 on another type, a `CANFAR_` env name on another type, or `replica` on a non-batch log stay `text/plain`. So does the 404 for an id with no session at all, because Skaha cannot tell that it names a batch.

```json
{"apiVersion": "v1", "kind": "Status", "status": "Failure", "reason": "BadRequest", "message": "maxfail is valid only for a batch.", "code": 400}
```

| Code | `reason` | When |
|---|---|---|
| 401 / 403 | the existing session reason | authentication and authorization |
| 400 | `BadRequest` | `maxfail` on a type other than `batch`; `maxfail` outside 0–1; `replicas` outside 1–256; `replicas` above 1 on a type other than `batch`; an `env` name starting with `CANFAR_`; a batch log without `replica` or with `replica` out of range |
| 404 | `NotFound` | no batch with this id for this user; a log replica that has no pod; the Community ClusterQueue does not exist (`LocalQueues.ensure`) |
| 503 | `ServiceUnavailable` | `LocalQueues.ensure` could not use the user's LocalQueue, or the Job create failed. `message` is the shared `Session queue unavailable.  Please try again.` |

The 503 comes from the launch path in #1249 that every session type uses, so a queue outage fails batch and the other types together. 401 and 403 keep their existing `text/plain` body. The server rejects `replicas` above 1 for headless; the deprecated client path never sends it, because it posts one create per replica.

## 4. Metrics

The resource report stays on the existing route:

`GET /apis/canfar.net/v1alpha1/metrics/session/{id}`

Each resource row of a batch has four values:

| Field | Meaning |
|---|---|
| `total` | new. One pod's request times `replicas`. The demand of the whole run |
| `requests` | what the batch holds now. One pod's request times `min(parallelism, completions - succeeded)` while the Job is reserving, otherwise zero (`0` for CPU, `0Gi` for memory) |
| `usage` | the sum over pods in phase `Running` with `canfar.net/id` |
| `efficiency` | the existing session-window ratio. The query counts requests only while each pod is `Running`, so the ratio covers the pods in flight |

The same snapshot as section 3.4:

```json
{
  "apiVersion": "canfar.net/v1alpha1",
  "kind": "Metrics",
  "metadata": {"name": "session-k7nq2wpx"},
  "spec": {"session": "k7nq2wpx"},
  "status": {
    "observedAt": "2026-11-13T18:20:00Z",
    "reservingWorkloads": 1,
    "resources": [
      {"name": "cpu", "total": "50", "requests": "10", "usage": "6.3", "efficiency": "0.68"},
      {"name": "memory", "total": "200Gi", "requests": "40Gi", "usage": "21.6Gi", "efficiency": "0.55"}
    ],
    "conditions": [
      {"type": "Ready", "status": "True", "reason": "Available", "lastTransitionTime": "2026-11-13T18:20:00Z"},
      {"type": "Cached", "status": "False", "reason": "Refreshed", "lastTransitionTime": "2026-11-13T18:20:00Z"}
    ]
  }
}
```

The batch report at each stage:

| Stage | `reservingWorkloads` | `total` | `requests` | `usage` | `efficiency` |
|---|---|---|---|---|---|
| Suspended | 0 | 50 / 200Gi | `0` / `0Gi` | absent | absent |
| Running at 10 | 1 | 50 / 200Gi | 10 / 40Gi | live | window value |
| 3 indexes left | 1 | 50 / 200Gi | 3 / 12Gi | live | window value |
| Finished, within the TTL | 0 | 50 / 200Gi | `0` / `0Gi` | absent | absent |

Rules:

- A Job labelled `canfar.net/kind=batch` always produces rows, while suspended and after it finishes, so `total` stays visible.
- Every other session keeps the current rule: no `total`, and `resources` is `[]` while the Job is not reserving. A headless report is unchanged.
- A GPU row carries `total` and `requests` only.
- `requests` follows Kueue's reclaimable-pods accounting: near the end of a run, the held count falls with the remaining indexes.
- Platform, user, and community reports do not change. They sum Kueue reservations, so `total` never inflates them. User reports find the batch's quota through its private LocalQueue (`canfar.net/username`), as for any session.
- Metrics has no `/metrics/batch` route and no cumulative `consumed` field in this version.

Skaha copies `status.resources` from this report into the Batch document, and copies `requests` and `usage` into the list entry.

## 5. Cluster object

### 5.1 Placement and launch

Batch uses the launch path from #1249, unchanged:

```java
LaunchAssignment assignment = LaunchRouter.fromEnv().assign(owner, SessionType.BATCH);
SessionJobBuilder.fromPath(launchBatchYaml, assignment) /* ... */ .buildLaunch();
new SessionLauncher(client).launch(launch, List.of());
```

`SessionJobBuilder` stamps `kueue.x-k8s.io/queue-name=<user>-<community>-lq`, the batch priority class, and `suspend: true`. `SessionLauncher` runs `LocalQueues.ensure`, creates the Job, and deletes it if the launch fails. Batch has no Service and no Ingress, so there are no owned resources.

The Job is named `batch-{id}` and lives in the assignment's namespace, the workload namespace where headless Jobs run. Skaha does not patch the Kueue Workload and does not watch `maxfail`. Kueue clears `suspend` when it admits the Job.

### 5.2 Job

The Job comes from a new template, `helm/skaha-config/launch-batch.yaml`. It starts from `launch-headless.yaml` and keeps everything a headless pod has: the `/arc` and `/etc/passwd` mounts, the init containers, the pod and container `securityContext`, node affinity, flexible spread, the registry pull secret, ephemeral-storage requests and limits, `automountServiceAccountToken: false`, and `enableServiceLinks: false`. `SessionJobBuilder.withHeadlessCommand` fills the image, command, args, and caller `env`. Every index has the same `hostname`; that is harmless because batch has no Service.

| Field | Value |
|---|---|
| `metadata.labels` | the standard session labels from `SessionLabels.canonical` (`canfar.net/id`, `username`, `name` cut to 63 characters, `community`, `project`, `app.kubernetes.io/managed-by`, `app.kubernetes.io/part-of`) plus `canfar.net/kind=batch` |
| `metadata.labels.kueue.x-k8s.io/queue-name` | `assignment.localQueue()` |
| `metadata.labels.kueue.x-k8s.io/priority-class` | the batch priority class from `LaunchRouter` (section 8) |
| `metadata.annotations.kueue.x-k8s.io/job-min-parallelism` | `"1"` when `replicas` is greater than 1 |
| `spec.suspend` | true |
| `spec.completionMode` | `Indexed` |
| `spec.completions` | `replicas` |
| `spec.parallelism` | `replicas` |
| `spec.backoffLimitPerIndex` | 0 |
| `spec.ttlSecondsAfterFinished` | 86400 (24 hours), fixed in the template |
| `spec.activeDeadlineSeconds` | 1209600 |
| `spec.podFailurePolicy` | one `Ignore` rule for condition `DisruptionTarget` |
| `spec.template.spec.restartPolicy` | `Never` |
| `spec.maxFailedIndexes` | section 3.6. Omitted when `maxfail` is absent or 1 |
| `metadata.annotations.canfar.net/maxfail` | the request's `maxfail`, when sent. The Batch document reads `spec.maxfail` from it, because `maxFailedIndexes` is rounded |

`backoffLimit` is not written. When `backoffLimitPerIndex` is set, Kubernetes defaults it to 2147483647, so the Job-wide limit never stops the Job before `maxFailedIndexes` does.

`restartPolicy` is `Never`. An empty policy defaults to `OnFailure`, and that value rejects `backoffLimitPerIndex`. The Job ends 14 days after it starts. After the Job finishes, the TTL keeps the Job and its pods for 24 hours; exit codes, logs, and the Batch document stay available for that long. The user docs say to collect results within that time.

Skaha writes the same standard labels on the pod template. It leaves the Kueue labels off the pod template.

The pod name is `batch-{id}-{index}-{five}`. With an 8-character id and an index up to 255, the name fits the 63-character limit.

Session list and session delete select this Job by `canfar.net/id`. The interactive list excludes it with `canfar.net/kind!=batch` in `SessionLabels.forUserSessions` and in the in-memory filter of `SessionJobCache.userJobs`. `SessionType` accepts `batch`, so `SessionBuilder.fromJob` can read the Job.

### 5.3 Environment

Variables Skaha sets use the `CANFAR_` prefix, so the canfar client inside a pod can read them.

Every session type gets:

| Name | Value |
|---|---|
| `CANFAR_SESSION_KIND` | the session type in lowercase, the same as `type` and `canfar.net/kind`: `batch`, `headless`, `notebook`, `desktop`, `desktop-app`, `carta`, `firefly`, `contributed` |
| `CANFAR_SESSION_ID` | the session id |
| `CANFAR_USERNAME` | the session owner |

The existing `skaha_sessionid`, `skaha_username`, and `skaha_hostname` stay. A later release deprecates them.

A batch pod also gets:

| Name | Value |
|---|---|
| `CANFAR_REPLICA_INDEX` | the completion index, `"0"` through the string of `replicas - 1`. A field reference to `metadata.annotations['batch.kubernetes.io/job-completion-index']`. The same number as `JOB_COMPLETION_INDEX`, which the Job controller injects, and as the `replica` log parameter |
| `CANFAR_REPLICA_COUNT` | the string of `replicas`, the same on every pod |

A batch pod gets no `REPLICA_ID` or `REPLICA_COUNT`. Those names stay the 1-based headless names that the client sets, and they are deprecated together with headless `replicas`.

The `CANFAR_` prefix is reserved. A create whose `env` sets any name starting with `CANFAR_`, in any case, returns 400, for every session type. The canfar client reads its settings without regard to case, so `canfar_registry__secret` is reserved too. As a result, `CANFAR_REGISTRY__USERNAME` and `CANFAR_REGISTRY__SECRET` cannot be passed through `env`. A private launch from inside a pod needs a saved registry credential (`canfar registry login`).

## 6. Client

`batch` is a kind on the existing `canfar create` command. `--maxfail` is the only new flag. The CLI has no `--parallelism` flag.

```text
canfar create batch images.canfar.net/skaha/astroml:latest \
  --name field-reduction --replicas 50 --cpu 1 --memory 4 \
  --maxfail 0.25 \
  -- python /arc/projects/demo/worker.py
```

Omitting `--maxfail` leaves it unset. The Batch document then has `"maxfail": null`.

```text
canfar ps
canfar info k7nq2wpx
canfar logs k7nq2wpx --replica 1
```

`canfar ps` shows the list entry and its `replicas` counts. `canfar info` prints the Batch document. `canfar logs` for a batch requires `--replica`; without it the CLI prints the 400 status and exits with an error.

```python
from canfar.sessions import Session

session = Session()
ids = session.create(
    name="field-reduction",
    image="images.canfar.net/skaha/astroml:latest",
    kind="batch",
    replicas=50,
    cores=1,
    ram=4,
    cmd="python",
    args="/arc/projects/demo/worker.py",
    maxfail=0.25,
)
batch = session.info(ids[0])[0]
log = session.logs(ids[0], replica=1)
```

- `Kind` (`canfar/models/types.py`) adds `"batch"`.
- `maxfail` defaults to `None`, and the client then omits it from the form.
- `Session.create` for `kind="batch"` sends one POST and returns a one-element `list[str]`. It adds no replica variables to the form; Skaha sets them on the pod.
- `session.logs` for a batch requires `replica`. Without it the server returns 400, which `session.logs` reports through its existing `errors` setting: `errors="raise"` raises, and the default returns no log for that id. `canfar logs` exits with an error.
- `session.fetch(kind="batch")` returns the list entries.

`canfar.helpers.distributed.stripe` and `chunk` keep their 1-based `replica` argument. When `replica` is omitted, they read `CANFAR_REPLICA_INDEX` plus 1 if it is set, otherwise `REPLICA_ID`. When `total` is omitted, they read `CANFAR_REPLICA_COUNT` if it is set, otherwise `REPLICA_COUNT`. One helper serves batch and headless.

Headless `replicas` above 1 still injects a 1-based `REPLICA_ID` and `REPLICA_COUNT`.

| Caller | Behavior |
|---|---|
| `kind="batch"` and `replicas` above 1 | One create, one id |
| `kind="headless"` and `replicas` above 1 | `FutureWarning`, which Python shows by default wherever the call sits, then the current one-create-per-replica loop. A later release removes it |
| any other kind and `replicas` above 1 | validation error. Desktop and Firefly already return this error |
| `maxfail` set on a kind other than `batch` | validation error, before the POST |
| `maxfail` outside 0–1 | validation error |
| an `env` name starting with `CANFAR_` | validation error, before the POST |

Workers read `CANFAR_REPLICA_INDEX` and `CANFAR_REPLICA_COUNT`. Docs and the canfar skill describe `replicas` as the batch job count for `kind="batch"`, mark the headless use as deprecated, and say that workers must be safe to run again.

## 7. Portal

The home page places a batch row beside the active-sessions widget. The row reads the list entry. It uses its own layout, separate from the interactive session card.

| Surface | Source |
|---|---|
| Name | `name` (from `canfar.net/name`, which may be cut to 63 characters) |
| Progress | `replicas.succeeded`, `failed`, `active`, `incomplete`, and `total` |
| Resources | `cpuCoresInUse` against `requestedCPUCores`, and `ramInUse` against `requestedRAM` |
| Elapsed, running | `Started ${formatRelativeToNow(startMs, nowMs)}` from `startTime`. While that is absent, the row says `Pending` |
| Elapsed, finished | `completionTime` minus `startTime`, fixed |
| Stop | DELETE `/v1/session/{id}`. The row is removed when the DELETE returns 200 |

The batch detail page reads `GET /v1/session/{id}`. It shows the replica table with `exitCode`, a log action per replica (`?view=logs&replica=`), the `total` / `requests` / `usage` / `efficiency` rows, and a footer from `observedAt`.

`Failed` with reason `FailedIndexes` is "Finished with failures". `MaxFailedIndexesExceeded` is "Stopped". The row does not use the interactive list's Expired chip. The home page does not draw one row per replica.

The portal change is part of this version.

## 8. Chart and cluster

| Item | Value |
|---|---|
| Template | `helm/skaha-config/launch-batch.yaml`, mounted like `launch-headless.yaml`. The 24-hour TTL is fixed in this file; the chart has no batch values |
| Priority | No chart or Skaha change. Batch uses the per-type WorkloadPriorityClass routing from #1249 as it is. Operators set `deployment.skaha.sessions.kueue.batch.priorityClass: low` in their deployment values (keel-deploy for CANFAR); `LaunchRouter` reads it as `SKAHA_QUEUE_BATCH_PRIORITY_CLASS`. Without that entry, batch falls back to `default` (`high` on CANFAR) and could preempt headless jobs |

This version makes no cluster, queue, or LocalQueue changes. Routing, LocalQueues, ClusterQueues, and their RBAC are as #1249 leaves them.

The workload namespace already has a Role for `batch/jobs`, and #1249 adds LocalQueue `create`/`get`/`patch` and ClusterQueue `get`. This version adds no JobSet rule, no ClusterRole, and no CRD.

Prerequisites:

- Kubernetes 1.29 or later. Per-index backoff and `maxFailedIndexes` are on by default from 1.29, and the `batch.kubernetes.io/job-completion-index` pod label exists from 1.28. Check keel-prod's version before implementing.
- The Kueue `batch/job` integration, which headless already uses.
- On prod, ClusterQueue `get` for the Skaha ServiceAccount (CADC-15958). Without it, every launch returns 503 after #1249, batch included.

## 9. Lifecycle

| Event | Result |
|---|---|
| Bad `replicas`, `maxfail`, or `env` | 400, no Job |
| LocalQueue unusable or Job create failed | 503, no Job |
| Create accepted | one id, one suspended Job, `phase` `Pending` |
| Admitted | `phase` `Running`. Active pods equal the admitted parallelism. The other indexes are `Pending` |
| A pod finishes | the next `Pending` index starts, still at the admitted parallelism |
| Preempted by Kueue | the Job is suspended, its running indexes are lost, and `phase` is `Pending` until Kueue admits it again |
| User deletes | background delete. GET returns 404 |
| Failed indexes reach K (section 3.6) | `Failed`, reason `MaxFailedIndexesExceeded` |
| Every index finishes and some failed | `Failed`, reason `FailedIndexes` |
| Every index succeeds | `Completed`, reason `AllIndexesSucceeded` |
| 14 days from the Job start | `Failed`, reason `DeadlineExceeded` |
| The TTL after the Job finishes | the Job and its pods are removed. GET returns 404 |

## 10. Implementation

| Module | Change |
|---|---|
| `SessionType` | add `BATCH("batch")` with no Service and no Ingress. Not counted toward the interactive cap or the one-per-type rule |
| `PostAction` | accept `type=batch`, `replicas`, and `maxfail`. Reject `CANFAR_*` env for every type. Build through `LaunchRouter`, `SessionJobBuilder`, and `SessionLauncher`. Return one id |
| `SessionJobBuilder` | render `launch-batch.yaml`. Write the Indexed fields, `maxFailedIndexes`, the min-parallelism annotation, and the environment in section 5.3. Write `CANFAR_SESSION_*` for every type |
| `SessionLabels`, `SessionJobCache` | exclude `canfar.net/kind=batch` from the interactive list |
| `SessionBuilder`, `GetAction` | the list entry in section 3.3 and the Batch document in section 3.4, both with the Metrics copy |
| Log route | section 3.5 |
| `metrics/.../providers/session.py` | read `completions`, `parallelism`, and `succeeded`. Compute held `requests` and, for `canfar.net/kind=batch`, `total` and rows that stay present |
| `metrics/.../services/models.py`, `schemas/metrics.py`, `api/v1alpha1/routes.py` | `SessionObservation.totals` and `ResourceMetrics.total` |
| Chart | `launch-batch.yaml` only |
| canfar | `Kind`, `CreateRequest`, `canfar create`, `canfar logs`, `canfar ps`, `stripe`/`chunk`, and the headless `FutureWarning` (section 6) |
| science-portal | section 7 |

Tests cover:

- Every row of the `maxfail` table.
- The Job for `replicas` 50: `parallelism` 50, `job-min-parallelism` `"1"`, `Indexed`, `CANFAR_REPLICA_COUNT` `"50"`, a `CANFAR_REPLICA_INDEX` field reference, no `REPLICA_ID`, no `backoffLimit`, and the batch queue and priority labels.
- The `replicas` 1 Job without the min-parallelism annotation.
- `CANFAR_SESSION_KIND`, `CANFAR_SESSION_ID`, and `CANFAR_USERNAME` on every session type, and a 400 for a caller `CANFAR_*` env.
- A Batch document whose `status.replicas` length equals `spec.replicas`, with a `Pending` row for an index whose pod is not scheduled.
- A list entry with `replicas` counts and held `requested*` values, and a `view=interactive` list without batches (live and cached).
- A batch log request without `replica` returning 400.
- Metrics batch reports at each stage in section 4, including 3 indexes left, and a headless report that is unchanged.
- A Helm render of `launch-batch.yaml` with the Indexed fields and the 24-hour TTL.
- `stripe` and `chunk` reading `CANFAR_REPLICA_INDEX` (0-based) and falling back to `REPLICA_ID` (1-based).

## 11. Open before implementation

- Check whether Kueue 0.17's elastic jobs (workload slices) can raise a batch's admitted parallelism after admission. If they can, decide whether to use them.
- Confirm that keel-prod runs Kubernetes 1.29 or later.

## 12. Out of scope

- A public `parallelism` or `completions` parameter.
- `NonIndexed` completion mode.
- A JobSet, a second replicated job, `dependsOn`, a coordinator, or startup order.
- Raising the admitted parallelism when more quota appears later, unless Kueue preempts the Job (see section 11).
- Retrying an index after its container exits.
- Putting log text in the Batch document.
- A watch loop that enforces `maxfail`.
- Reporting `FailedIndexes` as success.
- A `Terminating` phase.
- Batch history after the Job TTL.
- A cumulative `consumed` resource figure in Metrics.
- Renew, an idempotency token, or a per-user batch cap.
- Removing headless `replicas` in this version. This version warns.
- Renaming the `skaha_*` pod variables. This version adds the `CANFAR_*` names beside them.
- Installing a CRD or a Kueue integration from this chart.
