# ADR-0005: Fabric8 as Skaha's Kubernetes client

## Status

Accepted and implemented, 2026-10-23, under
[CADC-15897](https://herzberg.atlassian.net/browse/CADC-15897). Skaha has no
kubectl, client-java or subprocess calls.

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
- **No HTTP retries.** Fabric8's request retry is disabled for every request, so
  a POST is never resent blindly. Skaha retries only where an operation needs
  it; today that is the Job create.
- **Job create.** Retried once by Skaha when the outcome is uncertain (no
  response, 429, 5xx), after reading back whether it landed. The Job name
  contains the per-request random session ID, so a 409 means our own earlier
  attempt landed; read it back and check `canfar.net/id`. A definitive rejection
  (other 4xx) is a server error, not a 503. The UID comes from the create
  response.
- **Partial failure.** If any step fails after the Job exists, delete the Job
  (owner references cascade to its Service and IngressRoute) and return 503.
  Never return a session ID for a launch that cannot run.
- **Caching.** A per-replica informer caches session Jobs (label
  `canfar.net/id`), indexed by owner. It serves a user's session listing, the
  path the portal polls. Jobs this replica created in the last 60 seconds are
  included until the watch delivers them, so a listing right after a launch
  always shows it. Lookups by session ID (session GET, desktop apps, callbacks),
  the max-sessions admission check, and any read before the cache syncs are live
  reads, as is every read while the cache's watch is down. A session this
  replica deleted leaves the listing when the watch delivers the deletion.
  Startup waits at most 30 seconds for the first sync and never fails on it.
  Pods are not cached: their reads (events, logs, attach) are rare.
- **Exact calls.** Logs are one GET per Pod through the client's authenticated
  HTTP client: Fabric8's log DSL first reads the Pod and may watch it. Pod
  metrics are listed as typed `metrics.k8s.io` resources so the session label
  selector (including `!=`) passes through unchanged. Any Kubernetes API failure
  not handled closer to the call is a 503.
- **Secrets.** The image pull Secret is written with server-side apply, which
  needs `patch` on Secrets. The callback signing Secret is created only if
  absent and read once per replica; after an operator replaces it, restart
  Skaha.
- **Preserved.** Operator templates and custom fields (Traefik generic
  resources), Session labels and selectors, the callback signing Secret format,
  and `MetricsDAO` as the single metrics seam with its failure policies.

## Rollout

One PR per phase, each behind its own sub-task of CADC-15897:

| Phase | Change |
| --- | --- |
| 1 | This ADR; managed Fabric8 client; LocalQueue check on Fabric8 |
| 2 | Launch reliability fixes on the current `kubectl` path; session events through Fabric8 |
| 3 | Parent session launch and Secrets through Fabric8 |
| 4 | Desktop-app attach; session Jobs built from Fabric8 models |
| 5 | Renew, pod lookup and logs through Fabric8; `kubectl` removed from code and image |
| 6 | client-java removed; informer-cached session listings |

A migrated operation never falls back to `kubectl`. There is no runtime
client-selection flag.

## Consequences

- The image no longer contains `kubectl`, `docker-ce` or `containerd`, and
  the WAR no longer contains client-java, Netty or the AWS SDK.
- This supersedes the client-java detail in ADR-0001; the `MetricsDAO`
  contract is unchanged.
- commons-compress and the HttpClient 4 `URIBuilder` were transitive through
  client-java; they are now declared at the same versions so session URLs keep
  their exact encoding.
- The session cache needs `watch` on Jobs; server-side apply of the image pull
  Secret needs `patch` on Secrets. Sites with externally managed RBAC must add
  both (chart README lists every verb).
- A listing served by one replica may lag another replica's create by the
  watch delay (normally well under a second); lookups by session ID do not.
- Fabric8 does not provide cross-resource transactions; the delete-on-failure
  rule is Skaha's responsibility.

## References

- [CADC-15897](https://herzberg.atlassian.net/browse/CADC-15897) and its phase sub-tasks
- [ADR-0001: MetricsDAO single seam](0001-metrics-dao-single-seam.md)
- [Fabric8 Kubernetes Client](https://github.com/fabric8io/kubernetes-client)
