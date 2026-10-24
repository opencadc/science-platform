# ADR-0005: Fabric8 as Skaha's Kubernetes client

## Status

Accepted, 2026-10-23. Implementation in progress under
[CADC-15897](https://herzberg.atlassian.net/browse/CADC-15897).

## Context

Skaha reaches Kubernetes in two ways:

- `CommandExecutioner` / `KubectlCommandBuilder` fork `kubectl` for Job,
  Service, IngressRoute and Secret creation, desktop-app attach, renew, events
  and logs. Each launch forks 6–8 processes, each with a new TLS connection,
  and manifests are staged in `/tmp`.
- client-java 26 serves session reads, pod metrics, LimitRange and the
  LocalQueue startup check.

The default `CommandExecutioner.execute()` returns stderr as success, so failed
creates are silent and a POST can return a session ID for a session that cannot
run. client-java and `kubectl` share no connection pool, timeouts or retry
policy.

## Decision

- **One client.** Fabric8 Kubernetes Client replaces `kubectl` and client-java
  everywhere in Skaha. New Kubernetes code uses Fabric8 only.
- **One instance per replica.** `SkahaKubernetesClient` is built in
  `InitializationAction.doInit()` from the in-cluster ServiceAccount (token
  re-read per request for rotation) and closed in `doShutdown()`. Its default
  namespace is the workload namespace. Tests install a client pointed at the
  Fabric8 mock server.
- **Transport.** OkHttp (`kubernetes-httpclient-okhttp`); Vert.x is excluded.
  The client is configured the standard Fabric8 way (`Config.autoConfigure`,
  `KUBERNETES_*` environment), and OkHttp's own connection pool is used. Skaha
  adds no client settings, Helm values or transport factory.
- **No HTTP retries.** Fabric8's request retry is disabled, so a POST is never
  resent blindly. Skaha owns retry policy per operation.
- **Job create.** Bounded and retryable by Skaha: the Job name contains the
  per-request random session ID, so a 409 means our own earlier attempt landed;
  read it back and check `canfar.net/id`. The UID comes from the create
  response.
- **Partial failure.** If any step fails after the Job exists, delete the Job
  (owner references cascade to its Service and IngressRoute) and return 503.
  Never return a session ID for a launch that cannot run.
- **Caching.** Session reads come from per-replica Job and Pod informers. A read
  that misses right after a create falls back to a direct GET. The max-sessions
  admission check stays a live LIST.
- **Preserved.** Operator templates and custom fields (Traefik generic
  resources), Session labels and selectors, the callback signing Secret format,
  and `MetricsDAO` as the single metrics seam with its failure policies.

## Rollout

One PR per phase, each behind its own sub-task of CADC-15897:

| Phase | Change |
| --- | --- |
| 1 | This ADR; managed Fabric8 client; LocalQueue check on Fabric8 |
| 2 | Launch reliability fixes on the current `kubectl` path |
| 3 | Parent session launch and Secrets through Fabric8 |
| 4 | Desktop-app attach; session Jobs built from Fabric8 models |
| 5 | Renew, events and logs through Fabric8; `kubectl` removed from code and image |
| 6 | client-java removed; informer-cached session reads |

A migrated operation never falls back to `kubectl`. There is no runtime
client-selection flag.

## Consequences

- Until Phase 6, client-java and Fabric8 coexist; both use OkHttp 5.
- Phase 5 removes `kubectl`, `docker-ce` and `containerd` from the image.
- Phase 6 supersedes the client-java detail in ADR-0001; the `MetricsDAO`
  contract is unchanged.
- Informers need `watch` on jobs and pods (Phase 6 RBAC change).
- Fabric8 does not provide cross-resource transactions; the delete-on-failure
  rule is Skaha's responsibility.

## References

- [CADC-15897](https://herzberg.atlassian.net/browse/CADC-15897) and its phase sub-tasks
- [ADR-0001: MetricsDAO single seam](0001-metrics-dao-single-seam.md)
- [Fabric8 Kubernetes Client](https://github.com/fabric8io/kubernetes-client)
