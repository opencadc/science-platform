# Milestone M2: platform metrics initial release

This plan defines the second milestone for the CANFAR Metrics API rollout. It
reframes platform metrics around raw Kueue API research, live test fixtures,
and a contract lock for platform capacity over a configured set of
`ClusterQueues`.

## Summary

This milestone turns `GET /api/v1/metrics/platform` into a Kueue-backed
platform slice that is grounded in live cluster behavior rather than static
assumptions. You install Kueue in the test environment, seed at least two
`ClusterQueues` plus a shared `Cohort`, characterize the raw Kueue fields for
quota and allocation, and use that evidence to finalize a simple platform API
contract built around `capacity` and `allocated` resource maps.

The production-facing runtime model for this milestone is a configured list of
target `ClusterQueues`, an optional configured `Cohort`, a
`KUEUE_METRICS_URL` connection target when the service does not already talk
through the Kubernetes API server, and
`PLATFORM_METRICS_CACHE_TTL=300` seconds. The milestone should standardize on
uppercase runtime names such as `KUEUE_METRICS_CLUSTER_QUEUES`,
`KUEUE_METRICS_COHORT`, and `KUEUE_METRICS_URL`, then document how they align
with the service settings layer.

## In scope

This section lists milestone deliverables you execute.

- Install Kueue in local and CI-backed test environments used for live
  integration validation.
- Seed at least two `ClusterQueues` and one shared `Cohort` to exercise queue
  subset aggregation and cohort deduplication.
- Query raw Kueue `ClusterQueue` and `Cohort` resources and document which
  fields map to configured quota, shared quota, and live allocation.
- Finalize platform contract semantics for aggregating over a runtime-configured
  list of `ClusterQueues`.
- Finalize a minimal response shape with `capacity.<resource-name>` and
  `allocated.<resource-name>` keys rather than fixed resource fields.
- Ensure the platform contract can represent all current and future resource
  kinds, including custom resources such as GPU, IOPS, network, and
  provider-specific resource names.
- Set the milestone default cache TTL to five minutes before the service
  re-queries Kueue state.
- Preserve cache metadata in the platform response.

## Out of scope

This section lists deferred work.

- User and session attribution endpoint logic.
- Staging ArgoCD integration.
- Advanced analytics and dashboard-oriented metrics slices.
- Prometheus ownership model redesign.
- Long-lived watch or informer-based Kueue synchronization.
- Final rollout of node fallback as the primary production behavior for this
  milestone.
- Runtime utilization or consumption reporting beyond allocated resource totals.

## Dependencies

This milestone depends on the M1 foundation, the current platform endpoint, and
the live cluster test path.

- M1 quality and delivery scaffolding.
- `metrics/src/metrics/providers/kueue.py`.
- `metrics/src/metrics/config.py`.
- `metrics/src/metrics/service.py`.
- `metrics/src/metrics/models.py`.
- `metrics/src/metrics/api/routes.py`.
- `metrics/tests/test_providers.py`.
- `metrics/tests/integration/test_k8s_smoke.py`.
- `metrics/scripts/run-minikube-integration.sh`.
- `metrics/scripts/minikube-values.yaml`.
- `metrics/helm/metrics-api/`.
- Raw Kueue API resources:
  `/apis/kueue.x-k8s.io/v1beta1/clusterqueues` and
  `/apis/kueue.x-k8s.io/v1beta1/cohorts`.

## Constraints

This milestone must preserve operational safety and keep the contract grounded
in raw Kueue semantics.

- Maintain low impact on production infrastructure and bound request behavior to
  the configured timeout.
- Preserve a 12-factor runtime configuration model for the selected queue list,
  `KUEUE_METRICS_COHORT`, `KUEUE_METRICS_URL`, and cache TTL.
- Keep contract payload keys stable for platform scope while the internals move
  from broad queue summation to queue-subset aggregation.
- Parse Kubernetes quantity strings correctly for both quota and status fields.
- Deduplicate cohort-shared quota across the configured queue set rather than
  double-counting it once per member queue.
- Treat raw Kueue status as authoritative for allocated resources and do not
  add allocated totals to configured capacity.
- Keep the milestone on the `kueue.x-k8s.io/v1beta1` API surface unless a
  version change is explicitly researched and approved.
- Keep the resource schema open-ended so future keys such as `io`, `iops`,
  `network`, or vendor-specific resources do not require a contract redesign.

## Research baseline

This section records the raw Kueue fields under evaluation so the milestone can
tie contract decisions to live evidence.

- `ClusterQueue.spec.resourceGroups[*].flavors[*].resources[*].nominalQuota`
  defines queue-owned quota.
- `ClusterQueue.spec.cohort` identifies cohort membership for each selected
  queue.
- `Cohort.spec.resourceGroups[*].flavors[*].resources[*].nominalQuota` defines
  a shared pool on top of member queue quota.
- `ClusterQueue.status.flavorsReservation[*].resources[*].total|borrowed`
  exposes reserved quota currently held by assigned workloads.
- `ClusterQueue.status.flavorsUsage[*].resources[*].total|borrowed` exposes
  quota used by admitted workloads.

M2 must compare `flavorsReservation` and `flavorsUsage` against live test data
before the platform contract locks one of them as the default meaning of
`allocated`. Until then, the milestone treats that choice as an explicit
research outcome rather than an assumption.

## Implementation phases

This section sequences platform release work.

1. **Kueue test environment bring-up**
   - Install Kueue in Minikube-backed local and CI test environments.
   - Seed at least two `ClusterQueues` and one shared `Cohort`.
   - Replace static-only assumptions in the live integration path.
2. **Raw API characterization**
   - Query `ClusterQueue` and `Cohort` resources through the raw Kueue API.
   - Record exactly which fields represent queue quota, cohort-shared quota,
     reservation, admitted usage, and borrowed quota.
   - Confirm how the selected queue subset maps onto cohort membership.
3. **Platform contract design**
   - Define aggregate capacity semantics for a runtime-configured list of
     `ClusterQueues`.
   - Deduplicate cohort-shared quota once across the configured queue set.
   - Finalize a minimal response shape built around `capacity` and `allocated`
     generic resource maps.
   - Ensure the response can carry all current and future resource kinds
     without fixed field names.
   - Use live observations to choose between `flavorsReservation` and
     `flavorsUsage` for `allocated`.
4. **Runtime and cache configuration**
   - Define how the service receives `KUEUE_METRICS_CLUSTER_QUEUES` and
     `KUEUE_METRICS_COHORT` at runtime.
   - Define how `KUEUE_METRICS_URL` maps onto the existing service settings.
   - Set the platform metrics cache TTL default to 300 seconds and verify that
     the service does not re-query the cluster inside that window.
5. **Verification and rollout readiness**
   - Run live integration validation against the Kueue-backed test setup.
   - Verify that the endpoint contract matches observed queue and cohort state.
   - Capture rollout notes for safe promotion after the contract is locked.

## Validation plan

This section defines milestone verification.

- Run gate `harness-contracts`.
- Run gate `repository-coverage`.
- Run gate `harness-cli`.
- Validate that Minikube and CI install Kueue successfully before integration
  tests run.
- Validate that the seeded environment contains at least two `ClusterQueues`
  and one shared `Cohort`.
- Validate that the platform endpoint reflects only the configured queue subset.
- Validate that cohort-shared quota is deduplicated once across the selected
  queues.
- Validate that the response shape uses only `capacity` and `allocated` generic
  resource maps.
- Validate that custom and future resource keys can be represented without
  schema changes.
- Compare `flavorsReservation` and `flavorsUsage` against live queue state and
  record the `allocated` contract decision with evidence.
- Validate five-minute cache TTL behavior and cache metadata on the platform
  endpoint.

## Risks

This section lists milestone risks and mitigations.

- **Cohort double-counting:** A naive sum over selected queues can overstate
  capacity when queues share cohort quota. Mitigate this by seeding a shared
  cohort in tests and requiring dedupe evidence.
- **Allocation semantic ambiguity:** `flavorsReservation` and `flavorsUsage` can
  describe different live states. Mitigate this by comparing both against live
  behavior before locking the meaning of `allocated`.
- **Raw API version drift:** Kueue CRD fields can change between API versions.
  Mitigate this by pinning research and tests to `v1beta1` for M2.
- **Runtime configuration drift:** Production names such as
  `KUEUE_METRICS_CLUSTER_QUEUES`, `KUEUE_METRICS_COHORT`,
  `KUEUE_METRICS_URL`, and `PLATFORM_METRICS_CACHE_TTL` can diverge from the
  current service settings. Mitigate this by documenting the mapping in the
  milestone contract.
- **Static-only test blind spots:** Current Minikube and CI flows do not
  exercise live Kueue objects. Mitigate this by making Kueue installation and
  seeded fixtures part of milestone validation.
- **Resource schema churn:** A fixed response model can block future resources
  such as network or IOPS. Mitigate this by locking the contract around generic
  resource-name keys now.

## Operational controls

This section defines release controls for stable operation.

- Require live Kueue-backed smoke validation before promotion.
- Require an explicit configured queue list for production aggregation.
- Require evidence for any cache TTL change from the 300-second default.
- Require captured evidence for the chosen `allocated` semantic.
- Require recorded queue and cohort fixture manifests for regression testing.
- Require uppercase runtime configuration names in milestone-facing
  documentation.

## Implementer handoff checklist

Use this checklist to close M2 execution.

- [ ] Kueue installation is automated for local and CI-backed live tests.
- [ ] The seeded environment includes at least two `ClusterQueues` and one
      shared `Cohort`.
- [ ] Raw `ClusterQueue` and `Cohort` fields used by the contract are validated
      against live responses.
- [ ] Platform aggregation is defined for a runtime-configured queue subset.
- [ ] Cohort-shared quota is deduplicated once across selected queues.
- [ ] The response contract is limited to `capacity` and `allocated` generic
      resource maps.
- [ ] Future resource kinds can be represented without schema changes.
- [ ] The contract decision for `flavorsReservation` versus `flavorsUsage` is
      documented as the public meaning of `allocated`, with live evidence.
- [ ] The platform metrics cache TTL defaults to 300 seconds and is verified.
- [ ] Uppercase runtime environment names are documented for queue, cohort, URL,
      and cache TTL configuration.
- [ ] Required gates pass.
