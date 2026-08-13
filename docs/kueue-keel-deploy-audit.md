# Kueue configuration audit — CANFAR `keel-deploy`

**Status:** Audit, for action · **Date:** 2026-07-31
**Target:** `keel-deploy@main` — `manifests/kueue/**`, `helm/values/*/kueue/**`
**Companion:** [`kueue-fairshare-design.md`](kueue-fairshare-design.md) — read §0 and §1 first.

> **Status update — 2026-08-03.** Two findings below have moved.
>
> **F1 (GPU ledger reads zero) is fixed upstream**, not by configuration.
> [`5eae484`](https://github.com/kubernetes-sigs/kueue/commit/5eae48454bc8bea30fe2b1842cc3b9be81c95893)
> (PR #13621) replaces the integer milli round-trip in `MulByFloat` with arbitrary-precision decimal.
> **Built from `main` and validated on a live cluster**: a queue holding 1 CPU + 1 whole-unit GPU now
> records `0.1298` for *both*, where v0.17.0 recorded `0` for both. The half-life/sampling retune this
> audit recommended (`168h/15m`) is therefore **withdrawn** — it was a workaround for a bug that no
> longer needs working around, and at `168h/15m` it only reached 0.1 % accuracy anyway. Keep
> `usageHalfLifeTime: 336h`; set `usageSamplingInterval` from write-rate considerations, not accuracy.
>
> **A defect not in this audit was found and is more immediate: `consumedResources` CPU reads exactly
> zero on v0.17.0** (upstream #12694, fixed in v0.17.7), measured against ground-truth `flavorsUsage`
> showing a whole core held, at an α 400× above the truncation threshold. Combined with F1, **the
> deployed ledger is a pure memory measurement** — which supersedes F5's conclusion that memory merely
> *dominates* at 56.5 %.
>
> **F6 stands and is now the highest-priority mechanical fix**: v0.17.0 lacks #12694, #12891 and
> #12946. Minimum is **v0.17.8**; the target is a build carrying `5eae484`.
>
> Every other finding stands as written.

---


Audited against the files as they exist on `keel-deploy@CADC-16007-rename-clusterqueue-ska-to-src`, verified line-by-line against Kueue source at tags `v0.17.0` (deployed), `v0.17.8`, and `v0.19.0`.

### Headline

Both suspected defects were investigated. **Suspected defect 1 is refuted, and applying the proposed "one-line fix" would take the scheduler down.** Suspected defect 2 is confirmed, but the failure mode is materially worse than described — the reclaim path that was assumed to be the backstop does not work for the case that matters.

Three findings not on the original list outrank both of them:

1. **The GPU fair-share ledger reads approximately zero.** At the deployed `usageHalfLifeTime: 336h` / `usageSamplingInterval: 5m`, a LocalQueue holding a steady 1–11 GPUs converges to a ledger value of 0.000–0.001 GPU. GPU is the resource weighted 35× precisely because it is the scarce one, and it is the one resource not being charged.
2. **Admission Fair Sharing is currently arbitrating nothing.** AFS orders *between* LocalQueues within a ClusterQueue. skaha routes 100% of `canfar.net` traffic to `cadc-default` and 100% of `src.canfar.net` traffic to `src-default`. `cadc-unions` and `cadc-canucs` are referenced nowhere outside their own manifest. With one populated LocalQueue per ClusterQueue, every workload ties on usage and ordering falls through to priority — which means interactive (`high`) strictly starves batch (`low`), the exact outcome AFS was deployed to prevent.
3. **The weighted pool is a memory ledger, not a GPU ledger.** The premise in the audit brief (`2800·1 + 112·35 = 6720`, GPU = 58%) omits memory and ephemeral-storage, which are also in `resourceWeights`. The real split is memory 56.5%, GPU 17.9%, ephemeral-storage 12.9%, CPU 12.8%.

---

### Findings

| # | Sev | File / field | Current | Problem | Recommended |
|---|-----|--------------|---------|---------|-------------|
| F1 | **critical** | `controller.config.yml` › `admissionFairSharing.usageHalfLifeTime` / `usageSamplingInterval` | `336h` / `5m` | `MulByFloat` truncates to int64 milliunits. α = 1.719e-4 ⇒ any resource held below 5.82 units contributes **0** per tick. GPU ledger is dead below ~12 GPUs. | `168h` / `15m` |
| F2 | **critical** | `prod.src.yml` › all four resources | no `borrowingLimit` | Absent = unlimited. `src` (GPU nominal 0) can hold all 112 cadc GPUs; `reclaimWithinCohort: LowerPriority` requires **strictly** lower priority, so cadc batch (`low`) can never reclaim from an src interactive session (`high`). | explicit `borrowingLimit` per resource; `"0"` on GPU |
| F3 | **critical** | *(process)* — do **not** add `fairSharing.enable: true` | absent | In `config.../v1beta2` the field **does not exist**; cohort FS is already on. Config loading uses `serializer.EnableStrict` ⇒ unknown field is a hard decode error ⇒ controller CrashLoopBackOff. | leave as-is; fix the stale research doc |
| F4 | **critical** | skaha `kueue.default` / `kueue.headless` → same LocalQueue | both → `cadc-default` (and `src-default`) | One populated LocalQueue per ClusterQueue ⇒ AFS ordering always ties ⇒ falls through to priority ⇒ `high` interactive starves `low` batch. AFS is inert on both queues. | route by `canfar.net/community` to per-community LocalQueues |
| F5 | **high** | `controller.config.yml` › `resourceWeights` | cpu 1.0 / mem 9.31323e-10 / eph 2.6609e-11 / gpu 35 | Arithmetic is correct; the **policy** is not. Memory = 56.5% of the weighted pool, GPU = 17.9%, ephemeral scratch = 12.9%. | cpu 1.0 / mem 2.62873e-10 / eph 6.5718e-12 / gpu 62.5 |
| F6 | **high** | Kueue version | `v0.17.0` | On the broken side of AFS warm-start bug #12891: every restart discards the ledger and reseeds from an instantaneous admitted-usage snapshot. `336h` is fiction unless uptime ≫ 14d. | `v0.17.8` (same minor, same APIs) |
| F7 | **high** | both CQs › `preemption.borrowWithinCohort` | `LowerPriority`, `maxPriorityThreshold: 10000` | Dead config. API: *"It only works with Classical Preemption, **not** with Fair Sharing."* Verified: `getTargets` branches to `fairPreemptions`, which never reads it. | delete the block |
| F8 | **medium** | `prod.workloadPriority.yml` › `medium` | defined, referenced nowhere | Only `low`(10⁴) and `high`(10⁶) are in use, so `withinClusterQueue: LowerPriority` = every interactive session may preempt every batch job, cross-user, with zero AFS involvement. | assign `medium` to protected batch, **or** decide the policy explicitly |
| F9 | **medium** | `prod.resourceFlavors.yml` › `default` | no `nodeLabels`, no `tolerations` | Quota admission is decoupled from node fit: a GPU workload can be admitted against cluster-wide quota, then sit Pending with no GPU node — **while burning fair share**, since `GetAdmittedUsage()` counts admitted, not running. | split `cpu` / `gpu` flavors with node labels |
| F10 | **medium** | both CQs › `stopPolicy: None` + no drain runbook | `None` | No documented maintenance lever. The one people reach for — delete/recreate a LocalQueue — purges `AfsConsumedResources` + `AfsEntryPenalties` and destroys the persisted status. Permanent fair-share reset, at **every** version incl. v0.19. | runbook + `Hold`; guard LocalQueues against deletion |
| F11 | **medium** | `controller.config.yml` › `clientConnection.qps: 64 / burst: 128` | `64/128` | Ceiling is 6× the measured ~10 writes/s control-plane ceiling, against an API server with a failing `assignment.misc.projectcapsule.dev` webhook on the write path and etcd fsync 2ms→15ms. | measure first; F1's 15m sampling already removes ⅔ of LocalQueue status writes |
| F12 | **medium** | `integrations.frameworks: [batch/job]` | correct today | Interactive **is** covered (skaha submits `batch/Job` + `spec.suspend`). But JobSet/Ray/MPI/PyTorch workloads would run **unmanaged and unmetered** — a silent fair-share bypass, not just a missing feature. | keep; add an admission policy blocking non-Job kinds in the two namespaces |
| F13 | **low** | `metrics.localQueueMetrics` | absent | **Not a defect.** `LocalQueueMetrics` is Beta/**default-true** at v0.17 (Alpha/false only through v0.16), and `defaults.go:77` sets `{Enable: true}` when absent. All 17 `kueue_local_queue_*` metrics already emit. | add block explicitly as intent; pre-write the selector |
| F14 | **low** | `prod.cohorts.yaml` › `spec: {}` | `{}` | **Correct as-is.** `fairSharing.weight` on a Cohort is only meaningful when the Cohort has a parent; `canfar` is root. `resourceGroups` would be an *additional* shared pool, which CANFAR doesn't have. | leave; optionally move GPU quota here (see F14 detail) |
| F15 | **low** | `queueingStrategy: BestEffortFIFO` | `BestEffortFIFO` | **Correct.** Document it so nobody "fixes" it to StrictFIFO. | keep + comment |
| F16 | **low** | `managedJobsNamespaceSelector` | set | Inert today: for non-pod integrations it only modulates `manageJobsWithoutQueueName`, which is `false`. Becomes load-bearing if pod-based integrations are ever added. | keep; comment that it is currently inert |
| F17 | **low** | `controller.groupKindConcurrency.Pod: 64` | `64` | Dead — the `pod` integration is not enabled, so the Pod reconciler is never registered. | remove |
| F18 | **info** | `labelKeysToCopy` | already copies username/community/project/kind | The one unambiguously good thing in this config, and it is unexploited. Every Workload already carries per-user attribution. | build the per-user ledger from Workload labels (see F18 detail) |

---

### F1 — GPU fair-share ledger reads zero (critical)

`CalculateDecayedConsumed` computes `consumed = MulByFloat(old, 1−α) + MulByFloat(new, α)`, and `MulByFloat` truncates:

```go
// pkg/util/resource/resource.go:94-101 — identical at v0.17.0 AND v0.19.0
scaledV := float64(v.MilliValue()) * f
ret[k] = *resource.NewMilliQuantity(int64(scaledV), resource.DecimalSI)
```

This is **not fixed upstream** — I diffed v0.19.0 and the function is byte-identical. The only lever CANFAR has is α, and `α = 1 − 0.5^(sampling/halfLife)`.

At the deployed `336h` / `5m`, α = 1.71897e-4. Simulating the actual integer arithmetic to steady state:

| true GPUs held | 336h/5m (deployed) | 168h/5m | **168h/15m (rec.)** | 72h/15m |
|---|---|---|---|---|
| 1 | 0.000 | 0.000 | 0.001 | 0.416 |
| 2 | 0.000 | 0.000 | **0.970** | 1.248 |
| 4 | 0.000 | 0.001 | **2.910** | 3.328 |
| 7 | 0.001 | 2.909 | **5.820** | 6.240 |
| 12 | 5.818 | 8.727 | **10.670** | 11.232 |
| 20 | 11.635 | 14.545 | **18.430** | 19.552 |
| 112 | 104.715 | 107.633 | **110.579** | 111.488 |

Two things to notice beyond the obvious under-count. First, the ledger is **non-monotonic in resolution**: at 336h a queue holding 12 GPUs and one holding 16 both converge to 5.818 — AFS literally cannot tell them apart. Second, the entry penalty suffers the same truncation: `CalculateEntryPenalty = MulByFloat(totalRequests, α)`, so at 336h/5m a 1-GPU session's anti-burst penalty is **exactly 0**.

`168h` is also the value in Kueue's own documentation example and matches Slurm's default `PriorityDecayHalfLife` of 7 days. Harvard FASRC runs 3 days, HTCondor `PRIORITY_HALFLIFE` defaults to 1 day. Given AFS decays an EWMA of *concurrency* rather than an accumulating counter — so a user who stops consuming already drops to zero instantaneously in the `new` term — 336h is indefensibly long even before the truncation argument. The 15m sampling interval is a free bonus: it triples α *and* cuts LocalQueue status-write traffic by ⅔, which matters given F11.

```diff
--- a/manifests/kueue/controller/controller.config.yml
+++ b/manifests/kueue/controller/controller.config.yml
 admissionFairSharing:
-  usageHalfLifeTime: 336h
-  usageSamplingInterval: 5m
+  # 168h == Kueue's own doc example and Slurm's default PriorityDecayHalfLife (7d).
+  # 15m sampling raises the per-tick alpha to 1.031e-3 (from 1.719e-4), which lifts
+  # the integer-milliunit truncation floor in resource.MulByFloat from 5.82 GPU to
+  # 0.97 GPU and makes the 1-GPU entry penalty non-zero for the first time.
+  # See ADR / audit F1. Do not raise halfLife without re-deriving the floor:
+  #   floor_units = 1 / (1 - 0.5^(sampling_s / halfLife_s)) / 1000
+  usageHalfLifeTime: 168h
+  usageSamplingInterval: 15m
```

Apply the **same change** to the embedded copy in `manifests/kueue/controller/prod.controller.yml` (`managerConfig.controllerManagerConfigYaml`) — that file carries a byte-duplicated copy of the whole Configuration and is the one actually rendered into the ConfigMap.

### F2 — unbounded borrowing, and the reclaim path does not work (critical)

Confirmed from the v1beta2 API: *"borrowingLimit … If null, it means that there is no borrowing limit."* Both ClusterQueues set `lendingLimit == nominalQuota`, which per the API (*"If null … all the nominalQuota can be borrowed"*) is **exactly equivalent to omitting the field** — those eight lines encode no policy at all.

The severe part is the reclaim path. `findCandidates` filters cohort candidates through `SatisfiesPreemptionPolicy`, which for `LowerPriority` is a strict comparison:

```go
// pkg/scheduler/preemption/common/preemption_policy.go:33-38
lowerPriority := preemptorPriority > candidatePriority
if policy == kueue.PreemptionPolicyLowerPriority { return lowerPriority }
```

Combine that with skaha's deployed mapping — `default` (all interactive) → `high` = 1,000,000 and `headless` (all batch) → `low` = 10,000, identical on `canfar.net` and `src.canfar.net`:

- An **src** user opens a GPU notebook. src's GPU nominalQuota is 0, so every GPU it holds is borrowed from cadc, without limit.
- cadc wants its GPUs back and submits batch at `low`. `10000 > 1000000` is false ⇒ **not a candidate**. cadc cannot reclaim.
- A cadc *interactive* session also can't reclaim: `high > high` is false.

So cadc's only recourse against src squatting on all 112 GPUs is… nothing. And there is no safe recourse available, because the workloads holding the GPUs are exactly the interactive sessions the platform owners have said must not be preempted. The correct control is therefore an *ex ante* cap, not *ex post* reclaim.

The same argument applies to CPU and memory: src's 200-core nominal against cadc's 2,800-core lendable means src can hold 15× its nominal quota, unreclaimably, by running interactive.

```diff
--- a/manifests/kueue/clusterQueues/prod.src.yml
+++ b/manifests/kueue/clusterQueues/prod.src.yml
           resources:
             - name: "cpu"
               nominalQuota: "200"
               lendingLimit: "200"
+              # Absent borrowingLimit == UNLIMITED. reclaimWithinCohort only
+              # preempts STRICTLY lower priority, and skaha runs all interactive
+              # at high=1000000, so cadc can never reclaim from an src notebook.
+              # Cap ex ante instead of relying on a reclaim that cannot fire.
+              borrowingLimit: "200"
             - name: "memory"
               nominalQuota: "1600Gi"
               lendingLimit: "1600Gi"
+              borrowingLimit: "1600Gi"
             - name: "ephemeral-storage"
               nominalQuota: "4800Gi"
               lendingLimit: "4800Gi"
+              borrowingLimit: "4800Gi"
             - name: "nvidia.com/gpu"
               nominalQuota: "0"
               lendingLimit: "0"
+              # src owns no GPUs by design. "0" makes that enforceable rather
+              # than aspirational. Raise deliberately if src is meant to get GPUs.
+              borrowingLimit: "0"
```

Set an explicit `borrowingLimit` on `prod.cadc.yml` too (`cpu: "200"`, `memory: "1600Gi"`, `ephemeral-storage: "4800Gi"`, `nvidia.com/gpu: "0"` — src's full lendable). cadc borrowing is far less dangerous because src's lendable pool is small, but the symmetry makes the policy auditable and removes the same class of surprise.

### F3 — `fairSharing.enable: true` is refuted, and would be an outage (critical)

The field exists in `config.kueue.x-k8s.io/**v1beta1**` (`Enable bool // Defaults to false`). It was **removed** in the v1beta2 migration. CANFAR deploys `v1beta2`:

```go
// pkg/scheduler/preemption/fairsharing/strategy.go:50-52 @ v0.17.0
func Enabled(pfs *config.FairSharing) bool { return pfs != nil }
```

The v1beta1→v1beta2 conversion confirms the design intent — it drops the struct entirely when `enable` was false (`configuration_conversion.go:45-47`), so in v1beta2 *presence is the switch*. **Cohort Fair Sharing is already enabled on CANFAR.** `ClusterQueue.status.fairSharing.weightedShare` is being populated on both queues today.

Adding the field would not be a no-op:

```go
// pkg/config/config.go:57
codecs := serializer.NewCodecFactory(scheme, serializer.EnableStrict)
```

Strict decoding rejects unknown fields, so `enable: true` in the ConfigMap ⇒ decode error at startup ⇒ `kueue-controller-manager` CrashLoopBackOff ⇒ every new session and job stays suspended.

The misconception traces to `science-platform/docs/research/kueue-fairshare-tenancy.md` §A.1, which quotes `apis/config/v1beta1` against a stated v0.16.x target. That document is now stale in at least two places — §A.1 (`enable`) and §B.2 (`LocalQueueMetrics` is described as "Alpha and off"; it went Beta/default-**true** at v0.17). Correcting it should be part of this change set, because it is the artefact people will read next.

Two v0.17-new gates also flipped on by default and change fair-sharing preemption behaviour — worth knowing before touching preemption: `FairSharingPreemptWithinNominal` and `FairSharingPrioritizeNonBorrowing`, both `{Version: "0.17", Default: true, Beta}`. The first is why `fairPreemptions` has a `preemptorWithinNominal` fast path that bypasses the DRS strategy check entirely — it still respects the priority filter, so it does not rescue F2.

### F4 — AFS is arbitrating nothing (critical)

`AfsConsumedResources` is keyed by `LocalQueueReference = (namespace, spec.queueName)`. The comparator returns before ever reaching the priority tiebreaker *only if the two usage floats differ* — and workloads in the same LocalQueue always have identical usage.

Deployed reality:

| ClusterQueue | LocalQueues defined | LocalQueues skaha actually uses |
|---|---|---|
| `cadc` | `cadc-default`, `cadc-unions`, `cadc-canucs` | `cadc-default` only (both `default` and `headless`) |
| `src` | `src-default` | `src-default` only |

`cadc-unions` and `cadc-canucs` appear nowhere in keel-deploy outside `helm/values/canfar.net/kueue/localQueues/prod.cadc.yml`. So on both ClusterQueues, every workload has the same LQ usage, `cmp.Compare(lqAUsage, lqBUsage)` returns 0, and ordering falls through to `baseCompareFunc` — priority descending, then timestamp. Interactive at `high` therefore admits ahead of *all* batch, always, from every user.

This is the finding that determines whether any of the others matter: tuning half-life and weights (F1, F5) improves a number that nothing currently reads.

The fix is routing, not Kueue config. skaha already stamps `canfar.net/community` on every Job and `labelKeysToCopy` already propagates it. Route the LocalQueue by community rather than by session type:

```diff
--- a/helm/values/canfar.net/skaha/prod.yaml
+++ b/helm/values/canfar.net/skaha/prod.yaml
       kueue:
         rbac:
           create: true
         default:
-          queueName: "cadc-default"
+          # queueName resolved per-community at submit time; this is the fallback
+          # for users with no community. See skaha QueueConfiguration.
+          queueName: "cadc-default"
           priorityClass: "high"
         headless:
           queueName: "cadc-default"
           priorityClass: "low"
```

This requires a skaha change (queue selection currently keys off session type via `SKAHA_QUEUE_<TYPE>_NAME`) and relaxing skaha's boot-time "validate every configured LocalQueue exists, refuse to boot on 404" check. Per-community keeps the LocalQueue population bounded and small — which matters, because per-*user* LocalQueues would multiply the F10 deletion hazard by the user count and were already refuted on cardinality grounds in the existing research doc (H2).

### F5 — resourceWeights: arithmetic correct, policy wrong (high)

Arithmetic checks out: `9.313225746154785e-10 = 1/2³⁰` (configured value is off by 4.6e-7 relative — irrelevant), and `2.66092164175851e-11 = (1/35)/2³⁰` (off by 8.1e-6 relative). The config comments are accurate.

The policy is where it goes wrong. Weighted capacity of `cadc`, counting *all four* weighted resources:

| resource | capacity | × weight | contribution | share |
|---|---|---|---|---|
| cpu | 2,800 | 1.0 | 2,800 | 12.8% |
| memory | 12,400 GiB | 1.0/GiB | 12,400 | **56.5%** |
| ephemeral-storage | 99,200 GiB | 1/35 per GiB | 2,834 | 12.9% |
| nvidia.com/gpu | 112 | 35 | 3,920 | 17.9% |

Memory dominates. And CPU and memory are near-perfectly **collinear** — a node ships both in a fixed ratio (cluster-wide 12,400/2,800 = 4.43 GiB/core), so weighting both at 1.0 per unit charges node occupancy twice and dilutes GPU to 18%. Ephemeral-storage — scratch disk, which no user reasons about — carries as much fairness weight as CPU.

Set weights from an explicit target share instead. With `weight_r = (total_pool × target_share_r) / capacity_r`, anchored on `cpu = 1.0` and targets 20/25/5/50:

```diff
   resourceWeights:
     cpu: 1.0
-    # Memory counted in bytes, 1GiB = 2^30 bytes
-    # 1 / 2^30 = 9.313225746154785e-10
-    memory: 0.000000000931323
-    # Ephemeral storage counted in bytes, 1GiB = 2^30 bytes
-    # (1/35) / 2^30 = 2.66092164175851e-11
-    ephemeral-storage: 0.000000000026609
-    nvidia.com/gpu: 35
+    # Weights are derived from a TARGET SHARE of the weighted pool, not from
+    # unit equivalence:  weight_r = (pool * target_r) / capacity_r, cpu anchored
+    # at 1.0 so pool = 2800 / 0.20 = 14000.
+    #   cpu   20%  2800 cores   -> 1.0
+    #   mem   25%  12400 GiB    -> 0.28226/GiB = 2.62873e-10/byte
+    #   eph    5%  99200 GiB    -> 0.0070565/GiB = 6.5718e-12/byte
+    #   gpu   50%  112 GPUs     -> 62.5
+    # Previously memory was 56.5% of the pool and GPU only 17.9%; scratch
+    # ephemeral-storage carried as much weight as all CPU. See audit F5.
+    memory: 0.000000000262873
+    ephemeral-storage: 0.0000000000065718
+    nvidia.com/gpu: 62.5
```

Sensitivity, holding the other three fixed: GPU weight 35 → 17.9% of pool, 50 → 23.7%, 62.5 → 28.2%, 100 → 38.6%. (The table above reaches a clean 50% because memory and ephemeral are rebalanced at the same time.) Pick the target share the owners actually want and re-derive; do not tune the GPU number in isolation.

**Critical ordering constraint:** `resourceWeights` are applied in `CalculateUsage`, *after* `CalculateDecayedConsumed` has already truncated. Raising the GPU weight does **nothing** for F1 — 35 × 0 and 62.5 × 0 are both 0. F1 must land first or this change is cosmetic.

### F6 — v0.17.0 is on the broken side of AFS warm-start (high)

Established in phase 1 and re-confirmed: at v0.17.0 `initializeAfsIfNeeded` seeds the cache from `cacheLq.GetAdmittedUsage()` — a live snapshot — and never reads back the persisted `status.fairSharing.admissionFairSharingStatus.consumedResources` it writes every tick. PR #12891 first ships in **v0.17.7 / v0.18.3 / v0.19.0**. `v0.18.0`–`v0.18.2` do **not** carry it, so "upgrade to 0.18" is a trap.

Every controller restart — Helm upgrade, image bump, OOMKill, node drain, leader failover — discards the ledger. The effective half-life is `min(336h, MTBF of the controller pod)`. Two other AFS defects land in the same train (a CPU-truncating-to-zero path when the sampling guard is bypassed by informer lag, and a sampling/settlement race that persists a skewed value), and both corrupt the exact field the owners want to show users.

```diff
--- a/manifests/kueue/controller/prod.controller.yml
+++ b/manifests/kueue/controller/prod.controller.yml
 # Helm Values for K8s Kueue Production Deployment
-# Expected Kueue Version: 0.17.0
+# Expected Kueue Version: 0.17.8
+# Minimum is 0.17.7 — AFS warm-start fix (#12891). v0.18.0-.2 do NOT have it.
```

Patch-level bump inside the same minor; `kueue.x-k8s.io/v1beta2` and `config.kueue.x-k8s.io/v1beta2` both unchanged. Lowest-risk possible remediation. It does **not** fix F1 — the truncation is still present at v0.19.0.

### F7 — `borrowWithinCohort` is dead config (high)

Verbatim from `apis/kueue/v1beta2/clusterqueue_types.go:508-510`:

> BorrowWithinCohort contains configuration which allows to preempt workloads within cohort while borrowing. It only works with Classical Preemption, `__not__` with Fair Sharing.

Verified in code — `getTargets` branches on `p.enableFairSharing` (true, since the `fairSharing` block is present) into `fairPreemptions`, and `IsBorrowingWithinCohortForbidden` is called only inside `classicalPreemptions`.

Answering the brief's question directly: `maxPriorityThreshold: 10000` exactly equals `low`, so *if* it were live it would mean "a borrowing workload may preempt `low` workloads only, never `medium` or `high`" — which is a coherent and arguably intended policy. It is simply not in effect. Delete it rather than leave a plausible-looking lie in the manifest; the CEL rule `!(reclaimWithinCohort == 'Never' && has(borrowWithinCohort) && borrowWithinCohort.policy != 'Never')` makes removal always valid.

```diff
--- a/manifests/kueue/clusterQueues/prod.cadc.yml   (and prod.src.yml)
+++ b/manifests/kueue/clusterQueues/prod.cadc.yml
   preemption:
     reclaimWithinCohort: LowerPriority
-    borrowWithinCohort:
-      policy: LowerPriority
-      maxPriorityThreshold: 10000
+    # borrowWithinCohort intentionally omitted: it only applies to Classical
+    # Preemption, not Fair Sharing, and this deployment runs Fair Sharing
+    # (controller Configuration has a fairSharing block). See audit F7.
     withinClusterQueue: LowerPriority
```

### F8 — the priority ladder is the real gaming channel (medium)

Phase 1 established that priority **cannot** buy a larger share at admission under AFS — `cmp.Compare(lqAUsage, lqBUsage)` returns before `baseCmp`. The owners' fear is mechanically wrong for admission ordering. It is right for **preemption**, which is entirely AFS-blind (`grep -rn 'admissionfairsharing|AdmissionScope|AdmissionMode' pkg/scheduler/preemption/*.go` → zero non-test hits).

With only `low` and `high` in use and `withinClusterQueue: LowerPriority`, every interactive session can evict any user's batch job on priority alone. `medium` (100,000) exists and is referenced nowhere.

This is a **policy decision for the platform owners**, not something to change unilaterally. The three coherent options:

1. **Introduce a protected batch tier.** Give long-running or checkpointless batch `medium`. Cheapest, no upgrade, but leaves the channel open below `medium`.
2. **`withinClusterQueue: Never`.** Fully non-preemptive plane; batch waits for quota instead of being evicted. Removes the channel entirely — but also removes a user's ability to start a notebook when batch has filled the cluster, which is a real operational need.
3. **`preemptionGates` (v0.19+).** Purpose-built for exactly this: decouples *"this workload is hard to evict"* (priority — protects notebooks) from *"this workload may evict others"* (the gate — the gaming vector). Stamp a closed gate on every interactive Workload; keeps `high` priority for defence while removing offence. Caveat: enforcement at `scheduler.go:436` is currently reachable only behind the `ConcurrentAdmission` or `MultiKueueOrchestratedPreemption` feature gates — verify before designing on it.

### F9 — single flavor, no node labels (medium)

`prod.resourceFlavors.yml` defines `default` with no `spec` at all — no `nodeLabels`, no `tolerations`. Consequences:

- **Admission is decoupled from node fit.** Kueue admits against cluster-wide quota. A GPU workload can be admitted when 112 GPU-units of quota are free but no single GPU node has room, then sit Pending — *and it accrues fair-share the whole time*, because `GetAdmittedUsage()` counts admitted, not running. A user is charged for a session that never started, and skaha shows them "Pending" with no explanation.
- One `ephemeral-storage: 99200Gi` pool spanning heterogeneous nodes is meaningless as a fit signal.
- If GPU nodes are tainted, Kueue adds no tolerations; skaha must, and Kueue's accounting is blind to whether it did.

The fix is a flavor taxonomy — a `cpu` flavor and a `gpu` flavor with `nodeLabels`, GPU in its own `resourceGroup` (each resource may belong to exactly one group). Deferred to a later phase because it needs node-label ground truth I could not verify from the repo, and because it changes quota shape on both ClusterQueues simultaneously.

### F10 — no drain runbook, and deletion is unrecoverable (medium)

`stopPolicy` enum is `None | Hold | HoldAndDrain`. `Hold` stops admitting new workloads while leaving admitted ones running — the correct lever for node maintenance or a Kueue upgrade. Nothing in the repo documents it, so the reflex under pressure will be to delete and re-apply objects.

That reflex destroys the ledger. The LocalQueue `Delete` handler purges both `AfsConsumedResources` and `AfsEntryPenalties`, and the only durable copy of the history is the LocalQueue's own `.status` subresource, which dies with the object. **This is unchanged at v0.19.0** — the #12891 fix did nothing for the delete path. If CANFAR moves to per-community (or per-user) LocalQueues, those objects become persistent state: never pruned, never recreated, included in etcd backup scope. A GC job reaping "idle" LocalQueues would hand out self-service fair-share resets.

Deliverables: a `docs/runbooks/kueue-drain.md`; Argo CD `Prevent deletion` / a finalizer on LocalQueue objects; and a check that `argocd/applications/*/kueue/staging.yaml` `syncPolicy.automated.prune: true` cannot reach LocalQueues on a path rename. (Today the `include: '{localQueues/prod.cadc.yml}'` glob means a file rename would prune every LocalQueue in it.)

### F11 — controller sizing against a hot API server (medium)

`qps: 64 / burst: 128` with `groupKindConcurrency: 64` across six kinds, against a control plane measured at ~10 writes/s sustained, ~100ms per write floor, etcd fsync degraded 2ms→15ms, and a `assignment.misc.projectcapsule.dev` webhook failing on the write path. `qps` is a ceiling rather than a target, and the April 2026 benchmark showed Kueue was *not* the bottleneck (96.1% vs 95.3% completion) — so this is not currently causing harm.

Recommendation is **measure, don't tune blind**: alert on `rest_client_rate_limiter_duration_seconds` for the Kueue client and on apiserver admission-webhook latency, and lower to `32/64` only if the limiter is actually saturating while the Capsule webhook is still broken. Meanwhile F1's `5m → 15m` sampling change removes two-thirds of the LocalQueue status writes for free — at four LocalQueues that is small, but it scales linearly and matters if F4's per-community routing lands.

### F12 — framework coverage is a bypass, not a gap (medium)

`integrations.frameworks: [batch/job]` is correct today: skaha submits interactive sessions and headless jobs alike as `batch/Job` with `spec.suspend: true`, so interactive **is** covered. The risk is what happens when someone adds JobSet, RayJob/RayCluster, MPIJob or PyTorchJob to the platform: with no matching integration, no Workload object is created, no quota is consumed, and no fair-share is charged. The workload runs at full speed, free. Combined with `manageJobsWithoutQueueName: false`, a bare `batch/Job` submitted directly into `canfar-workloads` without the queue-name label does the same.

Keep the list minimal, but close the hole with an admission policy (Capsule / Kyverno / VAP) rejecting non-`batch/Job` workload kinds and label-less Jobs in `canfar-workloads` and `canfar-src-workloads`. That converts a silent bypass into a loud rejection.

### F13 — `localQueueMetrics` is a non-finding (low)

```go
// pkg/features/kube_features.go @ v0.17.0
LocalQueueMetrics: {
    {Version: "0.10", Default: false, PreRelease: Alpha},
    {Version: "0.17", Default: true,  PreRelease: Beta},   // <- graduated
},
// apis/config/v1beta2/defaults.go:77
cfg.Metrics.LocalQueueMetrics = cmp.Or(cfg.Metrics.LocalQueueMetrics, &LocalQueueMetrics{Enable: true})
```

All 17 `kueue_local_queue_*` metrics are already emitted for all four LocalQueues. At four LocalQueues the cardinality is trivial. (Note `kueue_local_queue_admission_fair_sharing_usage` — the scalar the scheduler actually sorts on, penalty included — is **v0.19-only** and does not exist at v0.17.x, so it is not available today at any version CANFAR can reach with a patch bump.)

Worth adding the block explicitly, purely so the next reader knows it was considered, and so the selector is pre-written before F4 multiplies the LocalQueue count:

```diff
 metrics:
   bindAddress: :8443
   enableClusterQueueResources: true
+  # LocalQueueMetrics is Beta/default-true since v0.17; stated explicitly so the
+  # cardinality decision is visible. Uncomment the selector before moving to
+  # per-community/per-user LocalQueues — the five *_wait_time_seconds histograms
+  # are ~17 series x priority_class each, per LocalQueue.
+  localQueueMetrics:
+    enable: true
+    # localQueueSelector:
+    #   matchLabels:
+    #     canfar.net/metrics: "true"
```

### F14 — the Cohort is fine (low)

`spec: {}` is correct. `fairSharing.weight` on a Cohort only matters when the Cohort has a parent (it is a share against siblings); `canfar` is root. `resourceGroups` at the Cohort level would be an *additional* shared pool on top of the ClusterQueues' quota — CANFAR has none, and inventing one would double-count.

Neither ClusterQueue sets `spec.fairSharing.weight`, so both default to 1. That is defensible: `weightedShare` is measured on usage *above nominal quota* normalised by cohort lendable, so cadc's large nominal already protects it without needing a weight. Worth stating explicitly in a comment rather than leaving it to inference.

One option worth a staging experiment, not a recommendation: if GPUs are meant to be genuinely *shared* rather than cadc-owned, move `nvidia.com/gpu: 112` to the Cohort as a shared pool and set both ClusterQueues to 0. That removes the src-borrows-from-cadc asymmetry at its root and makes GPU contention a pure fair-share question. It changes reclaim semantics materially (both queues become permanent borrowers), so it needs a staging soak before anyone proposes it for prod.

### F15 — `BestEffortFIFO` is correct (low)

Under AFS the queue head is the workload from the lowest-usage LocalQueue. With `StrictFIFO`, one un-admittable large workload — a 7-GPU flex session, say, given the deployed `limitRange` max of `nvidia.com/gpu: 7` — would head-of-line block every other user's small jobs indefinitely, which defeats AFS entirely. `BestEffortFIFO` is the right choice; it just needs a comment so nobody "corrects" it later.

### F18 — the per-user answer is already sitting there (info)

`labelKeysToCopy` already propagates `canfar.net/username`, `canfar.net/community`, `canfar.net/project` and `canfar.net/kind` onto **every** Workload object. This is the one part of the config that is unambiguously right and entirely unexploited.

It means the platform owners' second question — *"we have to report a statistic to a user, e.g. their fairshare value"* — is answerable **without** per-user LocalQueues and without the object-count explosion that was already refuted. The metrics service lists Workloads, groups by `canfar.net/username`, and runs the identical EWMA with the identical constants Kueue uses:

```
consumed_new = consumed_old·(1−α) + usage_now·α,   α = 1 − 0.5^(elapsed/halfLife)
usage        = Σ_r weight_r · consumed_r  /  weight_lq
```

Reusing CANFAR's own `usageHalfLifeTime` / `resourceWeights` keeps the user-facing number on the same scale as the one the scheduler sorts on. Because it charges from Workload objects regardless of `canfar.net/kind`, it satisfies the governing constraint — interactive is charged to the same ledger as batch — by construction, and it sidesteps the truncation bug in F1 entirely, since CANFAR would be doing the arithmetic in float rather than int64 milliunits.

Present it to users as a **ratio**, never an absolute: *"your 14-day weighted usage is 1.8× the community median; work from lower-usage users is admitted first."* Admission order is purely relative (`cmp.Compare`, lowest first), so an absolute "consumed CPU-equivalents" figure is meaningless without the peer comparison. Pair it with the half-life so decay is legible.

Do **not** surface `LocalQueue.status.fairSharing.weightedShare`. No controller writes it — only the ClusterQueue and Cohort controllers assign `WeightedShare` — and because it is a `+required` field it serialises as a permanent `0`. It is the field whose name most sounds like the answer.

While editing, drop the four superseded legacy keys (`canfar-net-sessionID`, `canfar-net-sessionName`, `canfar-net-sessionType`, `canfar-net-userid`) once skaha confirms nothing reads them — labels are copied only at Workload creation and never updated, so the migration is a clean cutover.

---

## Rollout order

Each step has an evidence gate that must pass before the next begins. Steps 0–3 are independently revertible; step 4 is not.

**Step 0 — correct the record (no cluster change).**
Update `docs/research/kueue-fairshare-tenancy.md` §A.1 (`fairSharing.enable` does not exist in v1beta2; cohort FS is already on) and §B.2 (`LocalQueueMetrics` is Beta/default-true at v0.17). Add a banner noting the doc targets v0.16.x while prod runs v0.17.0.
*Gate:* reviewed by whoever proposed the `enable: true` change, so the refutation is understood before anything ships.

**Step 1 — bump to v0.17.8 (F6).**
Patch bump only; no API or config-schema change. Drain with `stopPolicy: Hold` first (writing the F10 runbook is a prerequisite, not a follow-up).
*Gate:* controller stable ≥ 72h; `kubectl get lq -A -o jsonpath='{...consumedResources}'` shows **no step discontinuity** across the restart. That single observation is the direct proof that #12891 landed. Record `kubectl get pods -n kueue-system -o wide` restart counts as the baseline for how much the half-life ever meant.

**Step 2 — kill the dead and unbounded config (F2, F7).**
Delete `borrowWithinCohort` from both ClusterQueues; add explicit `borrowingLimit` to every resource on both. Ship `borrowingLimit` to **staging first** — this is the only step that can cause a user-visible admission refusal.
*Gate:* `kueue_cluster_queue_borrowing_*` / `ClusterQueue.status.flavorsUsage` show src capped at its limit; no rise in `kueue_local_queue_unadmitted_workloads` beyond the expected src-GPU refusals; zero preemption events with reason `InCohortReclamation` that were previously succeeding. **Explicitly confirm** with the owners that "src gets zero GPUs" is the intended policy before shipping `borrowingLimit: "0"` on GPU.

**Step 3 — retune AFS (F1, then F5).**
Apply the half-life and sampling change **first, alone** (`336h/5m → 168h/15m`), in both `controller.config.yml` and the embedded copy in `prod.controller.yml`. Let it run one full half-life.
*Gate:* this is the critical measurement of the whole exercise. Park a **known** GPU workload — e.g. exactly 4 GPUs in one LocalQueue — and read `status.fairSharing.admissionFairSharingStatus.consumedResources['nvidia.com/gpu']` after ~24h. Predicted: **≈2.91** at the new settings versus **≈0.000** at the current ones. If it reads 0, the truncation model is wrong and everything downstream of it must be re-derived before proceeding.
Only after that gate passes, apply the `resourceWeights` change. Weights multiply a truncated value, so shipping them first proves nothing.

**Step 4 — make AFS actually arbitrate (F4).**
Per-community LocalQueue routing in skaha (`canfar.net/community` → LocalQueue), plus LocalQueue manifests for every community, plus relaxing skaha's boot-time 404 check. This is the step that turns everything above from a tuned-but-idle mechanism into a live one. Requires a skaha release, so it trails the config work.
*Gate:* `kueue_local_queue_admitted_active_workloads` shows non-zero on more than one LocalQueue per ClusterQueue, and two LocalQueues report **materially different** `consumedResources`. Until that is true, AFS is decorative.
Treat the new LocalQueues as persistent state from day one — deletion protection in place *before* first traffic, per F10.

**Step 5 — the policy decisions (F8, F9, F14).**
Priority ladder, flavor taxonomy, and the optional cohort-owned GPU pool. Each needs an owner decision and a staging soak; none should be bundled with the mechanical fixes above.

---

### One-line summary for the owners

Cohort Fair Sharing is already on and does not need enabling — adding `enable: true` would crash the scheduler. What actually needs fixing is that the GPU fair-share ledger reads zero because of an integer-truncation interaction with the 14-day half-life; that Admission Fair Sharing is currently arbitrating nothing because every workload lands in one LocalQueue per ClusterQueue; that `src` can hold all 112 of `cadc`'s GPUs with no cap and no working reclaim; and that the deployed v0.17.0 throws the whole ledger away on every controller restart.

