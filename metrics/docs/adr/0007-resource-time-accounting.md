# ADR-0007: Active-workload lifetime resource accounting

## Status

Accepted.

## Decision

Metrics owns a versioned internal source-series contract for calculating total
usage and efficiency for Pods that are `Running` at report observation time.
The public accounting period is `ActiveWorkloadLifetime`. For every Pod
UID and open Kubernetes resource name, the contract exposes additive observed
resource-time and scheduler-effective requested resource-time, plus enough
continuity and coverage information to detect resets or incomplete Pod
lifetimes. The report assembler sums observed numerators and requested
denominators across the selected Running Pods, then derives the subject ratio.
It never averages Pod, User, or Community ratios.

Use a Metrics-owned durable producer as the accounting authority. Recording
rules may normalize short source intervals, but they cannot be the lifetime
authority: source retention can be shorter than a Pod lifetime, self-referential
recording rules do not provide transactional checkpoints, and a scrape gap can
silently remove part of a gauge integral. The producer checkpoints cumulative
per-Pod UID/resource numerators, denominators, the source cursor, and continuity
state in persistent Redis before publishing the corresponding series. Production
Redis must use append-only persistence and replication; cache eviction policy
must not apply to accounting keys.

On restart, the producer resumes from the last committed cursor and replays
retained source samples up to the current observation. A duplicate replay is
idempotent by Pod UID, resource, and source cursor. Missing replay input, a
counter reset that cannot be bridged, a scrape or sampling gap, a corrupt
checkpoint, or lost persistence marks that Pod/resource incomplete. It never
guesses across the gap. Container restart does not reset a Pod UID lifetime;
Pod recreation starts a distinct lifetime. A disappeared Pod is retained for
30 days for recovery and diagnosis but is excluded from reports unless that
exact UID is Running at observation time.

If any selected Running Pod lacks continuous accounting over its full Running
lifetime for a resource, the report omits that resource's `usageHours`,
`requestedHours`, and `efficiency` and reports
`Ready=False`/`AccountingIncomplete`. It does not publish a biased ratio over
only the covered Pods.

The source contract revision `1` publishes:

- `canfar_active_workload_usage_hours_total`
- `canfar_active_workload_requested_hours_total`
- `canfar_active_workload_accounting_complete`

Every series carries exactly `cluster`, `namespace`, `pod_uid`, `resource`,
`canfar_username`, `canfar_community`, `source_revision`, and `unit`.
Completeness also carries bounded `reason`: `complete`, `counter-reset`,
`corrupt-state`, `missing-series`, `pod-disappeared`, `process-restart`,
`sampling-gap`, or `scrape-gap`. Empty attribution values are retained rather
than dropping labels. There is at most one series per metric name, Pod UID,
resource, source revision, and attribution identity. Samples use UTC Unix
timestamps at the producer's committed cursor. Usage and requested series use
`core-hours`, `GiB-hours`, or `GPU-hours`; completeness uses `boolean`. Series
and checkpoints are retained for the full Running lifetime and 30 days after
disappearance. Source-contract upgrades write a new revision alongside the old
one; a producer never combines revisions, and the old revision is removed only
after no Running Pod depends on it plus the recovery retention.

The series expose numerator and denominator values, not only a precomputed
ratio. Public clients never submit PromQL or depend on internal series names;
Metrics queries the series through its controlled, versioned PromQL catalog and
caches normalized query results according to ADR-0005.

The `promql` adapter implements only the Prometheus-compatible instant-query
endpoint `/api/v1/query` in this phase. It submits Metrics-owned catalog
templates with form-encoded POST so query text does not enter URLs. Settings
may provide a typed optional Mimir tenant ID for `X-Scope-OrgID`; there is no
arbitrary caller parameter or header map. The adapter strictly validates the
result type, allowed labels, cardinality, units, source-contract revision, and
timestamp bounds before publishing a normalized cache snapshot. Range queries
remain outside this phase.

Only Pods in phase `Running` at report observation time contribute. Each Pod's
values cover its own Running lifetime, so Pods with different ages contribute
different integration intervals. Completed Pods and fixed historical windows
are outside the initial contract.

Public values are decimal strings converted to resource-specific hours: CPU
uses core-hours, memory uses GiB-hours, and `nvidia.com/gpu` uses GPU-hours.
The resource name implies the unit; the response does not repeat a `unit` field.

## Consequences

- The accounting tool and Metrics API can evolve independently when the source
  contract revision is explicit.
- CPU, memory, and future resources share one additive aggregation rule while
  preserving resource-specific units.
- Coverage and reset behavior are part of correctness and test fixtures, not
  incidental PromQL implementation details.
- Stateful accumulation adds operational ownership, persistence, and telemetry
  work, but recording rules alone cannot prove full-lifetime continuity.
- Corrupt or unavailable durable state fails accounting closed; it cannot
  produce a Ready partial result.
