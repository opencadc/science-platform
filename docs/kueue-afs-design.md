# CANFAR fair share — design

**Status:** Design, for review with the user representative group · **Updated:** 2026-08-14
**Companions:** [technical reference](kueue-afs-technical.md) · [user guide](kueue-afs-userguide.md) · [KEP-77 upstream proposal](KEP-77.md)

**Target platform.** Kubernetes **≥ 1.33** (in-place pod resize) and Kueue **≥ v0.19.1**
(released 2026-08-12; carries the sub-milli precision fix, so the fair-share ledger records all
resources — including GPUs — exactly, and victim selection is ledger-aware), with
`waitForPodsReady` disabled. Everything in this document assumes that baseline.

---

## 1. How fair share works here

CANFAR is shared. When it is busy, waiting work is started **in order of who has used the least
recently** — not first-come-first-served. This section defines the machinery precisely; everything
else in the document builds on it.

### 1.1 Credits — one currency for all hardware

Different jobs hold different mixes of hardware, so everything is converted to one unit, the
**credit**, using per-resource weights. Weights are set from a **target share of the pool** — decide
what fraction of total fairness weight each resource class should carry, then derive
`weight_r = (pool × target_r) / capacity_r`, anchored at `cpu = 1.0`. (Setting weights by unit
equivalence — "1 GiB ≈ 1 core" — silently hands most of the pool to memory, because clusters ship
several GiB per core.) Illustrative values used throughout this document:

| held | credits per hour held |
|---|---|
| 1 CPU core | 1 |
| 1 GiB memory | 1 |
| 1 GPU | 35 |

**You are charged for what you reserve, not what you use.** A 4-core request that runs one thread
still costs 4 — nobody else could have had the other three.

### 1.2 The ledger — an exponentially weighted moving average

Each user has one **ledger** per community they belong to. (Mechanically it is a Kueue *LocalQueue*
per `(user, community)` pair; the scheduler's own accounting object is our ledger — we never keep a
second set of books.)

The ledger is an **EWMA — exponentially weighted moving average** — of the credits the user is
*holding*, sampled on a fixed clock. Every sampling interval **Δt**, the ledger `U` is updated:

```
U ← (1 − α) · U + α · h          h = credits currently held
α = 1 − 0.5^(Δt / H)             H = the half-life
```

Two properties follow directly from this formula, and both matter:

- **`U` converges to what you hold.** Hold 40 credits steadily and `U` climbs toward 40 — it is a
  *time-averaged concurrency*, in credits, not an accumulating total. After 1 h, `U ≈ 0.23`; after
  1 day ≈ 5.2; after 5 days ≈ 20; held forever → 40.
- **Old usage fades on the half-life and only the half-life.** Stop holding, and `U` halves every
  `H`. The sampling interval Δt cancels out of the decay exactly — it sets how often the books are
  written, never how fast they forget.

| time since a burst of usage | still counted (H = 5 days) |
|---|---|
| 1 day | 87 % |
| 3 days | 66 % |
| **5 days** | **50 %** |
| 1 week | 38 % |
| 2 weeks | 14 % |
| 4 weeks | 2 % |

![decay family](kueue-decay-family.png)

**Ordering rule:** when capacity frees, the waiting workload from the **lowest-ledger** user is
admitted first. That single rule is fair share; the rest of this document is what we build around
it.

### 1.3 Parameters — normative values

| parameter | value | why |
|---|---|---|
| `usageHalfLifeTime` (**H**) | **120 h — 5 days** | Long enough to span a multi-day campaign, so work cannot be cycled faster than the ledger sees it; short enough that a heavy week is half-forgiven in five days and effectively clear inside three weeks. |
| `usageSamplingInterval` (**Δt**) | **5 m** | Resolution and write-cadence only (see §1.2); short keeps the displayed number fresh. |
| **α** (derived) | 1 − 0.5^(300/432 000) ≈ **4.81 × 10⁻⁴** | The per-tick blend factor. |
| Session **TTL** | **7 days, explicit renewal** | **Decided.** The re-contest interval: the longest a session may be held without the holder saying they still need it (§3.3). Feedback on consequences is requested from the user group (§8). |
| Guaranteed-session **floor** | per community, e.g. **128 credits** | The allotment below which a user's session is admitted through the guaranteed tier (§3). |
| Standing **bands** | five, percentile-cut 2.5 / 13.5 / 34 / 34 / 16 % | §6. |
| Entry penalty | α × requested credits | Charged at every admission (§2). |
| Pending-age **SLO** | p99 ≤ **H**; alert at H, page at 2 H | Waiting is bounded by decay; the SLO verifies it (§5). |
| Controller `qps` / `burst` | sized to ledger population | A capacity input, not a constraint; raise it rather than coarsening Δt. |

At the expected ceiling of **~1 000 users** (≈ 1 000–1 500 ledgers), the 5-minute clock costs 3–5
small status writes per second — unremarkable, and the ledger population is comfortably manageable.

**H is a one-way door.** The stored ledger's meaning is *defined* by the half-life; changing H
rescales everyone's standing with no migration. It is set before the user-facing display ships, not
after.

---

## 2. What is charged, and what escapes

Charging is automatic and continuous: the ledger samples what each user *holds*, every Δt, for the
whole life of every workload — interactive session and batch job identically. One ledger, no
exemptions. On top of the sampling, every admission pays an immediate **entry penalty** of
`α × requested credits` — exactly one tick's worth — so work too brief to be sampled is still
charged. Sub-interval work is therefore slightly *over*-charged relative to its true hold time;
this is deliberate anti-burst design from Kueue. A session killed after three seconds pays the same
as one held for a full interval: **spawn-and-kill is not free.**

| situation | charged? | note |
|---|---|---|
| Held for hours or days | ✅ | ledger converges to held credits |
| Lives for seconds | ✅ one tick's worth | entry penalty |
| Admitted but pod never schedulable | ✅ in full | charged while the user sees "waiting" — mitigated by per-class resource flavors (§8 Q1) |
| Rejected before admission | ❌ | correct — nothing was held |
| **Job submitted without a queue label** | ❌ **runs free** | design gap — §7 |
| **Non-`batch/Job` kinds** (JobSet, Ray, MPI) | ❌ **runs free** | design gap — §7 |

---

## 3. The two tiers: guaranteed, and everything else

**Definitions.** *Accounting* is who gets charged — settled above: everyone, identically.
*Actuation* is what the system **does** about the resulting standing — which workload starts next,
what may displace what, and when held capacity is returned. Accounting is uniform; actuation
differs by tier: a small guaranteed allotment is protected absolutely, and beyond it everything —
notebook and batch job alike — competes equally and is equally displaceable.

### 3.1 The tier table

| class | `WorkloadPriorityClass` | may preempt | may be preempted |
|---|---|---|---|
| **guaranteed session** | `high` | the normal pool — in its own community, and cross-community borrowers | **never** |
| **normal — interactive & batch alike** | `low` | nothing | by guaranteed sessions |

Two values, two tiers, nothing else. Preemption only ever targets *strictly lower* priority, and
every normal workload carries the same priority — so **nothing in the normal pool can preempt
anything**, structurally. There is no "spawn interactive to jump the line": interactive holds no
privilege over batch, and skaha's stamping is binary — the floor rule (§3.2) passes → `guaranteed`,
otherwise `normal`. The deal is *"do what you like with your guaranteed allotment; beyond it,
everything competes equally and is equally displaceable."*

When a guaranteed session must make room, victims are the **heaviest-ledger user's** workloads
first, then their **most recently admitted** — so displacement lands on batch statistically, since
recent admissions are overwhelmingly batch churn. A notebook *can* be displaced, when its owner is
the heaviest active user with nothing newer running; three bounds keep that rare and small: at most
one floor's worth of credits, on the heaviest user first, and every session already bounded by the
7-day TTL. Across communities the same priority gap lets a guaranteed session reclaim capacity from
another community's borrowers — the floor holds even when neighbours have borrowed deep — while
normal work cannot, so an explicit borrowing cap remains the control there.

### 3.2 The guaranteed session

Every community configures a **floor** — an allotment, in credits, that any member can claim at any
time regardless of their ledger. A 128-credit floor covers, for example, an 8-core/64-GiB session
(72 credits) or a 1-GPU/4-core/36-GiB session (75 credits), in any combination.

At submission, skaha stamps the tier:

```
guaranteed  ⇔  (credits currently held + credits requested) ≤ floor   AND   ledger U < floor
```

The first term is instantaneous — skaha's own session inventory, desktop-apps included — and closes
stacking, since the ledger lags holdings by design. The second brings memory: a user who has recently
burned far past the floor must decay back under it before claiming again.

A guaranteed session is admitted first (an under-floor user has a low ledger, so fair-share ordering
already favours them), may **preempt the normal pool** when the cluster is full — victims drawn from
the heaviest-ledger users first — and, once running, **cannot be preempted**. This is what makes
"come in each morning and start working" a property rather than a hope.

**The guarantee is statistical, and sized to be so.** Each community sets its floor such that
`floor × expected concurrent claimants ≤ community nominal quota` — a 22 000-credit community
supports ~170 simultaneous 128-credit claims. Because guaranteed sessions may displace the whole
normal pool, the guarantee can fail only when the cluster is saturated with *other guaranteed
holdings*, which the sizing rule precludes as long as concurrent claimants stay within the
assumption. That assumption is the one honest limit, and it is stated to users.

### 3.3 The session TTL — why 7 days, and why it is load-bearing

A session that is admitted once and renewed forever is capacity acquired once and never
re-contested — fair share would then govern session *starts* only, and a user who never lets go
would never be subject to it. The TTL is the **re-contest interval**: 7 days, then an explicit
one-click renewal. It is not a limit on how long you may work — it is a limit on how long hardware
may be held *without the holder saying they still need it*. Data on persistent storage is
untouched; only the session process recycles. Paired with idle culling on real activity (kernel
busy, terminal input, GPU utilisation — an open browser tab is not activity).

---

## 4. Flexible sessions — resize instead of over-provision

Asking users to guess their peak up front, then decoupling the charge from the ceiling, is the
classic failure of flexible profiles. Sessions here are **resizable** instead, built on Kubernetes
in-place pod resize (≥ 1.33):

| resource | resize | who drives it | charged? |
|---|---|---|---|
| **CPU** | automatic | at 80 % sustained utilisation, grow **+25 %**; decay back toward baseline when utilisation falls | **no — deliberate subsidy** |
| **RAM** | manual | **up/down arrows in the session UI** — no automatic growth | **yes** |
| **GPU** | never | devices cannot be resized in place | n/a |

The asymmetry is the point. **CPU is compressible** — over-committing it degrades speed, not
correctness — so bursty CPU is subsidised. **Memory is not**: let a process reach 32 GiB against a
16 GiB reservation and the node is 2× overcommitted on RAM, where the failure mode is the OOM
killer terminating *somebody else's* session. So RAM growth is deliberate — a human presses the
arrow — and charged.

### 4.1 How a resize is charged — the delta Workload

Kueue prices what it *admitted*; a pod resized afterwards keeps its admission-time charge, and
neither the admitted Workload nor the ledger can be edited to correct it — both are sealed by
Kueue's admission webhook, and the ledger is written from Kueue's own accumulator every tick and
never read back (verified; details in the technical reference).

The mechanism is therefore a **companion Workload per resized session** — a plain Kueue object,
created directly with no pods behind it, in the user's own ledger, requesting exactly the excess
over the admitted session. Kueue treats it as a first-class admission: it **charges the ledger
every tick identically to a running session, reserves real quota, pays the entry penalty, and is
refused when quota is short.** All of this was verified live on a test cluster — a podless delta was
charged at a ratio of **2.00 to three digits** against a real pod of the same size, reserved quota
within seconds, and an oversized one was refused with *"insufficient unused quota … 1 more needed."*

```yaml
apiVersion: kueue.x-k8s.io/v1beta2
kind: Workload
metadata:
  name: resize-<session-id>
  ownerReferences: [ <the session Job> ]           # garbage-collected with the session
spec:
  queueName: u-jdoe-cadc                          # the user's ledger
  priorityClassName: normal
  podSets:
    - name: delta
      count: 1
      template: { spec: { containers: [ { name: delta, image: none,
                  resources: { requests: { memory: 8Gi }, limits: { memory: 8Gi } } } ] } }
```

Three rules govern it:

- **Exactly one delta per session, always stating the whole excess, replaced on every change.**
  Its size cannot be edited in place (Kueue rejects it), so every resize — up or down — is *delete,
  then recreate at the new total*: measured at 0.1 s, atomic for quota. Idempotent from any state;
  one object to garbage-collect.
- **RAM growth is fail-closed.** skaha creates the delta first and patches the pod only once it
  admits; if it cannot admit, the arrow is refused with that queue-aware message. Growth respects
  quota and fair share by construction, and there is no window in which grown RAM is held but
  unaccounted.
- **Nothing bypasses admission — that is the point.** The delta competes at `normal` priority like
  any workload. A guaranteed-tier session growing within its floor stamps its delta `high` and
  preempts the normal pool exactly as a floor claim does; no special path exists or is wanted.

CPU auto-growth is not charged (policy) — the reconciler that keeps deltas in step with pods merely
records that drift, and charging it later is a configuration flip on the same loop. Two operational
requirements: `waitForPodsReady` stays disabled (a podless Workload would otherwise be evicted on
timeout), and admission policy in the workload namespaces must allow these objects from the
platform's service account.

**Upstream.** Vertical resize is an explicit non-goal of Kueue's elasticity work and out of scope
for its alternative proposal ([kueue#5897](https://github.com/kubernetes-sigs/kueue/issues/5897));
nothing is in flight. The delta Workload is the plan of record; the upstream proposal is documented
in [`KEP-77.md`](KEP-77.md) and is out of scope here.

---

## 5. Waiting is bounded by the half-life

There is no separate anti-starvation mechanism, because the ledger's decay *is* one, and the
argument is short:

- **A pending workload accrues nothing.** A heavy user who waits simply decays — halving every
  5 days — while every active competitor's ledger is propped up by what they are running. Relative
  standing therefore always drifts *toward* the waiter.
- **Waiting a full half-life halves the debt.** After H a heavy ledger has fallen to the level of a
  moderate one; after 2H it is light. Sustained starvation would need an unbroken supply of
  strictly-lighter users absorbing every freed slot for weeks — implausible with gated community
  membership, ~1 000 users, and a diurnal cycle that empties the line most nights.
- **The floor already covers the case that matters humanly**: the morning interactive session
  starts regardless of standing (§3.2). What fair share rations for an over-share user is *surplus
  batch throughput* — which is exactly what it is for.

The commitment is therefore an SLO, not a mechanism: **p99 pending age ≤ H (5 days)**, alerting
operators on violation and paging at 2 × H. If it is ever sustainedly violated, the evidence will
say why, and a bounded intervention (promoting workloads no larger than the floor, one per user)
is the prepared response — built on evidence, not in advance.

## 6. What the user sees

### 6.1 Standing: five bands, one metaphor

The user-facing standing is a **place in the line**, computed as a percentile rank among the
community's *active* members (running or pending work, or a non-trivial ledger), best standing
first, cut on the normal curve:

| band | share of active peers | badge |
|---|---|---|
| `next` | top 2.5 % | **Next in line** |
| `front` | next 13.5 % | **Near the front of the line** |
| `middle` | middle 34 % | **In the middle of the line** |
| `behind` | next 34 % | **Toward the back of the line** |
| `lagging` | bottom 16 % | **At the back of the line** |

**The no-line override:** when the user has nothing pending and the community's queue is empty, no
band is shown — the display reads **"no line — jobs start immediately."** A band describes your
place in a line that exists.

The only number in the copy is the user's ratio to their own share ("about 1.9× your share
recently"), always paired with the decay rule ("it halves every 5 days") — that pairing is what
turns a multi-week penalty from a mystery into a rule.

### 6.2 Position, and what is never shown

For pending work the user sees their position in the community line (cached on the sampling clock,
clamped so it never moves backwards) and a one-line reason from a fixed vocabulary. Two things are
deliberately never shown: a **start-time estimate** (queue ETAs on shared clusters are wrong at the
minute scale far more often than right, and a confident wrong promise is worse than none) and the
**raw scheduler internals** (weights, per-resource ledgers — they invite optimising the formula
rather than the work; they remain visible to administrators).

Per-user data is served as a `FairShareStanding` object — a Kubernetes-style declarative shape,
controller-ready — specified in the technical reference §7.

---

## 7. Design gaps

Known holes, stated plainly, each with its closure:

| # | gap | consequence | closure |
|---|---|---|---|
| 1 | **Label-less or non-`batch/Job` workloads run free.** A Job submitted without the queue label, or any JobSet/Ray/MPI kind, is invisible to quota and ledger. | Complete fair-share bypass for anyone with direct namespace access. | Admission policy rejecting label-less Jobs and non-Job kinds in workload namespaces. |
| 2 | **Desktop-apps bypass the session caps.** They are real Kueue workloads with user-chosen resources, but skaha's session-count check excludes them. | Cap and floor arithmetic undercount what a user holds. | Count desktop-apps in the session inventory used by caps **and** the §3.2 floor rule. |
| 3 | **A large influx of new users all start with empty ledgers.** A teaching cohort of 100 arrives with the best standing on the platform, simultaneously. | A semester class can displace working researchers for days. | The floor + per-user caps bound each newcomer; community sizing (§3.2) bounds the aggregate; if needed, a dedicated teaching community with its own quota isolates the cohort entirely. |
| 4 | **Ledger deletion is unrecoverable.** Deleting a ledger object (LocalQueue) permanently erases that user's history — at every Kueue version. | An automation accident (e.g. GitOps pruning) silently resets fair share platform-wide. | Deletion protection on ledger objects before first traffic; ledgers excluded from GitOps prune scope; never garbage-collect idle ledgers. |
| 5 | **A user in N communities holds N independent ledgers.** Heavy use in one community does not affect standing in another. | Bounded by design, not exploitable across communities: each community's quota caps total consumption inside it, and community *membership* is the gate. This mirrors how multi-allocation schedulers behave everywhere and is accepted — the control point is membership approval, not the scheduler. | Accepted; monitor cross-community concentration. |
| 6 | **In-place CPU growth is uncharged** (§4). | Bounded subsidy on the compressible resource. | Accepted by policy; a configuration flip charges it; upstream fix proposed in `KEP-77.md`. |

---

## 8. Open questions for the user representative group

Each is a real policy call with a recommendation to react to.

**Q1 — Should GPUs be scheduled as a separate pool?** GPU nodes are effectively a disjoint cluster
(non-GPU work does not run there). Three separation levels: **(A)** a GPU `ResourceFlavor` with
node labels — a correctness fix (today a GPU workload can be admitted against quota when no GPU
node has room, then charged while "waiting"); recommend unconditionally. **(B)** a separate GPU
ClusterQueue — GPU queueing gets its own policy, but **splits the ledger**: GPU-heavy users regain
full CPU standing, ending cross-resource fairness. **(C)** full isolation — GPU capacity idle
whenever GPU users are. The sharp question for the group: *should a user who has monopolised GPUs
for a week still get normal priority for CPU-only work?* If no — keep one queue (A only).

**Q2 — The 7-day session TTL is decided; what breaks?** Not a consultation on the number — a
request for consequences: *tell us which workflows a 7-day explicit-renewal limit damages, and
how.* Renewal is one click; storage is untouched; the alternative to a TTL is that the platform's
only defence against a forgotten GPU desktop is the owner's memory.

**Q3 — Should standing be visible to peers?** Recommendation: **no.** Each user sees their own
band and position; community administrators see the ranking. A public leaderboard converts a
scheduling mechanism into a social one.

---

## 9. Sequencing and build list

| step | where | what |
|---|---|---|
| 1 | platform | Kubernetes ≥ 1.33; Kueue ≥ v0.19.1; `waitForPodsReady` off |
| 2 | Kueue config | `H = 120h`, `Δt = 5m`; weights from target shares; `withinClusterQueue: LowerPriority`; priority classes per §3.1 |
| 3 | skaha | per-`(user, community)` ledger routing (queue chosen by user identity, not session type); ledger objects created on first submission, never deleted |
| 4 | skaha | floor rule at submit (§3.2): session inventory **including desktop-apps** + ledger read → binary tier stamp |
| 5 | skaha | 7-day TTL + renewal + idle culling on real activity |
| 6 | skaha + controller | RAM arrows (delta-Workload-first, fail-closed) + resize reconciler mirroring pod reality (needs RBAC on `workloads.kueue.x-k8s.io` and `pods/resize`); CPU auto-resize uncharged |
| 7 | metrics service | pending-age SLO: alert p99 > H, page > 2 × H (§5) |
| 8 | policy | admission policy closing gap #1; deletion protection closing gap #4 |
| 9 | metrics service | `FairShareStanding` route; bands; position cache (one ClusterQueue-scoped poll per interval) |
| 10 | docs | `KEP-77.md` upstream proposal (out-of-scope task, tracked separately) |

Gates between steps, config sketches, RBAC, caching, and the API contract: technical reference.

---

## 10. Related documents

- **[`kueue-afs-technical.md`](kueue-afs-technical.md)** — implementation reference: exact Kueue
  semantics, the statistic's computation, serving, caching, API contract, review record.
- **[`kueue-afs-userguide.md`](kueue-afs-userguide.md)** — the science-user guide.
- **[`kueue-keel-deploy-audit.md`](kueue-keel-deploy-audit.md)** — point-in-time audit of the
  currently deployed system; several findings remain live until the platform upgrade ships.
- **[`research/kueue-fairshare-tenancy.md`](research/kueue-fairshare-tenancy.md)** — the original
  research record.
