# Fair Share on the CANFAR Science Platform

> **Draft for user-group review.** Numbers reflect the current proposal: a **5-day** half-life, a
> **7-day session limit with one-click renewal**, and five standing bands. Please read with the
> design document's open questions in mind.

*A guide for science users, and for the people who run communities on the platform.*

Read time: about ten minutes. If you only read one section, read the next one.

---

## The short version

CANFAR is shared. There is not enough hardware for everyone to run everything at once, so when the platform is busy your job waits its turn.

**Turns are not handed out first-come-first-served. They go to whoever has used the least recently.**

That one sentence explains almost everything you will notice:

- Someone who submitted *after* you can start *before* you, if they have been using less than you have.
- Your standing improves on its own as time passes, because recent use fades.
- An idle notebook or desktop session costs you standing for every hour it is open, because it is holding hardware nobody else can use.
- Fair share decides **the order of the line**. It does not make the line shorter and it cannot tell you when you will reach the front.

If you have used a university cluster running Slurm, this is the same idea as Slurm's fairshare factor with a decay half-life. If you have not: keep reading, it is simpler than it sounds.

> **Rollout note.** Fair share is being introduced in stages. Today the platform tracks usage per community group; tracking per person is the next step, along with the standing display described below. The rule and the reasoning are the same either way — this document describes how it works and what you should expect. Where something is not live yet, it says so.

---

## 1. Why is my job waiting, and what can I do about it?

### Why it is waiting

Your job is waiting for one of three reasons. In rough order of likelihood:

1. **The platform is busy and it is not your turn yet.** Other people are ahead of you in the line, because they have used less recently than you have.
2. **Nothing is free that fits.** Your request needs more of something — cores, memory, a GPU — than is currently unused. A small job would have started; yours has to wait for a big enough gap.
3. **Something is wrong with the request or the platform.** Your request is larger than the platform will ever grant, you have hit a limit set by your community, or a component is degraded. These are rarer, and they should be reported rather than waited out.

The platform tells you which of these applies. If your session sits in "waiting" with no other explanation, that is a bug and worth reporting.

### What determines your turn

Two things, and only two:

- **How much of the platform you have been holding recently.** Not how many jobs you submitted — how much hardware you occupied, and for how long. A big job held for a long time counts a lot. A small job held briefly counts very little.
- **How that compares to everyone else in your community who is currently active.** Your standing is always relative. If you are the only person working today, you are at the front of the line no matter what you did last week.

Recent use counts more than old use. Old use fades away. That is covered in section 3.

### What you can do about it right now

- **Make the request smaller if you can.** A job asking for 8 cores starts long before one asking for 200, because gaps that size appear far more often. Section 5 has the practical list.
- **Close sessions you are not using.** Every open notebook and desktop is being charged for the hardware it holds, whether you are typing in it or not.
- **Use batch for batch work.** A job that runs unattended and exits releases its share the moment it finishes. A notebook you left open does not.
- **Wait.** Your standing genuinely does recover on its own. It is not a queue you can be forgotten in.

What does **not** help: resubmitting the same job repeatedly, splitting one job into many identical copies, or opening extra sessions "to hold a spot". None of these move you up. Resubmitting in particular just puts the same request back at the same position.

---

## 2. How your turn is actually decided

### The analogy

Think of a shared telescope with a queue of observers. Time is not allocated by who asked first. The scheduler looks at who has had the most telescope time lately and puts them behind the people who have had less. Someone who has not observed in a month goes near the front. Someone who just finished a long run goes near the back — and works their way forward again as the weeks pass.

That is fair share. Two details make it precise.

### Detail one: everything is converted to one currency

Different jobs use different mixes of hardware, so the platform converts everything into a single unit so it can be compared. Call it a **credit**.

| What you are holding | Cost, per hour you hold it |
| --- | --- |
| 1 CPU core | 1 credit |
| 1 GiB of memory | 1 credit |
| 1 GiB of scratch disk | about 0.03 credits |
| **1 GPU** | **35 credits** |

A GPU costs 35 credits because it is roughly 35 times scarcer than a core. A community with 2,800 cores has only 112 GPUs. Pricing a GPU like a core would mean GPU users effectively paid nothing for the scarcest thing on the platform.

The important consequence: **you are charged for what you reserve, not what you use.** If you request 4 cores and your code only ever uses one, you are charged for 4. Nobody else could have had those other three.

Some typical sessions:

| Session | Cost per hour |
| --- | --- |
| Small notebook (2 cores, 8 GiB) | 10 credits |
| Medium notebook (8 cores, 32 GiB) | 40 credits |
| GPU desktop (1 GPU, 8 cores, 32 GiB) | 75 credits |
| Batch job (200 cores, 400 GiB) | 600 credits |

### Detail two: it is time-averaged, not a total

Your standing is not "credits you have ever spent". It is closer to **the average number of credits you were holding over the last couple of weeks**, with the most recent days weighted most heavily.

This is why duration matters as much as size:

| What you ran | Credits per hour | Held for | Total |
| --- | --- | --- | --- |
| Medium notebook | 40 | 3 days | 2,880 |
| 200-core batch job | 600 | 1 hour | 600 |
| 200-core batch job | 600 | 6 hours | 3,600 |
| GPU desktop | 75 | 7 days | 12,600 |

A notebook left open for three days costs about as much as five hours of a 200-core batch job. A GPU desktop left open for a week costs about as much as **twenty-one hours** of that same 200-core job. This surprises people, and it is the single most useful thing to internalise.

### A worked example

A community owns 2,800 cores, 12,400 GiB of memory and 112 GPUs. Converted to credits, that pool is worth about **22,000 credits** held continuously.

Suppose 20 members of that community are active this week. Each person's fair share is roughly 22,000 ÷ 20 ≈ **1,100 credits**. That is the reference line: hold about 1,100 credits continuously and you are using exactly your share.

| | Recent activity | Average hold | Compared to share | Standing |
| --- | --- | --- | --- | --- |
| **Ana** | one GPU desktop plus a couple of small notebooks | ~120 credits | 0.11× | **Near the front of the line** |
| **Ben** | a reprocessing run, 600 cores + 2,400 GiB, for 2 of the last 5 days | ~860 credits | 0.78× | **In the middle of the line** |
| **Cara** | the same size run, but held for 4 of the last 5 days | ~2,570 credits | 2.3× | **At the back of the line** |

When all three submit at the same moment into a busy platform, Ana starts first, then Ben, then Cara. Not because of who they are or what they submitted, but because of what each of them has been holding.

Note what this example does *not* say. Cara is not being punished, and nothing has been taken away from her. She used more than her share, so for a while other people go first. As her recent usage fades she moves back up.

### The five standing bands

Your standing is shown as a **place in the line**, ranked among the members of your community who
are currently active:

| Band | Meaning |
| --- | --- |
| **Next in line** | You have used the least among active members. Your work starts as soon as anything frees up. |
| **Near the front of the line** | You have used less than most. Your work starts before theirs. |
| **In the middle of the line** | Around the middle of the pack. Work starts in the usual order. |
| **Toward the back of the line** | You have used more than most active members recently. Lighter users go first for a while. |
| **At the back of the line** | You have used more than almost everyone recently. Expect noticeably longer waits until your recent usage fades. |

When there is no line at all — you have nothing waiting and neither does anyone else — no band is
shown. The display simply says **"no line — jobs start immediately."**

---

## 3. Why recent use counts more, and how fast it fades

Usage does not accumulate forever. It decays. What you did yesterday counts much more than what you did last month, and what you did three months ago counts for essentially nothing.

The decay is set by a **half-life** — **five days**. Every five days, half of any given burst of usage has faded from your standing.

| Time since a burst of usage | How much of it still counts |
| --- | --- |
| 1 day | 87% |
| 3 days | 66% |
| **5 days** | **50%** |
| 1 week | 38% |
| 2 weeks | 14% |
| 4 weeks | 2% |

![how usage fades](kueue-decay.png)

### What this means in practice

- **A big run today does not follow you around forever.** It follows you for about a month, fading the whole time.
- **Recovery starts immediately and is gradual.** There is no cliff, no reset day, no "your allocation refills on the 1st". The moment you stop holding hardware, your standing starts climbing.
- **You cannot game it by pausing briefly.** Stopping for an afternoon does almost nothing. The timescale is days to weeks.
- **A long campaign is genuinely expensive.** If you hold a large amount for a week, expect reduced standing for roughly the following two. That is not a bug — you used that much of somebody's share. Section 5 has advice on structuring long campaigns.

Two weeks is a deliberate choice: it is longer than the longest normal piece of work (so nobody can cycle campaigns faster than the system notices), and short enough that a single busy week does not define your whole term. Your community administrators can see the current value and it may be adjusted; if it changes, the platform will say so.

---

## 4. Why interactive sessions count too

This is the question people ask most, usually in this form:

> *My notebook was just sitting there. I wasn't running anything. Why did it cost me priority?*

**Because "just sitting there" is not free.** When you start a session that requests a GPU, that GPU is assigned to you and removed from the pool. It is not available to anyone else for as long as your session exists — whether you are running a training loop, editing a plot, or asleep. From the platform's point of view, and from the point of view of the person waiting for a GPU, an idle GPU session and a busy GPU session are identical: the hardware is gone either way.

Charging for held hardware rather than for work performed is the only measurement that is both fair and unambiguous. Anything else would require the platform to guess whether your kernel counts as "really working", and would penalise people whose work involves thinking.

### Why interactive is charged on the same ledger as batch

There is a second reason, and it is worth stating plainly.

If interactive sessions were exempt from fair share, the cheapest strategy for every user would be to stop submitting batch jobs entirely and do all their computing inside a long-lived notebook or desktop — where the platform can't see it, and where it costs nothing. Fair share would be meaningless within a week, and the people who would suffer most are the ones running honest batch workloads.

So there is one ledger, and everything you hold is on it. **A GPU held from a desktop session costs exactly what a GPU held from a batch job costs.** No exceptions, no separate pool, no "interactive doesn't count".

### What is different about interactive

The *accounting* is identical. The *treatment* is not.

Interactive sessions are much harder to interrupt than batch jobs — killing a notebook loses unsaved work and interrupts a person mid-thought, whereas a batch job can usually just be run again. So the platform does not use fair share as a reason to kill running sessions. Instead:

- Ordinary work never interrupts a running session — nothing in the everyday pool can displace anything else.
- The one narrow exception: when the platform is completely full, a member claiming their **guaranteed session** (a small, community-configured allotment that is always available) may displace work from the heaviest recent users. In practice that lands overwhelmingly on batch jobs, and it is bounded to the size of one guaranteed session.
- Interactive sessions have a **7-day time limit** with one-click renewal, and an **idle timeout**, so that a session which has been forgotten is returned to the pool rather than held indefinitely. Renewing takes one click; your files on persistent storage are never affected — only the session itself recycles.
- Your fair-share standing affects **when a new session starts**, not whether a running one keeps running.

The practical upshot for you: if you want a GPU and you want it soon, the best thing you can do is close the GPU session you finished with yesterday.

---

## 5. What fair share does not do

Being clear about this saves a lot of frustration.

**It does not create capacity.** If a community has 112 GPUs and 200 people want one, fair share decides who gets them first. It does not produce the 88th through 200th GPU. When the platform is genuinely full, everyone waits — fairly, but they wait. If waits are consistently long, the answer is more hardware or a different plan, not a better queue.

**It does not give you a guaranteed start time.** There is no estimate of when your job will start, and this is deliberate. Start-time predictions on shared clusters are notoriously wrong — published measurements on comparable systems put minute-accurate predictions in the single-digit percentages — and a confident prediction that is wrong is worse than no prediction. The platform will tell you your position in the line once you have waited long enough for that to be meaningful. It will not tell you a time.

**It is not an allocation or a budget.** Fair share is turn-taking, not entitlement. You do not have a quota of core-hours to spend and you cannot run out. Being "at the back of the line" is not a penalty, a strike, or a bill — it is a statement that you were recently ahead of other people and it is now their turn. If the platform empties out, a user at the back of the line starts immediately.

**It does not reach into running work.** Your standing affects the order in which waiting work starts. It does not shut down something that is already running.

**It does not distinguish good science from bad.** It cannot tell whether your job is an urgent deadline or an exploratory run. If something is genuinely urgent, talk to your community administrator — that is a human decision, and there are levers for it.

---

## 6. How to get your work done faster

Ordered by how much difference they actually make.

**1. Right-size your request.** This is the biggest lever by a wide margin, and it works twice: a smaller request finds a gap sooner *and* costs less standing. Before you ask for 32 cores, check whether your code actually uses them — many astronomy pipelines are single-threaded for most of their runtime, and asking for 32 cores to run one thread costs you 32× while giving you no speedup. The same goes for memory: request what your job peaks at plus a margin, not the largest number in the dropdown.

**2. Close sessions you are finished with.** A forgotten GPU desktop is the most expensive thing on the platform. Make it a habit to close sessions at the end of the day rather than leaving them for tomorrow — restarting takes a minute, and you will start further forward in the line.

**3. Don't request a GPU unless you need a GPU.** A GPU costs 35× a core. If you are writing code, inspecting data, or running anything that is not actually using the accelerator, use a CPU session and switch to a GPU session for the part that needs one.

**4. Use batch for batch work.** If your work is "run this script over these 4,000 files", submit it as a batch job rather than babysitting it in a notebook. Batch jobs release their share the moment they finish, they don't need you awake, and they can run overnight when the platform is quieter. A notebook running the same thing costs you the whole time it is open — including the hours after it finished while you were asleep.

**5. Split long campaigns into shorter pieces.** A single job holding 600 cores for four days costs the same as 96 jobs holding 600 cores for one hour each — but the short jobs fit into gaps, start sooner, interleave with other people's work, and are much cheaper to lose if something fails. Long, wide, indivisible jobs are the hardest thing for any scheduler to place.

**6. Spread big work out.** If you have a month of processing to do, running it over three weeks costs you far less standing at any moment than compressing it into four days, and your day-to-day interactive work will not be stuck behind your own campaign.

**7. Work when the platform is quiet.** Standing is relative to who is active. Evenings, weekends and holidays are genuinely faster.

**8. Talk to your community administrator.** They can see things you cannot — how much of the community's pool is in use, who is holding what, whether a limit is set unusually low. If you have a real deadline, or you think your waits are disproportionate, they are the right person to ask. They have levers; the queue does not.

---

## 7. Frequently asked (and frequently complained)

### "My colleague submitted after me and their job started first."

This is expected behaviour, not a bug. The platform starts work from whoever has used the least recently, not in submission order. Your colleague has been using less than you have over the past couple of weeks, so they went first.

It is worth saying that this cuts both ways: on the days when *you* have been light and someone else has been heavy, you jump ahead of them. Over a term it evens out, which is the credit.

### "I've been waiting for hours."

Check three things, in this order.

1. **Your standing.** If you are "at the back of the line", that is your answer, and it will improve over the coming days.
2. **Your request size.** A very large request can wait a long time even with excellent standing, simply because a gap that size rarely opens. If a smaller version of the job would do, submit that.
3. **Your position.** Once you have been waiting more than about ten minutes, the platform shows your position in the line. If it is not moving at all over an hour while the platform is clearly busy, something may be stuck — report it.

If none of those explain it, contact support. A job waiting hours with good standing, a modest request and no visible movement is a fault, and we want to hear about it.

### "I only ran one job. Why is my standing bad?"

Because standing measures *hardware held over time*, not *number of jobs*. One job can be enormous. A single run holding 600 cores and 2,400 GiB for four days is about 290,000 credits — considerably more than a hundred small notebooks. If you ran one big thing, you used a lot, and the number is correct.

The other common case: you have a session you forgot about. Check your running sessions before assuming the number is wrong.

### "Why did my session get shut down?"

Sessions end on their own for a small number of reasons, and the platform tells you which one:

- **Time limit reached.** Sessions run for up to 7 days; renewing before expiry takes one click. If it expires, simply start a new one — your files are untouched.
- **Idle timeout.** A session with no activity for an extended period is closed and its hardware returned to the pool. An open browser tab is not activity — a running kernel, a terminal you are typing in, or GPU utilisation is.
- **Node maintenance or hardware failure.** Occasionally a machine has to be drained.
- **Displaced by a guaranteed session.** Rare: the platform was completely full and a member claimed their guaranteed allotment. Displacement falls on the heaviest recent users first, and batch work is overwhelmingly what gets displaced.

**Fair share is not on this list.** Being behind in line does not shut anything down. It only affects when your *next* session starts.

Practical advice regardless of the reason: save your work, and prefer files on persistent storage over state that only exists in a notebook kernel. Any shared platform will occasionally take a machine away.

### "Someone is hogging the GPUs."

Two honest answers.

First, hogging is self-limiting. Whoever is holding those GPUs is accumulating usage at 35 credits per GPU per hour, and their standing is falling accordingly. The longer they hold, the further back they go, and the sooner you get in front of them. This is exactly the situation fair share is built for.

Second — and this is the honest caveat — fair share affects who starts *next*, not who is running *now*. It will not take a GPU back from a session that already has it. If someone is holding GPUs long-term and it is blocking real work, that is a conversation for your community administrator, who can see who holds what and can act. Report it; it is not tattling, it is how the mechanism is supposed to be supervised.

### "Can I get priority for a deadline?"

Not by anything you can do in the interface, and that is on purpose — a priority button that anyone can press stops being a priority button on the first day. Deadlines are handled by people: ask your community administrator. They can adjust your community's settings, or give you a heads-up about when the platform will be quiet.

### "Does closing my session lose my standing history?"

No. Closing a session stops it accruing further cost. Your history is unaffected and continues to fade normally. There is no advantage to keeping a session open, and there is a real cost.

### "Is this per-person or per-group?"

The intent is per person, within each community you belong to. If you belong to two communities, you have an independent standing in each — heavy use of one community's resources does not count against you in the other, because those are separate allocations made by separate people.

During the current rollout the accounting is at the community-group level, so your standing partly reflects your group's activity. This is being moved to per-person; the platform will show which basis is in effect.

### "How do I see my standing?"

It will appear next to your sessions, as a band and a one-line explanation, with a breakdown available if you want the numbers. It is not yet live for all users.

---

## 8. For platform operators and community administrators

This section assumes you represent a community (today: `cadc` and `src`; more later) and want to know what you control and what you can promise.

### What your community owns, lends, and borrows

Every community has a **nominal pool** — the amount it owns outright and can always claim back:

| | CPU cores | Memory | Scratch | GPUs |
| --- | --- | --- | --- | --- |
| `cadc` | 2,800 | 12,400 GiB | 99,200 GiB | 112 |
| `src` | 200 | 1,600 GiB | 4,800 GiB | 0 |

Three concepts govern how that pool is used:

- **Owning.** Your nominal pool is what your members are guaranteed to be able to reach. If your members want it, you get it back.
- **Lending.** Idle capacity is offered to other communities rather than sitting unused. Both communities currently lend 100% of their pool. This is why utilisation stays high — but it means "your" hardware may be in use by someone else at any given moment.
- **Borrowing.** When your members want more than you own and another community has idle capacity, you can use it. Borrowed capacity is the first thing given back when the owner wants it.

**Two things to know about the current borrowing configuration**, because they materially affect what you can promise:

1. **Borrowing is currently uncapped.** There is no ceiling on how much one community can borrow from another. In practice this means `src` — which owns zero GPUs — can hold all 112 of `cadc`'s GPUs. Explicit borrowing caps are a pending change.
2. **Reclaiming borrowed capacity does not currently work against interactive sessions.** Reclaim only interrupts work of strictly lower priority, and interactive sessions run at the highest priority. So a community cannot pull back capacity that another community's members are holding in notebooks or desktops. Until borrowing caps are in place, the practical control is the cap, not the reclaim. Do not promise members that borrowed GPUs can be recalled on demand.

If your community is expected to have GPUs, that must be reflected in your nominal pool. Borrowing is not a substitute for ownership.

### What you can adjust for your members

| Lever | What it does | Notes |
| --- | --- | --- |
| **Nominal pool** | What your community owns | The only real guarantee. Changing it is a platform-level negotiation. |
| **Borrowing cap** | Ceiling on how much you can take from others | Currently unset; recommended to set explicitly. |
| **Lending limit** | How much of your pool is offered out | Lower it to reserve headroom, at the cost of utilisation. |
| **Member weights** | Relative share between your members | Default is equal. Raise a weight to give a member a larger share of your community's pool — this changes turn order within your community only, never at the expense of another community. |
| **Session limits** | Maximum session length, idle timeout, maximum concurrent sessions, maximum concurrent GPU sessions per member | The main tool for interactive fairness. Tight enough to prevent squatting, loose enough to be invisible to normal users. |
| **Guaranteed floor** | The always-available allotment per member | The community's main protection lever. Size it so `floor × expected simultaneous claimants ≤ your pool`. Everything beyond the floor competes in one pool — there is no interactive-over-batch priority to tune. |
| **Half-life and pricing** | How fast usage fades; what a GPU costs relative to a core | Platform-wide, not per community. Changes here affect everyone and should be announced. |

### What you can see

- **Per-member standing** within your community: recent weighted usage, share, ratio, band, and position in the ordering. This is the same ordering the platform actually uses, so it explains rather than approximates.
- **Who is holding what right now**, broken down by member and by session type.
- **Queue depth and waiting times** for your community.
- **Your community's share of the platform** — how much you are using relative to what you own, and whether you are currently lending or borrowing.
- **Health flags** — stale accounting, unusual resets, resources not being measured correctly.

Ask for the administrator view; it is separate from the user-facing display and deliberately contains numbers users are not shown, because raw scheduler internals invite arguments about the number rather than conversations about the behaviour.

### When a member complains — a triage order

1. **Look at their standing.** If they are well behind and the platform is busy, the system is working as designed. Explain it with the numbers: "you've been holding about 2.3× your share recently, and it halves every five days."
2. **Look at their request size.** Very large or very wide requests wait a long time regardless of standing. Frequently the fix is a smaller request, and frequently the member did not realise they were asking for more than they use.
3. **Look at what they are holding.** More often than you would expect, the complaint and the cause are the same person's forgotten GPU desktop.
4. **Look at community-level contention.** Is your community above its pool and borrowing? Is another community holding capacity you own? Is your pool simply too small for your membership? These are your problems to escalate, not the member's.
5. **Check the health flags.** If accounting is stale or a resource is not being measured, standing numbers are not trustworthy and you should say so rather than defend them.
6. **Only then consider a lever.** Raising one member's weight is a real action with a real cost to their colleagues. Use it deliberately, tell the community you did it, and set a date to undo it.

### What to tell your members, and what not to

**Safe to promise:**
- Turn order is by recent usage, and it recovers over days to weeks.
- Nobody is forgotten; waiting is bounded and standing always improves.
- Your community's owned pool is genuinely yours when your members want it.
- Idle sessions are expensive and closing them helps immediately.

**Do not promise:**
- A start time or a wait estimate. There isn't one and there won't be.
- That borrowed capacity can be recalled on demand — currently it cannot, if the borrower is holding it interactively.
- That a specific number of GPUs will be free at a specific time.
- That fair share will solve a capacity shortfall. If your community consistently wants more than it owns, that is a resourcing conversation.

### Known limitations worth tracking

Listed here because they affect what the numbers mean. **The first two must be resolved before the
user-facing standing display is switched on** — a number that moves for reasons users cannot see does
more damage than no number.

- **Flexible-profile sessions are under-charged by about 8×.** A session launched without explicitly
  choosing cores and memory reserves up to 8 cores / 32 GiB but is charged for 1 core / 4 GiB. A
  session that *did* choose those values is charged the full amount. Until this is fixed, standing
  figures are not comparable between the two, and publishing the number would make the discount
  discoverable.
- **On the currently-deployed scheduler version, neither CPU nor GPU usage is recorded at all.** Two
  independent upstream defects mean the ledger is effectively a memory-only measurement — a queue
  holding a whole CPU core and a GPU records zero for both. Verified on a live cluster. A scheduler
  upgrade fixes both; it has been validated end-to-end on a test cluster and is pending rollout.
  Until it lands, treat GPU- and CPU-heavy standing figures as substantially understated.
- **Turn ordering does not yet discriminate between members**, because all work currently routes into
  a single queue per community. Per-member routing is the change that makes fair share actually
  arbitrate. Everything above describes intended behaviour; this is the step that turns it on.
- **A heavy user has no guaranteed upper bound on waiting.** The scheduler orders by recent usage
  with no factor that grows the longer something waits, so in principle a consistently heavy member
  can sit behind a stream of lighter ones indefinitely. A reserved fallback lane is planned to bound
  this. Track it before enabling per-member ordering, not after.
- **Deleting and recreating a member's queue erases their history permanently.** There is no backup
  and no recovery. Queue objects must be treated as persistent state — never pruned, never
  recreated, and protected from automated cleanup.

## 9. Glossary

Plain-language terms used in this document, mapped to the technical vocabulary used in the platform's design documents and code. Users do not need this table; it exists so this document and the technical one can be read side by side.

| Plain language | Technical term |
| --- | --- |
| Your standing / your share | Admission Fair Sharing usage — the decayed weighted usage for your queue |
| Your ledger | `LocalQueue.status.fairSharing.admissionFairSharingStatus.consumedResources` |
| Credit / core-equivalent | Weighted resource unit, from `resourceWeights` (cpu 1.0, memory 1.0/GiB, GPU 35) |
| Your community's pool | ClusterQueue nominal quota |
| The shared platform | Cohort |
| Your personal line | LocalQueue (per user, per community) |
| Your turn / when your job starts | Admission |
| Your job / your session | Workload (a suspended Kubernetes Job managed by Kueue) |
| Waiting | Pending, i.e. quota not yet reserved |
| Recent use fades | Exponentially weighted moving average with a configured half-life (`usageHalfLifeTime`) |
| Half-life | `admissionFairSharing.usageHalfLifeTime` — **120h (5 days)** |
| How often usage is measured | `admissionFairSharing.usageSamplingInterval` |
| Owning | `nominalQuota` |
| Lending | `lendingLimit` |
| Borrowing | Borrowing within the cohort; capped by `borrowingLimit` |
| Reclaiming | `preemption.reclaimWithinCohort` |
| Being interrupted | Preemption |
| Member weight | `LocalQueue.spec.fairSharing.weight` |
| Guaranteed vs normal tier | `WorkloadPriorityClass` (`normal` 10,000 / `guaranteed` 1,000,000) |
| Position in line | Visibility API `positionInClusterQueue` / `positionInLocalQueue` |
| Session time limit | Session TTL — 7 days with renewal, enforced by skaha |
| Idle timeout | Idle culling, enforced by skaha |
| The thing that launches your session | skaha |
| The thing that decides turn order | Kueue |

---

## 10. Where to go next

- **Something is wrong** — a job waiting far longer than your standing explains, a standing figure you believe is incorrect, a session that ended without explanation: contact platform support.
- **Something is unfair** — a member holding disproportionate resources, a limit that seems wrong for your work, a deadline you cannot meet: contact your community administrator.
- **You want the mechanism in full detail**, including the exact formula, the scheduler's ordering rule, and the configuration: see the technical design documents. The glossary above is the bridge.

One closing note on tone, for anyone passing this document on. Fair share is not a claim that the platform is fair in every sense — it is one specific rule, applied consistently: *when there is not enough to go around, whoever has used the least recently goes first.* That rule is simple enough to explain, hard to game, and self-correcting over time. It is not a substitute for enough hardware, and it does not pretend to be.
