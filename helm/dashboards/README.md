# CANFAR Science Platform Grafana dashboards

Ten dashboards covering platform health, session usage, resource efficiency, queue
throughput, cluster capacity, storage, and the CANFAR control plane itself.

Plain Grafana JSON. Edit here, or edit in the Grafana UI and export back over the file.
The Helm chart turns each one into a ConfigMap that the Grafana sidecar collects.

| Dashboard | uid | Question it answers |
|---|---|---|
| **Platform Overview** | `canfar-overview` | What is the shape of demand right now? |
| **Platform Health** | `canfar-platform-health` | Is the platform healthy, and is any node in an OOM loop? |
| **Communities & Projects** | `canfar-communities` | Which community and project is consuming what? |
| **Sessions & Users** | `canfar-sessions` | Who is on the platform, what did they launch, how long did it run? |
| **Efficiency & Waste** | `canfar-efficiency` | Where are we holding capacity nobody is using, and who do we talk to? |
| **Session Drilldown** | `canfar-drilldown` | What exactly did this one session do? |
| **Queue & Scheduling** | `canfar-queue` | Is Kueue admitting work, and is the fair-share policy sane? |
| **Cluster Capacity** | `canfar-capacity` | Do we have room, and where is capacity stranded? |
| **Storage** | `canfar-storage` | Are volumes filling up, and is the disk keeping up? |
| **Platform Services** | `canfar-services` | Is the Skaha API and its supporting deployment healthy? |

Platform Services excludes workload namespaces (`namespace!~".*workload.*"`) on every
query, not just in its namespace picker. The picker only ever offered namespaces that
own Deployments, so workload namespaces never appeared there — but its panels read
`kube_pod_*` and `container_*`, and the picker defaults to All, which resolves to `.*`
and swept user sessions in. That is the mirror of the session dashboards, whose
namespace picker filters *to* `.*workload.*`.

137 panels across ten dashboards.

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
pods=[canfar.net/id,canfar.net/username,canfar.net/name,canfar.net/kind,canfar.net/job,canfar.net/flavor,canfar.net/accelerator,canfar.net/community,canfar.net/project,canfar.net/app-id]
jobs=[<same list>]
```

Pods and jobs are separate allowlists in kube-state-metrics; the Sessions lifecycle and
duration panels read Job labels, so both must be populated. Both are, as of this change.
Verify with two queries that should each return a non-zero count:

```promql
count(kube_pod_labels{label_canfar_net_username!=""})
count(kube_job_labels{label_canfar_net_username!=""})
```

The four dashboards that touch no CANFAR labels — queue, capacity, storage, services —
do not depend on this, which makes them a useful control: if those have data and the
session dashboards do not, the allowlist is the cause.

Other exporters required: **kube-state-metrics** (`kube_*`), **cAdvisor/kubelet**
(`container_*`, `kubelet_volume_stats_*`), **node-exporter** (`node_*`), and **Kueue**
metrics scraped into Prometheus (`kueue_*`). Only the Queue dashboard needs Kueue.

## Label contract

These dashboards read the `canfar.net/*` session labels defined in
[`skaha/docs/labels.md`](../../skaha/docs/labels.md). kube-state-metrics rewrites label
keys before exposing them — characters outside `[a-zA-Z0-9_]` become `_`, camelCase
becomes snake_case, the result is lowercased, and `label_` is prefixed:

| Dimension | Kubernetes label | Prometheus label |
|---|---|---|
| Session ID | `canfar.net/id` | `label_canfar_net_id` |
| User | `canfar.net/username` | `label_canfar_net_username` |
| Session name | `canfar.net/name` | `label_canfar_net_name` |
| Session kind | `canfar.net/kind` | `label_canfar_net_kind` |
| Job | `canfar.net/job` | `label_canfar_net_job` |
| Flavor | `canfar.net/flavor` | `label_canfar_net_flavor` |
| Accelerator | `canfar.net/accelerator` | `label_canfar_net_accelerator` |
| Community | `canfar.net/community` | `label_canfar_net_community` |
| Project | `canfar.net/project` | `label_canfar_net_project` |
| Desktop app | `canfar.net/app-id` | `label_canfar_net_app_id` |

Verified live on keel-prod: all nine keys present on both `kube_pod_labels` and
`kube_job_labels`, and every pre-realignment key at zero series.

Three points from [CADC-15910](https://herzberg.atlassian.net/browse/CADC-15910) that
shape what is here:

- **`project` is the Kueue LocalQueue dimension and `community` the ClusterQueue /
  fair-share dimension.** Communities & Projects groups by both and shows the Kueue
  queue labels beside them for that reason.
- **Both default to `default`, never missing.** Every session on the cluster today
  carries `community=default` and `project=default`, so those panels currently show a
  single bucket while the real segmentation sits in Kueue's own labels (`cadc`, `ska`).
  A large `default` bucket means attribution is not being set at launch, not that the
  dimension is unused — the panel descriptions say so.
- **GPU count is not a label.** Accelerator is only `gpu` or `none`; every GPU *count*
  on these dashboards comes from the `nvidia.com/gpu` resource request.

`canfar.net/cluster` and `canfar.net/site` are explicitly out of scope, which matches
the decision not to use a `cluster` label in joins.

Flavor changed shape, not just name. It used to be two boolean *Job* labels
(`opencadc.org/canfar-job-fixed|flexible`); it is now one pod-level value, so the
former Job-scoped sizing panel is a proper `fixed`/`flexible` breakdown that responds
to the session filters.

## Conventions worth preserving when editing

- **Session queries gate on `canfar.net/username`, not `canfar.net/id`.** Only labels
  in the kube-state-metrics allowlist exist as series. Both are allowlisted now, but the
  username gate is kept because it is the one every deployment allowlists first, and an
  earlier revision that gated on the session ID silently emptied every session panel.
  Drilldown groups by `pod` for the same reason — the pod name is always present and
  already encodes kind, user, and session ID.
- **The datasource variable ships with a seeded `current`**, set to Mimir (`uid: mimir`).
  A datasource variable with an empty `current` does not reliably resolve on a
  *provisioned* dashboard the way it does on one saved from the UI; `${datasource}`
  interpolates to nothing and every panel silently returns no data. Prometheus
  (`uid: prometheus`) stays one click away in the picker as a fallback, since both are
  prometheus-type datasources.
- **Rates use `$__rate_interval`**, never `$__interval` or a hard-coded `[5m]`.
- **Efficiency counts running pods only** — Pending and Completed pods hold no working
  set and would otherwise drag every efficiency number toward zero.
- **Windowed totals derive from `$__range_s`**, so "memory wasted" means what the time
  picker says.
- **Every panel carries a unit, a threshold, and a description** saying what to conclude
  from it rather than restating the title.

## Why Overview and Platform Health are separate

Overview mixed two audiences: people asking who is using what, and operators asking
whether the platform is holding up. Platform Health carries the operational half —
load with trends, reliability, and node-level OOM kills — and deliberately carries no
workload breakdown at all. Session counts by kind, by user and by project stay on
Overview and Sessions & Users; Platform Health answers "is the platform holding up",
not "what is running".

OOM appears in exactly two places by design. Platform Health owns the operational
view (which node, how often). Session Drilldown keeps terminations because it is
scoped to one chosen session and answers a different question: did *this* session get
killed. That is context, not duplication.

Node-level kills come from `node_vmstat_oom_kill`, the kernel counter, joined to
`node_uname_info` for readable names since the metric only carries `instance`. They
are drawn as a single status-history grid: one cell per node per hour.

Buckets are pinned to an hour by setting the target's `interval` to `1h`, so a cell
means the same thing regardless of panel width or selected range. Colour runs
green-yellow-red by value on a scale pinned from 0 to 10; hover for the exact count.

The pin matters. A continuous scale maps the min and max of whatever data is on screen,
so a single 28-kill hour would rescale the panel and wash a 4-kill hour out to green —
and the meaning of a colour would shift between refreshes. Fixed bounds keep a colour
comparable across time and across nodes, at the cost of everything above 10 sharing the
same red.

Platform Health opens at 24h while the other eight open at 6h. An hourly grid needs
enough columns to show a pattern, and six of them cannot.

The panel is read for shape rather than magnitude. Over one recent six-hour window
`c0118` showed a single red hour of 28 kills while `c0113` showed three separate yellow
hours of 4 — one blew up once, the other is looping, and only the second wants draining.
A ranked list puts those two adjacent and says nothing about the difference.

Rows are shortened from the FQDN to the node name (`c0107`) with `label_replace`, since
`keel-prod-` and `.arbutus.uvic.ca` repeat on every row. The prefix letter carries the
role: `c` compute, `g` GPU, `s` services, `k` control plane. Kubernetes reports no role
labels on these nodes.

Three approaches were tried and dropped, recorded so they are not retried. Per-role
grids with a vivid green zero state: ~95% of the surface shouted and buried the signal.
A table built on the `timeSeriesTable` transformation with a Trend sparkline: that
transformation emits no separate numeric column, so it rendered as node names beside
grey squiggles with no counts. A sortable counts table beneath the grid: accurate, but
redundant once the grid became hourly and legible.
`container_oom_events_total` looks tempting because it carries `node` and `pod`
directly, but its counters were flat at zero over 24h on this cluster while
node_vmstat showed 113 kills — so it is not a trustworthy source here.

## Storage: most volumes are not measurable

The Volume inventory lists every PersistentVolumeClaim from
`kube_persistentvolumeclaim_info`, not only the ones with usage metrics, because on
this cluster those are very different sets. Across the CANFAR namespaces 1 of 11
claims reports `kubelet_volume_stats_*` — 16% of provisioned capacity. The kubelet
only emits volume stats for volumes it can stat through the CSI driver, and most of
these are statically-provisioned CephFS.

Capacity therefore comes from the claim (always present) and Used and Fill from kubelet
(often absent), with unmeasurable volumes showing "not measurable" rather than being
dropped. The stats row separates *Volumes* from *Reporting usage* for the same reason:
an earlier version showed only measurable volumes, so "Volumes tracked: 15" read as a
total when it was less than half of them, and Cavern — where the user data actually
lives — was silently absent.

No `ceph_*` metrics are scraped, so there is currently no alternative source for
CephFS usage.

## Known gaps

- **No `cluster` label.** Multi-cluster selectors are deliberately absent: no `cluster`
  label exists in Prometheus today, and a join on an absent label fails silently to an
  empty panel. Scoping is by `$namespace`.
- **No rollup / recording rules.** The
  [CANFAR Metrics Collection](https://herzberg.atlassian.net/wiki/spaces/C/pages/2335047722/CANFAR+Metrics+Collection)
  page specifies a `canfar:id:*` to `canfar:user:*` to `canfar:project:*` to
  `canfar:community:*` chain with tiered retention. These dashboards query raw series.
  Mimir is deployed and is the default datasource, so the tiered-retention half of that
  plan is now within reach; the recording rules themselves remain unwritten.
- **Percentile and waste panels are expensive** — they use
  `max_over_time(...[$__range:5m])` subqueries. Fine at 24h; be careful over a month.
- **Metrics nothing emits yet** are not charted: login latency, token issuance success
  rate, group membership query latency, authentication uptime, storage API response time
  and throughput. A panel that can never populate is indistinguishable from a broken one.
- **Session counts come from `kube_job_*`.** Skaha launches every session as a Job, which
  makes lifecycle and wall-clock duration directly readable, but bounds those counts by
  the Job TTL controller's retention.
