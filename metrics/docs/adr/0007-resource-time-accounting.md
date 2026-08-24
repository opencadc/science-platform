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

Use Prometheus/Mimir recording rules where the requested and observed
resource-time can be derived completely and reset-safely from retained source
series. Use a Metrics-owned stateful exporter or controller where durable
accumulation is required. An exporter restart, scrape gap, label disappearance,
or counter reset must not silently turn a partial interval into a full-lifetime
efficiency value. If any selected Running Pod lacks continuous accounting over
its full Running lifetime for a resource, the report omits that resource's
`usageHours`, `requestedHours`, and `efficiency` and reports
`Ready=False`/`AccountingIncomplete`. It does not publish a biased ratio over
only the covered Pods.

The internal series are cumulative or otherwise reset-detectable and carry the
Pod UID, resource name, canonical CANFAR attribution labels, and source-contract
revision. They expose numerator and denominator values, not only a precomputed
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
  work when recording rules alone cannot prove full-lifetime continuity.
