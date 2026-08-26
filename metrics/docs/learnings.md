# Metrics learnings

This file records concise, implementation-backed lessons. Durable architecture
decisions live in [`adr/README.md`](adr/README.md); queue identity and
attribution rules live in [`metadata-labels.md`](metadata-labels.md).

## Current lessons

- Date: August 25, 2026
  - Context: Metrics redesign after review.
  - Lesson: A small queue read is the product boundary. User requests come
    from LocalQueues, Community requests come from labelled configured
    ClusterQueues, and Platform totals come from the configured ClusterQueue
    list. Do not reintroduce Pod inventory, Cohorts, or a producer to fill a
    source gap.
  - Evidence: ADR-0010, `docs/architecture.md`, and the Confluence Metrics
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
  - Evidence: `docs/metadata-labels.md` and the fixed PromQL label contract.
  - Action taken: Added a dedicated metadata-label requirements document and
    linked it from the service entry points.

- Date: August 25, 2026
  - Context: Concurrent report requests across API replicas.
  - Lesson: One shared Redis lease per surface and subject prevents duplicate
    Kueue/PromQL reads across replicas while unrelated subjects remain
    parallel. An in-process lock alone is insufficient.
  - Evidence: ADR-0005 and `docs/design.md`.
  - Action taken: Kept the cache policy at User/Community 2/10/15 minutes and
    Platform 5/30/60 minutes.

- Date: August 25, 2026
  - Context: Optional current efficiency.
  - Lesson: Efficiency is a current Running-Pod ratio from Prometheus/Mimir,
    not a lifetime value. It must remain optional and server-owned; a backend
    failure returns Kueue data with `PartialData`.
  - Evidence: `docs/specs.md`, `docs/metadata-labels.md`, and ADR-0010.
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
