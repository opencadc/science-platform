# Metrics learnings

This file records concise, implementation-backed lessons. Durable architecture
decisions live in [`adr/README.md`](adr/README.md); platform-owned labels live
in [`../../skaha/docs/labels.md`](../../skaha/docs/labels.md); the wire
contract lives in [`specs.md`](specs.md).

## Current lessons

- Date: September 24, 2026
  - Context: Cache redesign to two stages with one refresher.
  - Lesson: Derive a snapshot's stage from its remaining Redis TTL (written
    as the stale window from fill start) and decide "serve, claim, or wait" in
    one Lua read. Then only one request across every replica can refresh a
    stale snapshot, and no replica or source clock is compared. A retained
    third stage added keys and states without serving anyone.
  - Evidence: `src/metrics/cache/redis.py`, the multi-replica property test in
    `tests/test_cache_coordinator.py`, and a kind burst of 300 concurrent
    requests over a stale snapshot that caused one fill and one lease claim.
  - Action taken: Windows are now Platform 5/10, User 2/4, Community 5/10
    minutes, and Session 30/60 seconds (fresh/stale). ADR-0005 amended; the
    schema revision was bumped so old and new replicas never share keys.

- Date: September 24, 2026
  - Context: Lease ownership under stalls and dropped replies.
  - Lesson: Every owner exit (publish, cooldown, release) must be fenced on
    the lease token in Lua, and a resent claim must recognize its own token.
    The lease must end before a cold waiter's deadline or a crashed owner is
    never replaced. A failed fill needs a short cooldown, or every waiter
    retries a failing source.
  - Evidence: `tests/test_cache_redis.py` and `tests/test_cache_coordinator.py`.
  - Action taken: The deadlines are constants (lease 13 s, cold wait 15 s,
    failure cooldown 5 s), so no configuration can put them out of order.

- Date: September 24, 2026
  - Context: Readiness during shared outages.
  - Lesson: A readiness probe that follows a dependency every replica shares
    removes every replica at once, turning serviceable stale data into
    connection errors. Validate once, then latch.
  - Evidence: ADR-0012 and a kind Redis outage in which `/readyz` stayed 200
    while reports degraded to the outage copy and then to 503.
  - Action taken: `/readyz` latches after Redis and the configured
    ClusterQueues validate once.

- Date: September 24, 2026
  - Context: Session requests disagreed with Kueue.
  - Lesson: Summing every container's requests over-reports a pod with an init
    container, and counting finished or suspended Jobs reports quota nobody
    holds. Use the effective pod request (Kubernetes and Kueue semantics) of
    active Jobs only.
  - Evidence: `tests/test_session.py` and a kind desktop session whose
    reported 0.3 CPU matched Kueue's reservation.
  - Action taken: ADR-0010 amended; a finished session returns 200 with no
    resources.

- Date: September 24, 2026
  - Context: Session efficiency over a whole window.
  - Lesson: A duration ratio must integrate requests only while each pod was
    `Running` and scope heavy selectors to the session's Job pods, with a
    fixed subquery step. kube-state-metrics must expose `canfar.net/id` or
    Session efficiency has no series.
  - Evidence: `src/metrics/providers/promql.py`, `tests/test_promql.py`, and
    `scripts/test-dependencies.yaml`.
  - Action taken: Sessions shorter than one minute report no efficiency.

- Date: September 24, 2026
  - Context: Silent configuration drift.
  - Lesson: A retired name that is quietly ignored is worse than a crash: a
    single-underscore `METRICS_OTEL_*` key turned telemetry off with no signal.
  - Evidence: `src/metrics/core/settings.py` (`RETIRED_ENVIRONMENT`) and
    `tests/test_main.py`.
  - Action taken: Startup exits with status 2 on retired names, invalid
    settings, or a placeholder cache secret, naming variables but never values.

- Date: September 24, 2026
  - Context: Skaha consumes Platform reports.
  - Lesson: A consumer that accepts only `Ready=True`/`Available` turns every
    stale or partial Platform report into its own outage. Consumers should
    accept every valid condition pair from the specification.
  - Evidence: Skaha `PlatformMetricsDAO` and its tests on
    `feat/skaha-usage-metrics`.
  - Action taken: Skaha accepts `Available`, `PartialData`, and `StaleData`
    and still rejects malformed pairs.

- Date: August 26, 2026
  - Context: Cache window retune after User traffic vs Community/Platform
    refresh cost.
  - Lesson (superseded September 24, 2026 by the two-stage windows above):
    Fresh/serviceable/retained windows are per-surface, not a shared
    User+Community pair. User stays fresh for 2 minutes, is serviceable stale
    through 3 minutes, and is retained (not served) through 5 minutes.
    Community is 5/10/15 minutes. Platform stays 5/30/60 minutes.
  - Evidence: `FRESHNESS_POLICIES` in `src/metrics/cache/models.py`, ADR-0005,
    and `docs/runbooks/redis.md`.
  - Action taken: Split User from Community in the fixed policy table and
    updated the ADRs, runbook, and implementation-backed docs together.

- Date: August 25, 2026
  - Context: Metrics redesign after review.
  - Lesson: A small queue read is the product boundary. User requests come
    from LocalQueues, Community requests come from labelled configured
    ClusterQueues, and Platform totals come from the configured ClusterQueue
    list. Do not reintroduce Pod inventory, Cohorts, or a producer to fill a
    source gap.
  - Evidence: ADR-0010, `docs/specs.md`, and the Confluence Metrics
    Backend contract.
  - Action taken: Superseded the accounting and Pod-source ADR sections and
    removed the accounting runbook.

- Date: August 25, 2026
  - Context: User LocalQueue aggregation.
  - Lesson: `pendingWorkloads` means waiting; `reservingWorkloads` represents
    work holding or moving through Kueue reservation and is the public count.
    It must not be renamed to `runningPods`.
  - Evidence: Kueue v1beta2 LocalQueue status and the approved API shape.
  - Action taken: Documented `reservingWorkloads` on all three surfaces.

- Date: August 25, 2026
  - Context: Queue identity and optional efficiency attribution.
  - Lesson: Core Kueue labels and conditional PromQL labels are different
    contracts. Platform-controlled admission stamping must preserve exact,
    case-sensitive values from LocalQueue through Jobs and Pod templates.
  - Evidence: `skaha/docs/labels.md` and the fixed PromQL label contract.
  - Action taken: Documented attribution against the Skaha label catalog and
    linked it from the service entry points.

- Date: August 25, 2026
  - Context: Concurrent report requests across API replicas.
  - Lesson: One shared Redis lease per surface and subject prevents duplicate
    Kueue/PromQL reads across replicas while unrelated subjects remain
    parallel. An in-process lock alone is insufficient.
  - Evidence: ADR-0005 and `docs/specs.md`.
  - Action taken: Kept one lease per surface and subject (the window values
    recorded here were later superseded).

- Date: August 25, 2026
  - Context: Optional current efficiency.
  - Lesson: Efficiency is a current Running-Pod ratio from Prometheus/Mimir,
    not a lifetime value. It must remain optional and server-owned; a backend
    failure returns Kueue data with `PartialData`.
  - Evidence: `docs/specs.md`, `skaha/docs/labels.md`, and ADR-0010.
  - Action taken: Removed usage-hours, checkpoints, and producer language from
    the current documentation.

- Date: July 31, 2026
  - Context: Kueue client access.
  - Lesson: Validate the exact Kubernetes access pattern production RBAC
    allows. The `kueue.x-k8s.io/v1beta2` contract and configured queue boundary
    are more important than a convenient client helper.
  - Evidence: ADR-0001 and the Kueue validation workflow.
  - Action taken: Keep Kubernetes access in one explicit Kueue provider and
    document the required namespace and ClusterQueue lists.

- Date: April 17, 2026
  - Context: Repository conventions.
  - Lesson: Use Conventional Commits for changes so release tooling can
    classify history.
  - Evidence: repository contribution instructions.
  - Action taken: Retained as a standing repository convention.
