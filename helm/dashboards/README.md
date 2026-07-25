# CANFAR Science Platform Grafana dashboards

Eight dashboards covering platform health, session usage, resource efficiency, queue
throughput, cluster capacity, storage, and the CANFAR control plane itself.

Plain Grafana JSON. Edit here, or edit in the Grafana UI and export back over the file.
The Helm chart turns each one into a ConfigMap that the Grafana sidecar collects.

| Dashboard | uid | Question it answers |
|---|---|---|
| **Platform Overview** | `canfar-overview` | Is the platform healthy, and what is the shape of demand right now? |
| **Sessions & Users** | `canfar-sessions` | Who is on the platform, what did they launch, how long did it run? |
| **Efficiency & Waste** | `canfar-efficiency` | Where are we holding capacity nobody is using, and who do we talk to? |
| **Session Drilldown** | `canfar-drilldown` | What exactly did this one session do? |
| **Queue & Scheduling** | `canfar-queue` | Is Kueue admitting work, and is the fair-share policy sane? |
| **Cluster Capacity** | `canfar-capacity` | Do we have room, and where is capacity stranded? |
| **Storage** | `canfar-storage` | Are volumes filling up, and is the disk keeping up? |
| **Platform Services** | `canfar-services` | Is the Skaha API and its supporting deployment healthy? |

109 panels, 183 queries.

## Deploying

Off by default:

```yaml
grafanaDashboards:
  enabled: true
  folder: CANFAR Science Platform
```

Enable on **one** release only. Two releases pointing at the same Grafana would render
ConfigMaps carrying identical dashboard uids, and the sidecar would import whichever it
wrote last. The `$namespace` picker already spans every workload namespace, so a single
install covers them all.

## Prerequisite: kube-state-metrics must expose the CANFAR labels

The session dashboards attribute usage by joining container metrics to `kube_pod_labels`.
kube-state-metrics drops object labels by default since v2.0, so the CANFAR keys must be
named in `--metric-labels-allowlist`. Today's contract needs at least:

```
pods=[canfar-net-userid,canfar-net-sessionType]
jobs=[opencadc.org/canfar-job-fixed,opencadc.org/canfar-job-flexible]
```

Both are already allowlisted on the current cluster. Verify with a query that should
return a non-zero count:

```promql
count(kube_pod_labels{label_canfar_net_userid!=""})
```

The four dashboards that touch no CANFAR labels — queue, capacity, storage, services —
do not depend on this, which makes them a useful control: if those have data and the
session dashboards do not, the allowlist is the cause.

Other exporters required: **kube-state-metrics** (`kube_*`), **cAdvisor/kubelet**
(`container_*`, `kubelet_volume_stats_*`), **node-exporter** (`node_*`), and **Kueue**
metrics scraped into Prometheus (`kueue_*`). Only the Queue dashboard needs Kueue.

## Label contract

These dashboards read the **current** `canfar-net-*` pod labels. kube-state-metrics
rewrites label keys before exposing them — characters outside `[a-zA-Z0-9_]` become `_`,
camelCase becomes snake_case, the result is lowercased, and `label_` is prefixed:

| Dimension | Kubernetes label | Prometheus label |
|---|---|---|
| User | `canfar-net-userid` | `label_canfar_net_userid` |
| Session kind | `canfar-net-sessionType` | `label_canfar_net_session_type` |
| Session name | `canfar-net-sessionName` | `label_canfar_net_session_name` |
| Session ID | `canfar-net-sessionID` | `label_canfar_net_session_id` |
| Desktop app | `canfar-net-appID` | `label_canfar_net_app_id` |
| Job sizing | `opencadc.org/canfar-job-{fixed,flexible}` | `label_opencadc_org_canfar_job_{fixed,flexible}` |

[opencadc/science-platform#1112](https://github.com/opencadc/science-platform/pull/1112)
replaces these with `canfar.net/*` keys and adds project, community, flavor and
accelerator attribution. Those labels are not on the cluster yet, so no panel here uses
them — a follow-up ticket covers the transition once #1112 is live.

## Conventions worth preserving when editing

- **Session queries key off `canfar-net-userid`, never `canfar-net-sessionID`.** Only
  labels in the kube-state-metrics allowlist exist as series; the user label is
  allowlisted and the session ID is not. Gating on the ID silently empties every session
  panel. Drilldown groups by `pod` for the same reason — the pod name is always present
  and already encodes kind, user, and session ID.
- **The datasource variable ships with a seeded `current`.** A datasource variable with
  an empty `current` does not reliably resolve on a *provisioned* dashboard the way it
  does on one saved from the UI; `${datasource}` interpolates to nothing and every panel
  silently returns no data.
- **Rates use `$__rate_interval`**, never `$__interval` or a hard-coded `[5m]`.
- **Efficiency counts running pods only** — Pending and Completed pods hold no working
  set and would otherwise drag every efficiency number toward zero.
- **Windowed totals derive from `$__range_s`**, so "memory wasted" means what the time
  picker says.
- **Every panel carries a unit, a threshold, and a description** saying what to conclude
  from it rather than restating the title.

## Known gaps

- **No `cluster` label.** Multi-cluster selectors are deliberately absent: no `cluster`
  label exists in Prometheus today, and a join on an absent label fails silently to an
  empty panel. Scoping is by `$namespace`.
- **No rollup / recording rules.** The
  [CANFAR Metrics Collection](https://herzberg.atlassian.net/wiki/spaces/C/pages/2335047722/CANFAR+Metrics+Collection)
  page specifies a `canfar:id:*` to `canfar:user:*` to `canfar:project:*` to
  `canfar:community:*` chain with tiered retention. These dashboards query raw series.
  The retention tiers need Mimir rather than recording rules, so it is separate work.
- **Percentile and waste panels are expensive** — they use
  `max_over_time(...[$__range:5m])` subqueries. Fine at 24h; be careful over a month.
- **Metrics nothing emits yet** are not charted: login latency, token issuance success
  rate, group membership query latency, authentication uptime, storage API response time
  and throughput. A panel that can never populate is indistinguishable from a broken one.
- **Session counts come from `kube_job_*`.** Skaha launches every session as a Job, which
  makes lifecycle and wall-clock duration directly readable, but bounds those counts by
  the Job TTL controller's retention.
