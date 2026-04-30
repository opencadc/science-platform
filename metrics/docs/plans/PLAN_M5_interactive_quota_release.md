# Milestone M5: interactive quota release

This plan defines the fifth milestone for the CANFAR Metrics API roadmap. It
adds the first quota metric contract on top of the M4 provider runtime
architecture.

## Repository snapshot versus milestone target

M4 establishes `MetricsRuntime`, complete provider metric methods, typed source
configuration, and a platform-only active API. M5 closes when the `kube`
provider can serve one complete user-scoped quota metric from Kubernetes Pod
labels and container resource specs.

M5 does not make `kube` an alternate `platform` source. The `kube` provider is
the source for `sources.quotas.interactive`, and it returns an
`InteractiveQuota` response through a new quota API route.

## Summary

This milestone introduces:

```text
GET /api/v1/metrics/users/{user}/quotas/interactive
```

The route reports the requested and limited resources for a user's interactive
sessions, split into `fixed` and `flexible` allocation classes. The provider
lists live Kubernetes Pods from configured namespaces, filters by user and
interactive labels, groups matching Pods into logical interactive sessions, and
sums container `resources.requests` and `resources.limits`.

The canonical configuration shape is:

```yaml
metrics:
  providers:
    kube:
      kube_api_url: https://kubernetes.default.svc
      token_file: /var/run/secrets/kubernetes.io/serviceaccount/token
      ca_file: /var/run/secrets/kubernetes.io/serviceaccount/ca.crt
      namespaces:
        - skaha-workload
      pod_selector:
        match_labels:
          canfar.net/workload: interactive
      labels:
        user: canfar.net/user
        sessions:
          - canfar.net/notebook
          - canfar.net/desktop
          - canfar.net/carta
          - canfar.net/contributed
          - canfar.net/skaha
        allocation: canfar.net/allocation
        fixed_value: fixed
        flexible_value: flexible
  sources:
    platform: kueue
    quotas:
      interactive: kube
  cache:
    scope_ttl_seconds:
      platform: 300
      quotas:
        interactive: 2
```

## In scope

This milestone delivers the first quota API family member and keeps Kubernetes
details behind the `kube` provider seam.

- Add `MetricScope.INTERACTIVE_QUOTA` with scope value `quotas.interactive`.
- Add `ProviderMetrics.interactive_quota(user)` as a complete metric method.
- Add typed source config `sources.quotas.interactive: kube`.
- Add typed cache TTL config for `cache.scope_ttl_seconds.quotas.interactive`.
- Add `KubeProviderConfig`, `KubeProvider`, and `KubeMetrics` support for the
  interactive quota scope.
- Add `src/metrics/schemas/quotas.py` for quota response models.
- Add `GET /api/v1/metrics/users/{user}/quotas/interactive`.
- Use an app-level private cache with a default two-second TTL for this scope.
- Query Kubernetes Pods from configured namespaces only.
- Use Kubernetes label selectors to filter by user and configured interactive
  Pod labels before downloading Pod objects.
- Preserve arbitrary Kubernetes resource names in `requests` and `limits`.
- Count logical interactive sessions, not Pods.
- Emit provider telemetry counters for skipped Pods while keeping skipped Pod
  details out of the API response.

## Out of scope

This milestone avoids broader quota and workload metrics until their contracts
are clear.

- Platform metrics from the `kube` provider.
- `GET /api/v1/metrics/users/{user}/quotas` collection responses.
- Job, JobSet, or individual job metrics.
- User allotment, entitlement, limit, or available quota calculation.
- Namespace parameters in the public API.
- Watch-backed indexes or informer-style synchronization.
- Per-provider cache backends.

## Dependencies

This milestone depends on the provider runtime and Kubernetes deployment
contracts already established by earlier milestones.

- M1 delivery foundation.
- M2 platform route and Kueue quantity behavior.
- M3 package realignment.
- M4 provider runtime architecture.
- Kubernetes RBAC that lets the service list Pods in configured namespaces.

## API contract

The public route is user-scoped and keeps namespace details out of the API:

```text
GET /api/v1/metrics/users/{user}/quotas/interactive
```

The response kind is `InteractiveQuota`. The response data contains only
observed Kubernetes requests, limits, and logical session counts.

```json
{
  "version": "metrics.canfar.net/v1",
  "kind": "InteractiveQuota",
  "metadata": {
    "created": "2026-04-26T12:00:00Z"
  },
  "status": "Success",
  "data": {
    "scope": "quotas.interactive",
    "cluster": "science-platform",
    "user": "alice",
    "fixed": {
      "count": 2,
      "requests": {
        "cpu": "4",
        "memory": "12Gi"
      },
      "limits": {
        "cpu": "4",
        "memory": "12Gi"
      }
    },
    "flexible": {
      "count": 3,
      "requests": {
        "cpu": "1.5",
        "memory": "6Gi"
      },
      "limits": {
        "cpu": "12",
        "memory": "48Gi"
      }
    }
  }
}
```

`requests` and `limits` map directly to Kubernetes container
`resources.requests` and `resources.limits`. `count` is the number of distinct
logical interactive session identities in that allocation class. Missing
resource names are omitted from the relevant map.

## Kubernetes query model

The `kube` provider uses live list-on-request reads in M5. It does not build a
watch-backed local index yet.

For each request, the provider:

1. Builds a Kubernetes label selector from `pod_selector.match_labels`, the
   configured `labels.user` key, and the route `{user}` value.
2. Lists Pods from each configured namespace with that selector.
3. Keeps only Pods that contain exactly one of the configured session label
   keys and a valid allocation label.
4. Classifies the Pod as `fixed` or `flexible` from `labels.allocation`.
5. Groups Pods by the matching session label value.
6. Sums container `resources.requests` and `resources.limits` across all Pods
   and containers in each allocation class.
7. Returns total `fixed` and `flexible` counts and resource maps.

The route `{user}` value is assumed to match the Kubernetes user label value
directly and to be a valid Kubernetes label value.

## Label contract

Provider configuration owns label names and category values because platform
labels may change over time.

- `labels.user` identifies the user label key.
- `labels.sessions` lists the session label keys that can identify logical
  interactive sessions.
- Each matching Pod must have exactly one configured session label key.
- `labels.allocation` identifies whether the session is `fixed` or `flexible`.
- `fixed_value` and `flexible_value` define the allocation label values.
- Pods without the required user, session, or allocation labels are ignored in
  the API response.

Provider telemetry must count skipped Pods by reason so label drift is visible
without widening the response contract.

## Cache policy

The cache remains an app-level module. The `kube` provider does not own cache
storage or cache key formatting.

Interactive quota responses are user-scoped and must use private HTTP caching:

```text
Cache-Control: private, max-age=2
```

The default internal cache TTL for `quotas.interactive` is two seconds. Runtime
cache keys include the scope, provider name, provider fingerprint, and a hashed
user value.

## Provider contract

M5 extends the M4 provider contract without reintroducing partial source
composition.

`KubeMetrics` advertises `MetricScope.INTERACTIVE_QUOTA` and implements:

```python
async def interactive_quota(self, user: str) -> InteractiveQuotaData:
    ...
```

The method returns a complete `InteractiveQuotaData` model. Runtime routes and
cache code do not know Kubernetes label semantics, Pod shapes, or resource
aggregation rules.

## Implementation phases

This sequence keeps the new quota metric separate from platform behavior.

1. Add quota schema models in `src/metrics/schemas/quotas.py`.
2. Extend provider base types with `MetricScope.INTERACTIVE_QUOTA` and
   `ProviderMetrics.interactive_quota(user)`.
3. Extend settings with `sources.quotas.interactive`, nested cache TTLs, and
   `KubeProviderConfig` label, namespace, and HTTP settings.
4. Implement `KubeProvider` startup validation for namespace and label config.
5. Implement namespace-scoped Pod list calls with user and interactive label
   selectors.
6. Implement session grouping, fixed/flexible classification, and resource
   request/limit aggregation.
7. Add the quota route and route-level private cache headers.
8. Add unit tests for selectors, grouping, resource parsing, cache keys, and
   response shape.
9. Add Kubernetes integration fixtures for fixed and flexible interactive Pods.
10. Update docs and run review checkpoints before milestone closure.

## Validation plan

Validation must prove that M5 serves the new quota metric without weakening the
M4 provider architecture.

- Run gate `harness-contracts`.
- Run gate `repository-coverage`.
- Run gate `harness-cli`.
- Run `uv run pytest -q`.
- Validate startup fails when `providers.kube.namespaces` is empty.
- Validate startup fails when required label config is missing.
- Validate source capability checks reject `sources.quotas.interactive` unless
  the selected provider advertises `INTERACTIVE_QUOTA`.
- Validate request-time Kubernetes calls include the user label selector.
- Validate fixed and flexible session counts are distinct logical session
  counts.
- Validate requests and limits preserve arbitrary resource names.
- Validate missing resource names are omitted from the relevant map.
- Validate user-scoped responses use private cache headers.
- Validate app-level cache TTL defaults to two seconds for
  `quotas.interactive`.

## Future work

M5 deliberately uses list-on-request for implementation clarity and a small
two-second cache for request bursts.

A later milestone can replace request-time listing with a watch-backed index:

1. Perform an initial Pod list during provider startup.
2. Maintain an in-memory index keyed by user, session, and allocation class.
3. Process Pod `ADDED`, `MODIFIED`, and `DELETED` events to update aggregates.
4. Serve quota requests from memory without Kubernetes API calls.
5. Reconnect and relist on watch expiration or transport failure.

That future work should also evaluate Kubernetes API backpressure controls,
per-provider concurrency limits, and stale-index telemetry.

## Risks

This milestone relies on label correctness and efficient Pod selection.

- **Label drift:** If platform labels change, Pods can be skipped. Mitigate with
  typed label config, startup validation, and skipped-Pod telemetry.
- **Large namespace scans:** If the selector is too broad, Pod list responses
  can be expensive. Mitigate by requiring an interactive `pod_selector` and
  user label selector.
- **Ambiguous session count:** If Pods ever carry more than one configured
  session label, counts can drift. Mitigate by requiring exactly one session
  label per Pod and counting violations in telemetry.
- **Cache privacy:** User quota data must not enter shared caches. Mitigate with
  private cache headers and user-specific internal cache keys.

## Operational controls

Operators configure the Kubernetes search space and labels explicitly.

- `providers.kube.namespaces` is required and is not exposed in the API.
- RBAC grants `list` Pod access only in configured namespaces.
- User values are passed directly into Kubernetes label selectors.
- The route has no namespace parameter and returns no namespace breakdown.
- HTTP cache headers are private for user-scoped quota responses.

## Implementer handoff checklist

Use this checklist to close the milestone.

- [ ] `sources.quotas.interactive: kube` is implemented and validated.
- [ ] `KubeMetrics.interactive_quota(user)` returns complete quota data.
- [ ] `GET /api/v1/metrics/users/{user}/quotas/interactive` is implemented.
- [ ] Fixed and flexible aggregates include `count`, `requests`, and `limits`.
- [ ] App-level cache TTL for `quotas.interactive` defaults to two seconds.
- [ ] Response cache headers are private.
- [ ] Kubernetes list calls are namespace-scoped and user-filtered.
- [ ] Skipped Pod telemetry exists without API response fields.
- [ ] Watch-backed index work is documented as a future improvement.
- [ ] Required gates and review checkpoints pass.
