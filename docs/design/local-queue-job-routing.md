# About per-user LocalQueue routing

## Names

A Community is one operator-owned ClusterQueue. Every session Job admits through
that ClusterQueue. A LocalQueue is private to one user. It points at the
Community's ClusterQueue. This change uses a single Community named `default`.
More Communities later means more ClusterQueues. Community selection and GMS are
out of scope here.

## What this change does

On first launch for a user, Skaha creates a LocalQueue named
`<user>-default-lq` in the workload namespace when that name is a DNS-1123 label
of at most 63 characters. If the name is longer or not a DNS-1123 label, Skaha
rejects the launch. It does not hash the name.

The LocalQueue carries `canfar.net/username`, `canfar.net/community=default`, and
`app.kubernetes.io/managed-by=skaha`. Its `spec.clusterQueue` is the Helm value
`deployment.skaha.sessions.communities.default.clusterQueue`. That ClusterQueue
must already exist and must be labelled `canfar.net/community=default`.

Skaha never deletes LocalQueues. Metrics treats a repeated community label on
two configured ClusterQueues as an error.

## Helm

```yaml
deployment.skaha.sessions.communities:
  default:
    clusterQueue: cadc
deployment.skaha.sessions.kueue:
  default: {priorityClass: high}
  headless: {priorityClass: low}
```

A leftover `queueName` fails the chart render. List the ClusterQueue in
`METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES`. The workload Role needs LocalQueue
`create`, `get`, and `patch`. Skaha also needs ClusterQueue `get`.

## Call path

```java
LaunchAssignment assignment = LaunchRouter.fromEnv().assign(owner, type);
SessionJobBuilder.fromPath(path, assignment) /* ... */ .buildLaunch();
new SessionLauncher(client).launch(launch, ownedYamls);
```

`LaunchRouter` replaces `QueueConfiguration`. It rejects `SKAHA_QUEUE_*_NAME`.
`LocalQueues.ensure` is the only Skaha code that reads ClusterQueues or writes
LocalQueues. Before that write, the Job must already carry the assignment's
`kueue.x-k8s.io/queue-name` and `suspend=true`.

## LocalQueue ensure

| Existing object | Result |
|-----------------|--------|
| None | Create the private LocalQueue |
| Both identity labels match, same ClusterQueue | Reuse |
| Skaha-managed, one identity label missing, the other matches | Patch the missing label |
| Both identity labels missing, owner references, wrong ClusterQueue, conflicting labels, or not Skaha-managed with gaps | 503 |

## Metrics

User reports list LocalQueues by `canfar.net/username` only. They do not read
ClusterQueues. Community reports read the one configured ClusterQueue with that
`canfar.net/community` label. Platform and startup fail if two configured
ClusterQueues share a community label.

## Trade-offs

Desktop-app Jobs still use the Default Community route. Each launch does a live
ClusterQueue GET and LocalQueue GET with no cache. Production needs ClusterQueue
`get` for the Skaha ServiceAccount (CADC-15958) or every launch returns 503.
Usernames that cannot form a DNS-1123 LocalQueue name cannot launch.
