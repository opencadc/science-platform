# Kueue source guide

Metrics has one Kueue provider with three explicit source views. The required
label and admission contract is in [`metadata-labels.md`](metadata-labels.md).

## Source views

| View | Kueue objects | Selection | Public values |
| --- | --- | --- | --- |
| User | LocalQueues | Configured namespaces plus exact `canfar.net/username` | Reservation resources and `reservingWorkloads` |
| Community | Configured ClusterQueues | Exact `canfar.net/community` | Reservation resources and `reservingWorkloads` |
| Platform | Configured ClusterQueues | Complete configured list | Nominal capacity, usage allocation, and `reservingWorkloads` |

The provider uses `kueue.x-k8s.io/v1beta2`. It reads only the configured
objects and never discovers Platform membership by listing every ClusterQueue.
Cohorts are not a source.

## User aggregation

The User read lists LocalQueues in each namespace from
`METRICS_PROVIDERS__KUEUE__NAMESPACES`, selects the exact
`canfar.net/username=<username>` label, and validates that:

- the LocalQueue references a configured ClusterQueue;
- its `canfar.net/community` equals the referenced ClusterQueue label; and
- its Kubernetes object identity is distinct from every other matching
  LocalQueue. Multiple distinct LocalQueues may carry the same User and
  Community labels and are all aggregated. A repeated `(namespace, name)`
  identity, or a conflicting UID when present, is corrupt metadata.

For each valid queue, add `status.flavorsReservation.resources[].total` by
resource name and add `status.reservingWorkloads`. All configured namespace
reads must succeed for a complete cold result. Multiple namespaces and
multiple distinct matching LocalQueues in one namespace are valid and are
aggregated.

No Pod list, Pod phase, Job name, or Cohort relationship contributes to User
requests.

## Community aggregation

The Community read filters the configured ClusterQueue set by exact
`canfar.net/community=<community>`. It aggregates every match. No match is a
404; malformed or inaccessible matching metadata is a primary-source failure.
The Community report does not enumerate Users or LocalQueues.

## Platform aggregation

The Platform read visits every configured ClusterQueue and computes:

- `capacity`: sum nominal quota by resource name;
- `allocated`: sum `status.flavorsUsage.resources[].total` by resource name; and
- `reservingWorkloads`: sum the ClusterQueue status count.

Kueue's `total` already includes borrowed quota. Do not add a separate
`borrowed` field. Do not add Cohort quota or usage and do not infer Platform
membership from a label.

## Failure boundaries

- Missing User LocalQueues: 404.
- No configured ClusterQueue with the requested Community label: 404.
- A missing, inaccessible, malformed, duplicate, or cross-linked queue object:
  use a serviceable complete snapshot when available, otherwise 503.
- Invalid Kubernetes quantity: source failure, never zero.

Optional PromQL failure while `METRICS_PROVIDERS__PROMQL__BASE_URL` is present
is independent: the primary Kueue report remains HTTP 200 with efficiency
omitted and `Ready=False`/`PartialData`.

## RBAC

The service account needs read access to LocalQueues in every configured
namespace and read access to the configured ClusterQueues. The exact verbs and
resource names are deployment-specific, but least privilege must not expand
the source boundary to arbitrary Pod inventory or unconfigured queues.
