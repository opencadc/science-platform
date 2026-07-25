# CANFAR Science Platform Grafana dashboards

Eight dashboards covering platform health, session usage, resource efficiency, queue
throughput, cluster capacity, storage, and the CANFAR control plane itself.

They are generated, not hand-edited. [`generate.py`](generate.py) writes
[`dist/`](dist); the Helm chart turns `dist/` into ConfigMaps that the Grafana sidecar
discovers.

```bash
cd helm/dashboards
python3 generate.py           # rewrite dist/ (8 dashboards + labels.yaml)
python3 generate.py --check   # fail if dist/ is stale (runs in pre-commit)
```

No third-party Python packages are required.

---

## Why generated

The four session dashboards must work against two label generations — the
pre-realignment `canfar-net-*` keys and the `canfar.net/*` keys introduced by
[opencadc/science-platform#1112](https://github.com/opencadc/science-platform/pull/1112)
— because during the migration both kinds of session run at once and #1112 does **not**
dual-write.

Rather than commit two copies, each dashboard is emitted **once** with a
`__CANFAR_LABEL_*__` placeholder wherever a Prometheus label name belongs. The Helm
chart substitutes the real names at install time from `dist/labels.yaml`, so eight
source files can render as many ConfigMaps as there are installed generations.

A dashboard containing no placeholder is generation-independent (queue, capacity,
storage, services) and Helm renders it exactly once, without a uid suffix, however many
generations are installed. Nothing declares that split — it falls out of whether the
file mentions a session label.

Dimensions the legacy contract never had (project, community, flavor, accelerator)
resolve to their post-#1112 names in both generations. On legacy data those labels do
not exist, so the panels read empty and their filters match only at `All` — which is the
honest rendering of "this attribution did not exist yet".

## The dashboards

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

The first four carry label placeholders and are rendered once per installed
generation; legacy copies get a `-legacy` uid suffix and a `(legacy labels)` title
suffix. The last four are generation-independent and render once.

### Design rules the generator enforces

- **One dashboard, one question, one audience.** Overview never becomes a dumping
  ground; drilldown never tries to be a summary.
- **Every panel has a unit and a threshold.** A bare number nobody can interpret is a
  bug. Efficiency thresholds run red-at-the-bottom (low is waste); saturation
  thresholds run red-at-the-top.
- **Every panel has a description** saying what to conclude from it, not restating the
  title. These render as the `i` tooltip.
- **Rates use `$__rate_interval`**, never `$__interval` or a hard-coded `[5m]`, so
  panels stay correct at every zoom level.
- **Efficiency counts running pods only.** Pending and Completed pods hold no working
  set and would otherwise drag every efficiency number toward zero.
- **Variables are named for the dimension, not the label** (`$user`, `$kind`), so the
  cross-dashboard nav dropdown carries filters intact between legacy and next boards.
- **Session queries key off the *user* label, never the session ID.**
  kube-state-metrics only exports labels named in `--metric-labels-allowlist`. The user
  label is the one every CANFAR deployment already allowlists; the session ID usually is
  not. Gating on the ID silently empties every session panel. Drilldown groups by `pod`
  for the same reason — the pod name is always present and already encodes kind, user,
  and session ID.
- **The datasource variable ships with a seeded `current`.** A datasource variable with
  an empty `current` does not reliably resolve on a *provisioned* dashboard the way it
  does on one saved from the UI; `${datasource}` interpolates to nothing and every panel
  silently returns no data. See `DEFAULT_DATASOURCE_UID` in `lib.py`.
- **Windowed totals derive from `$__range_s`**, not a hard-coded window, so "memory
  wasted" means what the time picker says.

---

## The label contract

kube-state-metrics rewrites Kubernetes label keys before exposing them: characters
outside `[a-zA-Z0-9_]` become `_`, camelCase becomes snake_case, everything is
lowercased, and a `label_` prefix is added. `ksm_label()` in [`lib.py`](lib.py)
implements that transform, so the PromQL label names are derived rather than typed by
hand. It is verified against four label names observed in the dashboards currently
running in production.

| Dimension | legacy (`main`) | next (#1112) | Prometheus label (next) |
|---|---|---|---|
| Session ID | `canfar-net-sessionID` | `canfar.net/id` | `label_canfar_net_id` |
| User | `canfar-net-userid` | `canfar.net/username` | `label_canfar_net_username` |
| Kind | `canfar-net-sessionType` | `canfar.net/kind` | `label_canfar_net_kind` |
| Name | `canfar-net-sessionName` | `canfar.net/name` | `label_canfar_net_name` |
| Desktop app | `canfar-net-appID` | `canfar.net/app-id` | `label_canfar_net_app_id` |
| Job | — | `canfar.net/job` | `label_canfar_net_job` |
| Flavor | `opencadc.org/canfar-job-{fixed,flexible}` (Job) | `canfar.net/flavor` (pod) | `label_canfar_net_flavor` |
| Accelerator | — | `canfar.net/accelerator` | `label_canfar_net_accelerator` |
| Project | — | `canfar.net/project` | `label_canfar_net_project` |
| Community | — | `canfar.net/community` | `label_canfar_net_community` |

Two consequences worth knowing when reading a legacy-generation install:

- **No project or community attribution exists on the legacy contract.** The
  project-grouped panels and the per-project summary table render empty. They are kept
  rather than stripped so the same eight files serve both generations, and so the gap
  is visible rather than silent.
- **Legacy sizing was a pair of boolean *Job* labels** (`opencadc.org/canfar-job-fixed`
  / `-flexible`), not the pod-level `canfar.net/flavor`. The flavor panels therefore
  read empty on legacy data.

> [!NOTE]
> The [CANFAR Metrics Collection](https://herzberg.atlassian.net/wiki/spaces/C/pages/2335047722/CANFAR+Metrics+Collection)
> Confluence page lists the required labels as `canfar.net/uuid` and `canfar.net/user`.
> The code merged in #1112 writes `canfar.net/id` and `canfar.net/username`. These
> dashboards follow the code, because that is what will actually be on the pods —
> confirmed by `PostAction.java:206`, which generates the session ID with
> `RandomStringGenerator(8)`, so it is an 8-character string and not a UUID. If the
> Confluence page is the intended contract instead, the Java side needs to change and
> the `DIMS` table in `lib.py` is a two-line edit.

---

## Prerequisite: kube-state-metrics must expose the labels

**The session dashboards return no data unless kube-state-metrics is told to expose
CANFAR pod labels.** Since v2.0 it drops all object labels by default. This is
cluster-side configuration and lives outside this chart — in `kube-prometheus-stack`
values, it looks like:

```yaml
kube-state-metrics:
  extraArgs:
    - --metric-labels-allowlist=pods=[canfar.net/id,canfar.net/username,canfar.net/kind,canfar.net/name,canfar.net/app-id,canfar.net/job,canfar.net/flavor,canfar.net/accelerator,canfar.net/project,canfar.net/community],jobs=[canfar.net/id,canfar.net/username,canfar.net/kind,canfar.net/project,canfar.net/community]
```

While the legacy dashboards are still installed, keep the old keys allowlisted too:

```
pods=[canfar-net-sessionID,canfar-net-userid,canfar-net-sessionType,canfar-net-sessionName,canfar-net-appID,...]
jobs=[canfar-net-sessionID,canfar-net-userid,canfar-net-sessionType,opencadc.org/canfar-job-fixed,opencadc.org/canfar-job-flexible]
```

Quick check that it is working — this should return a non-empty result:

```promql
count(kube_pod_labels{label_canfar_net_username!=""})
```

Other required exporters: **kube-state-metrics** (`kube_*`), **cAdvisor/kubelet**
(`container_*`, `kubelet_volume_stats_*`), **node-exporter** (`node_*`), and
**Kueue** metrics scraped into Prometheus (`kueue_*`). Only the Queue dashboard needs
Kueue; the rest degrade to a single empty panel without it.

---

## Deploying

Off by default. Enable in your values:

```yaml
grafanaDashboards:
  enabled: true
  folder: CANFAR Science Platform
  schemes:
    - legacy   # drop once every running session carries canfar.net/* labels
    - next
```

Enable this on **one** release only. Two Skaha releases pointing at the same Grafana
would render ConfigMaps carrying identical dashboard uids, and the sidecar would import
whichever it wrote last. The `$namespace` picker already spans every workload namespace,
so a single install covers them all.

The chart renders one ConfigMap per dashboard, labelled `grafana_dashboard: "1"` and
annotated `grafana_folder: CANFAR Science Platform`. Each ConfigMap key is prefixed
with its scheme (`canfar-next-overview.json`), so the sidecar never writes two
dashboards to the same filename when both schemes are installed.

`namespace` matters when the Grafana sidecar only watches one namespace — set it to
wherever Grafana looks, which is often not the Skaha release namespace.

### Migration sequence

1. Add the **new** label keys to the kube-state-metrics allowlist, keeping the old ones.
2. Deploy with `schemes: [legacy, next]`. The next-generation boards will be empty
   until #1112 ships — that is expected.
3. Ship #1112. New sessions populate the `next` boards; long-lived sessions keep
   populating `legacy`.
4. When `count(kube_pod_labels{label_canfar_net_session_id!=""})` reaches zero, set
   `schemes: [next]` and drop the old keys from the allowlist.

---

## Known limitations

- **No `cluster` label.** Multi-cluster selectors were deliberately left out: the
  current hand-built CANFAR dashboards do not use one, and a join on a label that is
  not present fails silently to an empty panel rather than loudly. Scoping is by
  `$namespace`. If you standardise on a `cluster` external label, add it to `on(...)`
  in the join helpers in `lib.py`.
- **Percentile and waste panels are expensive.** They use subqueries
  (`max_over_time(...[$__range:5m])`) that re-evaluate the label join at every step.
  They are fine on a 24h range; be careful pointing them at a month.
- **Recording rules are the obvious next step.** The
  [CANFAR Metrics Collection](https://herzberg.atlassian.net/wiki/spaces/C/pages/2335047722/CANFAR+Metrics+Collection)
  page specifies a `canfar:id:* → canfar:user:* → canfar:project:* → canfar:community:*`
  rollup chain with tiered retention. That is not implemented here — these dashboards
  query raw series directly. Once those rules exist, the join helpers in `lib.py` are
  the single place that needs to change to consume them, and the expensive panels get
  cheap.
- **Session start/completion counts come from `kube_job_*`.** Skaha launches every
  session as a Job, which makes lifecycle and wall-clock duration directly readable.
  It also means these counts only cover sessions that Kubernetes still remembers, so
  they are bounded by the Job TTL controller.

---

## Changing the label contract

`dist/labels.yaml` is generated from the `DIMS` table in `lib.py` via the
kube-state-metrics name-mangling rules — never hand-edit it. To add a dimension, add a
`Dim` (with its legacy key, or `None` if the dimension is new) and regenerate. Helm
fails the render if a dashboard still contains a placeholder the map does not cover, so
the two cannot drift apart silently.

## Adding a panel

Panels are plain dicts built by the helpers in `lib.py`. To add one to the overview:

```python
lay.add(
    timeseries(
        "My panel",
        targets(target(by_dim(scheme, pod_cpu_used(), ["kind"]),
                       legend=f"{{{{{scheme.promql('kind')}}}}}")),
        unit=CORES,
        desc="What the reader should conclude from this.",
        legend="table",
        calcs=["mean", "max"],
    ),
    12,  # width, of 24
    9,   # height
)
```

`Layout` places panels left-to-right and wraps automatically, so no `gridPos` is ever
written by hand. If the panel depends on a dimension the legacy contract lacks, guard
it with `if scheme.has("project"):` — `lay.add(None, ...)` is also a no-op, so an
inline conditional expression works too.

Then run `python3 generate.py` and commit both the generator change and `dist/`.
