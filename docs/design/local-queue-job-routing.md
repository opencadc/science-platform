# Design: per-user LocalQueue routing (community `default`)

## Nomenclature

- **Community** = one ClusterQueue (operator-owned). Jobs always admit through a Community’s ClusterQueue.
- **LocalQueue** = private, user-named (§5). One LocalQueue per user in this slice; it points at the Community’s ClusterQueue.
- **Now:** a single Community `default` → Helm `clusterQueue`.
- **Later:** more than one ClusterQueue means more than one Community; selection/GMS stay out of this slice.

## Problem

Every new Skaha user Job must land on a private LocalQueue named by spec §5, labelled with the user’s identity and community `default`, pointing at the Helm-configured ClusterQueue for that community. Metrics must fail closed when two configured ClusterQueues share a community label (§9). Community POST, GMS, and multi-community selection stay out of this slice.

## Usage (caller's view)

```yaml
deployment.skaha.sessions.kueue:
  community: default
  clusterQueue: cadc   # operator ClusterQueue; must be in Metrics CLUSTER_QUEUES
  default: {priorityClass: high}
  headless: {priorityClass: low}
```

Leftover `queueName` fails render. `PostAction` builds an assignment once, then the builder and launcher consume it.

```java
LaunchAssignment assignment = LaunchRouter.fromEnv().assign(owner, type);
SessionJobBuilder.fromPath(path, assignment) /* ... */ .buildLaunch();
new SessionLauncher(client).launch(launch, ownedYamls); // ensure LocalQueue then create Job
```

## Shape

- `LaunchAssignment` (immutable): namespace, user, community, clusterQueue, workloadPriorityClass. `localQueue()` derives the §5 name (never stored).
- `LaunchRouter`: replaces `QueueConfiguration`. Reads `community` + `clusterQueue` + per-type priorities from env. Rejects `SKAHA_QUEUE_*_NAME`. `assign` is pure.
- `LocalQueues.ensure(assignment)`: sole ClusterQueue reader / LocalQueue writer. Create, reuse, repair missing labels only when `managed-by=skaha`. Non-empty `ownerReferences` → 503. Hyphen-collision: both identity labels missing on a readable name → 503 unless the name is unambiguous for the requested pair via digest rules; one surviving matching label may repair. Never write `managed-by` during repair. Before any write, Job must already carry matching queue-name and `suspend=true` from the assignment.
- `SessionJobBuilder.fromPath(path, assignment)`: always suspend + queue-name; user/community on Job and Pod. `withQueue` deleted.
- Metrics: index configured ClusterQueues by community label; duplicate → `ProviderExecutionError` on platform, community, user, and startup.

## Synthesis decision

Base: candidate-opus ([LaunchAssignment sketch](75f6264c-be2c-45b5-b63b-c871439181a3)), picked by [cross-judge](3b8e1155-deac-4853-8d7c-4d90136f59bd) 27–25 over [extend builders](700cc7cc-2f47-40cd-9aba-9e2b898ec2fc).

Grafts from Grok: (1) reject non-empty `ownerReferences`; (2) hyphen-collision vectors + digest both-labels-missing repair, with unambiguous readable repair kept; (3) Job stamp check before LocalQueue mutate, comparing to the assignment only.

Operator lock: community stays the configured `default` string mapped to Helm `clusterQueue`. Helm uses flat `kueue.community` / `kueue.clusterQueue` for this slice (thinner than a full `communities` map).

## Tradeoffs accepted

- Desktop-app Jobs use current Default route, not parent inheritance.
- Live ClusterQueue GET + LocalQueue GET each launch, no cache.
- Prod needs ClusterQueue `get` (CADC-15958) or every launch is 503.
- Periods in usernames take the digest path (DNS-1123 label rule).

## Next implementation step

Naming vectors + `LaunchAssignment.localQueue()`, then router, ensure, builder, Helm, Metrics.
