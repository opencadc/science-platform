# Skaha session labels

Labels Skaha attaches at session launch. `SessionLabels` and `SessionLabelPlan`
own this set. Metrics may use the same labels for PromQL attribution. That
contract is under [Metrics integration boundary](#metrics-integration-boundary).

## All labels

| Label | Values | When set | Notes |
|-------|--------|----------|-------|
| `canfar.net/id` | session id | Always | Required |
| `canfar.net/username` | user id | Always | Required |
| `canfar.net/name` | session name | Always | Required; truncated to ≤63 chars if needed for Kubernetes label syntax |
| `canfar.net/kind` | session type (`notebook`, `carta`, `desktop`, `headless`, `batch`, `desktop-app`, `firefly`, …) | Always | Required. `view=interactive` and the interactive session cap leave out `headless` and `batch` |
| `canfar.net/job` | Job name | Always | Required. A batch Job is `batch-<id>` |
| `canfar.net/flavor` | `fixed` \| `flexible` | Always | `fixed` when memory request equals memory limit; otherwise `flexible` |
| `canfar.net/accelerator` | `gpu` \| `none` | Always | `gpu` when GPU count > 0; otherwise `none` |
| `canfar.net/community` | Community name | Always | Helm communities map key of the entry with `default: true` |
| `canfar.net/project` | scope string | Always | Defaults to `default` when blank/omitted |
| `canfar.net/app-id` | desktop app id | Only when non-blank (desktop-app launches) | Omitted entirely when blank |
| `app.kubernetes.io/managed-by` | `skaha` | Always | Constant |
| `app.kubernetes.io/part-of` | `canfar` | Always | Constant |
| `app.kubernetes.io/version` | Skaha version string | Only when `SKAHA_VERSION` is set | Validated as a Kubernetes label value |
| `kueue.x-k8s.io/queue-name` | the owner's LocalQueue name | Always | Job only; not copied to Service/Ingress |
| `kueue.x-k8s.io/priority-class` | WorkloadPriorityClass name | When `SKAHA_QUEUE_<TYPE>_PRIORITY_CLASS` or `SKAHA_QUEUE_DEFAULT_PRIORITY_CLASS` is set | Job only; not copied to Service/Ingress |

## Applied to

| Object | Labels |
|--------|--------|
| Job metadata | All session labels above that are present for the launch, plus Kueue labels; the Job is created suspended |
| Job pod template | Same session labels as Job metadata, without Kueue labels |
| Service metadata | Present `canfar.net/*` and `app.kubernetes.io/*` session keys from the plan (not Kueue) |
| Service selector | Only `canfar.net/id` and `canfar.net/kind` |
| Ingress metadata | Same as Service metadata |

A batch has no Service and no Ingress. The Job controller adds
`batch.kubernetes.io/job-completion-index` to each batch Pod; Skaha reads it to
find a replica's Pod for its row in the Batch document and for
`view=logs&replica=<n>`.

## Batch annotations

Skaha writes these annotations on a batch Job, not labels:

| Annotation | Value | When set |
|------------|-------|----------|
| `kueue.x-k8s.io/job-min-parallelism` | `"1"` | `replicas` above 1. Kueue may then admit fewer Pods than `parallelism` |
| `canfar.net/maxfail` | the request's `maxfail`, e.g. `0.25` | `maxfail` sent. The Batch document reads `spec.maxfail` back from it, because `maxFailedIndexes` is rounded |

## Container environment

Skaha sets these variables on every session app container, after the request's
`env`, from the same values as the labels. A request `env` name that starts with
`CANFAR_` (any case) is rejected with 400.

| Variable | Value | Sessions |
|----------|-------|----------|
| `CANFAR_SESSION_KIND` | `canfar.net/kind` | All |
| `CANFAR_SESSION_ID` | `canfar.net/id` | All |
| `CANFAR_USERNAME` | `canfar.net/username` | All |
| `CANFAR_REPLICA_INDEX` | field reference to the Pod's `batch.kubernetes.io/job-completion-index` annotation, `0` to `replicas - 1` | Batch |
| `CANFAR_REPLICA_COUNT` | `replicas` | Batch |

## Typical full set (common session, no desktop app, version set)

```text
canfar.net/community=cadc
canfar.net/project=default
canfar.net/id=<session-id>
canfar.net/username=<user>
canfar.net/name=<session-name>
canfar.net/kind=<type>
canfar.net/flavor=fixed|flexible
canfar.net/job=<job-name>
canfar.net/accelerator=none|gpu
app.kubernetes.io/managed-by=skaha
app.kubernetes.io/part-of=canfar
app.kubernetes.io/version=<SKAHA_VERSION>
kueue.x-k8s.io/queue-name=<user>-cadc-lq   # Job only
```

## Typical batch set

A batch Job named `batch-<id>`, 50 replicas, `maxfail=0.25`:

```text
canfar.net/community=cadc
canfar.net/project=default
canfar.net/id=<session-id>
canfar.net/username=<user>
canfar.net/name=<session-name>
canfar.net/kind=batch
canfar.net/flavor=fixed|flexible
canfar.net/job=batch-<session-id>
canfar.net/accelerator=none|gpu
app.kubernetes.io/managed-by=skaha
app.kubernetes.io/part-of=canfar
kueue.x-k8s.io/queue-name=<user>-cadc-lq   # Job only

# Job annotations
kueue.x-k8s.io/job-min-parallelism=1
canfar.net/maxfail=0.25

# Added by the Job controller to each Pod (label and annotation)
batch.kubernetes.io/job-completion-index=<0..49>
```

## Edge cases

| Case | Result |
|------|--------|
| Desktop app with `SOFTWARE_APPID` | Adds `canfar.net/app-id` |
| Desktop app / launch without app id | No `canfar.net/app-id` key |
| `SKAHA_VERSION` unset or blank | No `app.kubernetes.io/version` key |
| No priority class configured | No `kueue.x-k8s.io/priority-class` key |
| GPU count = 0 | `canfar.net/accelerator=none` |
| GPU count > 0 | `canfar.net/accelerator=gpu` |
| Memory request == limit | `canfar.net/flavor=fixed` |
| Memory request ≠ limit (or unpaired) | `canfar.net/flavor=flexible` |
| User or Community not a DNS-1123 label, or `<user>-<community>-lq` over 63 characters | Launch rejected |
| Project omitted | `canfar.net/project=default` |
| Batch with one replica | No `kueue.x-k8s.io/job-min-parallelism` annotation |
| Batch without `maxfail` | No `canfar.net/maxfail` annotation |

## Metrics integration boundary

Metrics uses distinct queue and optional efficiency contracts. The platform-owned
labels are:

| Object | Core Kueue labels | PromQL-only attribution labels |
|--------|-------------------|--------------------------------|
| ClusterQueue | `canfar.net/community` only | None |
| LocalQueue | `canfar.net/username`, `canfar.net/community`; also `app.kubernetes.io/managed-by=skaha` and `app.kubernetes.io/part-of=canfar` on Skaha-created queues | None |
| Submitting Job/workload metadata | `kueue.x-k8s.io/queue-name` | `canfar.net/username`, `canfar.net/community` |
| Job Pod template and resulting Pod | None | `canfar.net/username`, `canfar.net/community` |

PromQL attribution labels on the Job and Pod are required only when the PromQL
provider is enabled. Core Kueue reports do not need them. The Job's
`kueue.x-k8s.io/queue-name` selects the user's LocalQueue. It does not decide
Community membership. The ClusterQueue's `canfar.net/community` label does.

Before Skaha creates a session Job, it reads the ClusterQueue named by
`clusterQueue` on the communities entry with `default: true`. That object must
carry `canfar.net/community` equal to the entry's map key. Skaha then creates or
reuses the owner's LocalQueue with these labels:

| Label | Value |
|-------|-------|
| `canfar.net/username` | session owner |
| `canfar.net/community` | that map key |
| `app.kubernetes.io/managed-by` | `skaha` (set on create only) |
| `app.kubernetes.io/part-of` | `canfar` (set on create only) |

Skaha reuses an existing LocalQueue when both identity labels match and
`spec.clusterQueue` matches the assignment. It patches one missing identity
label only when `managed-by=skaha` and the other identity label already matches.
If both identity labels are missing, or the queue is otherwise unsafe, the
launch returns 503. Skaha never retargets, deletes, or adopts a LocalQueue.

## Legacy keys (historical)

Older Skaha releases used `canfar-net-*` and `opencadc.org/canfar-job-fixed|flexible`
labels. Current launches write only the `canfar.net/*` keys above (including
`canfar.net/flavor`).
