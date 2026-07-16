# Skaha session labels

Labels Skaha attaches at session launch. Owned by `SessionLabels` / `SessionLabelPlan`.

## All labels

| Label | Values | When set | Notes |
|-------|--------|----------|-------|
| `canfar.net/id` | session id | Always | Required |
| `canfar.net/username` | user id | Always | Required |
| `canfar.net/name` | session name | Always | Required; truncated to ≤63 chars if needed for Kubernetes label syntax |
| `canfar.net/kind` | session type (`notebook`, `carta`, `desktop`, `headless`, `desktop-app`, `firefly`, …) | Always | Required |
| `canfar.net/job` | Job name | Always | Required |
| `canfar.net/flavor` | `fixed` \| `flexible` | Always | `fixed` when memory request equals memory limit; otherwise `flexible` |
| `canfar.net/accelerator` | `gpu` \| `none` | Always | `gpu` when GPU count > 0; otherwise `none` (also defaulted if somehow omitted) |
| `canfar.net/community` | scope string | Always | Defaults to `default` when blank/omitted |
| `canfar.net/project` | scope string | Always | Defaults to `default` when blank/omitted |
| `canfar.net/app-id` | desktop app id | Only when non-blank (desktop-app launches) | Omitted entirely when blank |
| `app.kubernetes.io/managed-by` | `skaha` | Always | Constant |
| `app.kubernetes.io/part-of` | `canfar` | Always | Constant |
| `app.kubernetes.io/version` | Skaha version string | Only when `SKAHA_VERSION` is set | Validated as a Kubernetes label value |
| `kueue.x-k8s.io/queue-name` | local queue name | Only when a queue is configured for the launch | Job only; not copied to Service/Ingress |
| `kueue.x-k8s.io/priority-class` | priority class name | Only when a queue is configured for the launch | Job only; not copied to Service/Ingress |

## Applied to

| Object | Labels |
|--------|--------|
| Job metadata | All session labels above that are present for the launch, plus Kueue labels when queued |
| Job pod template | Same session labels as Job metadata (not Kueue-specific beyond what was merged onto the Job) |
| Service metadata | Present `canfar.net/*` and `app.kubernetes.io/*` session keys from the plan (not Kueue) |
| Service selector | Only `canfar.net/id` and `canfar.net/kind` |
| Ingress metadata | Same as Service metadata |

## Typical full set (common session, no desktop app, version set, no queue)

```text
canfar.net/community=default
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
```

## Edge cases

| Case | Result |
|------|--------|
| Desktop app with `SOFTWARE_APPID` | Adds `canfar.net/app-id` |
| Desktop app / launch without app id | No `canfar.net/app-id` key |
| `SKAHA_VERSION` unset or blank | No `app.kubernetes.io/version` key |
| Queue enabled | Adds both Kueue labels on the Job only |
| Queue disabled | No Kueue labels |
| GPU count = 0 | `canfar.net/accelerator=none` |
| GPU count > 0 | `canfar.net/accelerator=gpu` |
| Memory request == limit | `canfar.net/flavor=fixed` |
| Memory request ≠ limit (or unpaired) | `canfar.net/flavor=flexible` |
| Community / project omitted | Both set to `default` |
