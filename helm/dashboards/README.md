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

137 panels across ten dashboards.

## Deploying

Off by default:

```yaml
grafanaDashboards:
  enabled: true
  folder: CANFAR Science Platform
  datasourceUid: mimir
  datasourceName: Mimir
```

Enable on **one** release only. Two releases pointing at the same Grafana would render
ConfigMaps carrying identical dashboard uids, and the sidecar would import whichever it
wrote last.

`datasourceUid` must name a Prometheus-type data source that exists in the target
Grafana. See the note on the seeded data source under [Conventions](#conventions-worth-preserving-when-editing)
for why an unresolvable value fails silently rather than loudly.

## Nothing about one installation is baked into the JSON

The dashboards carry placeholders that `helm/templates/grafana-dashboards.yaml`
substitutes at render time:

| Placeholder | Substituted with |
|---|---|
| `__CANFAR_WORKLOAD_NS__` | `include "skaha.workloadNamespace"` — the namespace Skaha launches sessions into |
| `__CANFAR_RELEASE_NS__` | `.Release.Namespace` — where Skaha itself runs |
| `__CANFAR_DS_UID__` / `__CANFAR_DS_NAME__` | `grafanaDashboards.datasourceUid` / `datasourceName` |

Substitution uses Helm's `replace`, **not `tpl`**. Grafana legend formats are written
`{{label}}`, and `tpl` would try to evaluate them as Helm template actions. The template
also fails the render if any `__CANFAR_*__` placeholder survives, because an
unsubstituted placeholder produces a dashboard that loads fine and shows nothing.

Two consequences worth knowing:

- **A release reports on its own sessions.** The session dashboards scope `$namespace` to
  one workload namespace. Where several releases each own a workload namespace, enable
  the dashboards on the release whose sessions you want, or widen the `$namespace` picker
  in Grafana after import.
- **Loading a file straight into Grafana leaves the placeholders literal.** Render through
  Helm, or substitute by hand.

Storage is the deliberate exception: its `$pvc_namespace` picker offers *every* namespace
holding claims and merely *defaults* to this release's two. Scoping the picker itself
would hide claims belonging to other releases, which is the opposite of what a storage
view is for.

Platform Services excludes the workload namespace on every query, not just in its
namespace picker. The picker only ever offered namespaces that own Deployments, so
workload namespaces never appeared there — but its panels read `kube_pod_*` and
`container_*`, and the picker defaults to All, which resolves to `.*` and swept user
sessions in. That is the mirror of the session dashboards, which scope *to* the workload
namespace.

## Prerequisite: kube-state-metrics must expose the CANFAR labels

The session dashboards attribute usage by joining container metrics to `kube_pod_labels`.
kube-state-metrics drops object labels by default since v2.0, so the CANFAR keys must be
named in `--metric-labels-allowlist`. Today's contract needs at least:

```
pods=[canfar.net/id,canfar.net/username,canfar.net/name,canfar.net/kind,canfar.net/job,canfar.net/flavor,canfar.net/accelerator,canfar.net/community,canfar.net/project,canfar.net/app-id]
jobs=[<same list>]
```

Pods and jobs are separate allowlists in kube-state-metrics; the Sessions lifecycle and
duration panels read Job labels, so both must be populated. Verify with two queries that
should each return a non-zero count:

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

Three points from [CADC-15910](https://herzberg.atlassian.net/browse/CADC-15910) that
shape what is here:

- **`project` is the Kueue LocalQueue dimension and `community` the ClusterQueue /
  fair-share dimension.** Communities & Projects groups by both and shows the Kueue
  queue labels beside them for that reason.
- **Both default to `default`, never missing.** Where attribution is not set at launch,
  every session carries `community=default` and `project=default`, and those panels
  collapse to a single bucket while the real segmentation sits in Kueue's own queue
  labels. A large `default` bucket means attribution is not being set, not that the
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
  in the kube-state-metrics allowlist exist as series. Both should be allowlisted, but the
  username gate is kept because it is the one every deployment allowlists first, and an
  earlier revision that gated on the session ID silently emptied every session panel.
  Drilldown groups by `pod` for the same reason — the pod name is always present and
  already encodes kind, user, and session ID.
- **The data source variable ships with a seeded `current`.** A datasource variable with
  an empty `current` does not reliably resolve on a *provisioned* dashboard the way it
  does on one saved from the UI; `${datasource}` interpolates to nothing and every panel
  silently returns no data. Any other Prometheus-type source stays one click away in the
  picker.
- **Node names are shown as Kubernetes reports them.** An earlier revision ran them
  through `label_replace` to strip a common prefix and domain. Where that pattern does not
  match, `label_replace` leaves the target label unset and every row renders blank — a
  cosmetic saving bought with a silent failure. The full name also pastes straight into
  `kubectl`.
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
workload breakdown at all. Counts by kind and by user stay on Overview and
Sessions & Users, and by community and project on Communities & Projects; Platform
Health answers "is the platform holding up", not "what is running".

OOM appears in exactly two places by design. Platform Health owns the operational
view (which node, how often). Session Drilldown keeps terminations because it is
scoped to one chosen session and answers a different question: did *this* session get
killed. That is context, not duplication.

Node-level kills come from `node_vmstat_oom_kill`, the kernel counter, joined to
`node_uname_info` for the node name since the metric only carries `instance`. They
are drawn as a single status-history grid: one cell per node per hour.

Buckets are pinned to an hour by setting the target's `interval` to `1h`, so a cell
means the same thing regardless of panel width or selected range. Colour runs
green-yellow-red by value on a scale pinned from 0 to 10; hover for the exact count.

The pin matters. A continuous scale maps the min and max of whatever data is on screen,
so a single very bad hour would rescale the panel and wash a moderately bad hour out to
green — and the meaning of a colour would shift between refreshes. Fixed bounds keep a
colour comparable across time and across nodes, at the cost of everything above 10
sharing the same red.

Platform Health opens at 24h while the other nine open at 6h. An hourly grid needs
enough columns to show a pattern, and six of them cannot.

The panel is read for shape rather than magnitude. On one deployment during development,
one node showed a single red hour of roughly 28 kills while another showed three separate
yellow hours of about 4 — one blew up once, the other is looping, and only the second
wants draining. A ranked list puts those two adjacent and says nothing about the
difference.

Three approaches were tried and dropped, recorded so they are not retried. Grids split
by node role with a vivid green zero state: almost the whole surface shouted and buried
the signal. A table built on the `timeSeriesTable` transformation with a Trend sparkline:
that transformation emits no separate numeric column, so it rendered as node names beside
grey squiggles with no counts. A sortable counts table beneath the grid: accurate, but
redundant once the grid became hourly and legible.

`container_oom_events_total` looks tempting because it carries `node` and `pod` directly.
Treat it as suspect and check it before relying on it: on one deployment its counters sat
flat at zero across a day in which `node_vmstat_oom_kill` recorded over a hundred kills.

## Storage: some volumes are not measurable

The Volume inventory lists every PersistentVolumeClaim from
`kube_persistentvolumeclaim_info`, not only the ones with usage metrics, because those can
be very different sets. The kubelet only emits `kubelet_volume_stats_*` for volumes it can
stat through the CSI driver, so statically-provisioned volumes — CephFS in particular —
are commonly absent. On one deployment during development a small minority of claims
reported usage, covering well under a quarter of provisioned capacity.

Capacity therefore comes from the claim (always present) and Used and Fill from kubelet
(often absent), with unmeasurable volumes showing "not measurable" rather than being
dropped. The stats row separates *Volumes* from *Reporting usage* for the same reason:
an earlier version showed only measurable volumes, so the volume count read as a total
when it was less than half of them, and the claim holding the actual user data was
silently absent.

Where no `ceph_*` metrics are scraped there is no alternative source for CephFS usage.

## Known gaps

- **No `cluster` label.** Multi-cluster selectors are deliberately absent: no `cluster`
  label is exposed today, and a join on an absent label fails silently to an empty panel.
  Scoping is by namespace.
- **No rollup / recording rules.** The
  [CANFAR Metrics Collection](https://herzberg.atlassian.net/wiki/spaces/C/pages/2335047722/CANFAR+Metrics+Collection)
  page specifies a `canfar:id:*` to `canfar:user:*` to `canfar:project:*` to
  `canfar:community:*` chain with tiered retention. These dashboards query raw series;
  the recording rules remain unwritten.
- **Ephemeral (local) storage usage is not measurable.** Requests and limits are exposed
  by kube-state-metrics, node capacity by node-exporter, and admitted totals by Kueue
  where the ClusterQueue covers `ephemeral-storage` — but how much a session has actually
  written is not in Prometheus at all. It lives in the kubelet Summary API
  (`/stats/summary` → `pods[].ephemeral-storage.usedBytes`), which is JSON and needs a
  translator to scrape. The *consequence* is visible:
  `kubelet_evictions{signal="ephemeralstorage.available"}` and the `DiskPressure` node
  condition.
- **Percentile and waste panels are expensive** — they use
  `max_over_time(...[$__range:5m])` subqueries. Fine at 24h; be careful over a month.
- **Metrics nothing emits yet** are not charted: login latency, token issuance success
  rate, group membership query latency, authentication uptime, storage API response time
  and throughput. A panel that can never populate is indistinguishable from a broken one.
- **Session counts come from `kube_job_*`.** Skaha launches every session as a Job, which
  makes lifecycle and wall-clock duration directly readable, but bounds those counts by
  the Job TTL controller's retention.
