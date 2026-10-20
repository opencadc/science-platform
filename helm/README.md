# skaha

A Helm chart to install the Skaha web service of the CANFAR Science Platform

| Chart | AppVersion | Type |
|:-----:|:----------:|:----:|
|1.10.1<!-- x-release-please-version --> | 1.6.0 | application |

## Requirements

| Repository | Name | Version |
|------------|------|---------|
| oci://registry-1.docker.io/bitnamicharts | redis | ^18.19.0 |

## Values

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| autoscaling.enabled | bool | `true` | Enable HorizontalPodAutoscaler for skaha-tomcat. Target and scaling behavior are intentionally chart-managed (CPU 65%). |
| autoscaling.maxReplicas | int | `6` | Maximum number of skaha-tomcat replicas. |
| autoscaling.minReplicas | int | `2` | Minimum number of skaha-tomcat replicas. |
| deployment.hostname | string | `"myhost.example.com"` | Public hostname for the Skaha API. |
| deployment.skaha.annotations | object | `{}` | Annotations added to the Skaha API Deployment metadata. |
| deployment.skaha.apiVersion | string | `"v1"` | Skaha API version path segment (for example, `v1` -> `/skaha/v1/...`). |
| deployment.skaha.cookieSignaturePublicKey.existingSecret.name | string | `""` |  |
| deployment.skaha.cookieSignaturePublicKey.existingSecret.path | string | `""` |  |
| deployment.skaha.defaultQuotaGB | string | `"10"` | Default user storage quota in GiB for first-time users. |
| deployment.skaha.identityManagerClass | string | `"org.opencadc.auth.StandardIdentityManager"` | Java IdentityManager implementation used for authentication. |
| deployment.skaha.image | string | `"images.opencadc.org/platform/skaha:1.6.0"` | Container image for the Skaha API service. |
| deployment.skaha.imageCache.refreshSchedule | string | `"*/30 * * * *"` | Cron schedule used to refresh cached images. |
| deployment.skaha.imagePullPolicy | string | `"Always"` | Image pull policy for the Skaha API container. |
| deployment.skaha.init.image | string | `"busybox:1.37.0"` | Init container image used to bootstrap user storage paths. |
| deployment.skaha.init.imagePullPolicy | string | `"IfNotPresent"` | Image pull policy for the bootstrap init container. |
| deployment.skaha.podAnnotations | object | `{}` | Annotations added to the Skaha API Pod template metadata. |
| deployment.skaha.posixMapperCacheTTLSeconds | string | `"86400"` | TTL in seconds for cached POSIX mapper entries. |
| deployment.skaha.posixUserGroupCacheTTLSeconds | string | `"86400"` | TTL in seconds for the in-process POSIX user-group (URI to GID) cache. |
| deployment.skaha.registryHosts | string | `"images.canfar.net"` | Space-delimited list of image registry hosts allowed for sessions. |
| deployment.skaha.resources.limits.cpu | string | `"2000m"` | CPU limit for the Skaha API container. |
| deployment.skaha.resources.limits.memory | string | `"3Gi"` | Memory limit for the Skaha API container. |
| deployment.skaha.resources.requests.cpu | string | `"1000m"` | CPU request for the Skaha API container. |
| deployment.skaha.resources.requests.memory | string | `"2Gi"` | Memory request for the Skaha API container. |
| deployment.skaha.sessions | object | `{"authorization":{"group":{"enabled":false,"uri":""},"permissionsAPI":{"authAPIBaseURL":"","baseURL":"","enabled":false,"name":"skaha","version":""}},"expirySeconds":"345600","flexResourceRequests":{"headless":{"cpuCores":"1","memoryInGB":"4"}},"headlessPriorityClass":{"create":false,"description":"For high-priority headless jobs. Preempting.","globalDefault":false,"preemptionPolicy":"PreemptLowerPriority","value":2000},"imagePullPolicy":"Always","ingress":{"customResponseHeaders":{},"tls":{}},"initContainerImage":"redis:8.2.2-bookworm","kueue":{"api":{"group":"kueue.x-k8s.io","version":"v1beta2"},"rbac":{"create":false}},"limitRange":{"create":true,"enabled":false},"maxCount":"5","maxEphemeralStorage":"200Gi","minEphemeralStorage":"20Gi","namespace":{"create":false,"name":"skaha-workload"},"nodeLabelSelector":null,"priorityClass":{"create":false,"description":"For high-priority user pods. Preempting.","globalDefault":false,"name":"uber-user-preempt-high","preemptionPolicy":"PreemptLowerPriority","value":2000},"tolerations":[],"userStorage":{"homeDirectory":"home","projectsDirectory":"projects","spec":{},"topLevelDirectory":"/cavern"}}` | @deprecated Prefer deployment.skaha.sessions.authorization or deprecated usersGroup alone. Still honoured when sessions.authorization.group.enabled is false. The IVOA GMS Group URI to verify users against for permission to use the Science Platform. See https://www.ivoa.net/documents/GMS/20220222/REC-GMS-1.0.html#tth_sEc3.2 usersGroup: "ivo://example.org/gms?prototyping-groups/mini-src/platform-users" Group URI for users to ensure priority for their headless jobs. See https://www.ivoa.net/documents/GMS/20220222/REC-GMS-1.0.html#tth_sEc3.2 headlessPriorityGroup: "ivo://example.org/gms?skaha-priority-headless-users" Array of GMS Group URIs allowed to set the logging level.  If none set, then nobody can change the log level. See https://www.ivoa.net/documents/GMS/20220222/REC-GMS-1.0.html#tth_sEc3.2 for GMS Group URIs See https://github.com/opencadc/core/tree/main/cadc-log for Logging control loggingGroups:   - "ivo://example.org/gms?prototyping-groups/mini-src/platform-users" The Resource ID (URI) of the Service that contains the Posix Mapping information posixMapperResourceID: "ivo://example.org/posix-mapper" URI or URL of the OIDC (IAM) server.  Used to validate incoming tokens. oidcURI: https://iam.example.org/ The Resource ID (URI) of the GMS Service. gmsID: ivo://example.org/gms The absolute URL of the IVOA Registry where services are registered registryURL: https://spsrc27.iaa.csic.es/reg This applies to Skaha itself.  Meaning, this Pod will be scheduled as described by the nodeAffinity clause. Note the different indentation level compared to the sessions.nodeAffinity. See https://kubernetes.io/docs/tasks/configure-pod-container/assign-pods-nodes-using-node-affinity/ nodeAffinity: {} Settings for User Sessions.  Sensible defaults supplied, but can be overridden. For units of storage, see https://kubernetes.io/docs/concepts/configuration/manage-resources-containers/#meaning-of-memory. |
| deployment.skaha.sessions.authorization.group.enabled | bool | `false` | When true, SKAHA_USERS_GROUP is set from uri. Required uri when enabled (Helm validates). The Skaha service enforces mutually exclusive authorization modes at runtime. |
| deployment.skaha.sessions.authorization.permissionsAPI.authAPIBaseURL | string | `""` | Auth API base URL for the permissions client. Written to cadc-registry.properties as ivo://skao.int/std/AuthAPI when enabled. Required when enabled (Helm validates). |
| deployment.skaha.sessions.authorization.permissionsAPI.baseURL | string | `""` | Permissions API base URL. Written to cadc-registry.properties as ivo://skao.int/std/PermissionsAPI when enabled. Required when enabled (Helm validates). |
| deployment.skaha.sessions.authorization.permissionsAPI.enabled | bool | `false` | When true, enables permissions-API session authorization. Sets SKAHA_SESSIONS_AUTHORIZATION_PERMISSIONS_API_ENABLED plus SKAHA_PERMISSIONS_API_NAME and optional SKAHA_PERMISSIONS_API_VERSION. Required baseURL and authAPIBaseURL are written to cadc-registry.properties (not environment variables). Helm validates baseURL and authAPIBaseURL when enabled. Mutually exclusive with group mode at runtime. |
| deployment.skaha.sessions.authorization.permissionsAPI.name | string | `"skaha"` | Service name passed to the permissions API (SKAHA_PERMISSIONS_API_NAME). |
| deployment.skaha.sessions.authorization.permissionsAPI.version | string | `""` | Policy version passed to the permissions API (SKAHA_PERMISSIONS_API_VERSION). Omitted from the Deployment when empty; Skaha defaults to "1". |
| deployment.skaha.sessions.expirySeconds | string | `"345600"` | Session lifetime in seconds before expiry and shutdown. |
| deployment.skaha.sessions.flexResourceRequests.headless.cpuCores | string | `"1"` | Default CPU request (cores) for flex headless sessions. |
| deployment.skaha.sessions.flexResourceRequests.headless.memoryInGB | string | `"4"` | Default memory request (GiB) for flex headless sessions. |
| deployment.skaha.sessions.headlessPriorityClass | object | `{"create":false,"description":"For high-priority headless jobs. Preempting.","globalDefault":false,"preemptionPolicy":"PreemptLowerPriority","value":2000}` | Effective headless PriorityClass configuration (merged with legacy headlessPriorityClass). |
| deployment.skaha.sessions.imagePullPolicy | string | `"Always"` | Image pull policy applied to all user session containers. |
| deployment.skaha.sessions.ingress.customResponseHeaders | object | `{}` | Custom response headers added to user-session ingress responses. |
| deployment.skaha.sessions.ingress.tls | object | `{}` | TLS configuration for the user-session Traefik IngressRoute. |
| deployment.skaha.sessions.initContainerImage | string | `"redis:8.2.2-bookworm"` | Image used by the session init container that manages POSIX data. |
| deployment.skaha.sessions.kueue | object | `{"api":{"group":"kueue.x-k8s.io","version":"v1beta2"},"rbac":{"create":false}}` | Per-session-type Kueue configuration. |
| deployment.skaha.sessions.kueue.api | object | `{"group":"kueue.x-k8s.io","version":"v1beta2"}` | Kueue CustomObjects API (LocalQueues): sets SKAHA_KUEUE_API_VERSION and SKAHA_KUEUE_API_GROUP on the Skaha Deployment. |
| deployment.skaha.sessions.kueue.api.group | string | `"kueue.x-k8s.io"` | Kueue API group CRDs belong to. |
| deployment.skaha.sessions.kueue.api.version | string | `"v1beta2"` | Kueue API version segment for LocalQueue lookups (override e.g. to v1beta1 for older Kueue installs). |
| deployment.skaha.sessions.limitRange.create | bool | `true` | When true together with `enabled`, the chart creates the LimitRange `<release>-session-limit-range` in the workload namespace from `spec` below. When false, do not create one here; ensure the namespace has a LimitRange Skaha can use (Skaha uses the first LimitRange returned; the name is mostly irrelevant). |
| deployment.skaha.sessions.limitRange.enabled | bool | `false` | When true, set `SKAHA_SESSION_LIMIT_RANGE_ENABLED` on the Skaha deployment so it loads the **first** `LimitRange` in the workload namespace (name is mostly irrelevant). Also gates this chart's LimitRange Role/RoleBinding (`rbac.create`). Must also be true (with `create`) for the chart to render the LimitRange manifest. |
| deployment.skaha.sessions.maxCount | string | `"5"` | Maximum number of active sessions allowed per user. |
| deployment.skaha.sessions.maxEphemeralStorage | string | `"200Gi"` | Maximum ephemeral storage limit for sessions (non-desktop). |
| deployment.skaha.sessions.minEphemeralStorage | string | `"20Gi"` | Initial ephemeral storage request for new sessions (non-desktop). |
| deployment.skaha.sessions.nodeLabelSelector | string | `nil` | Node label selector used when discovering schedulable worker nodes. |
| deployment.skaha.sessions.priorityClass | object | `{"create":false,"description":"For high-priority user pods. Preempting.","globalDefault":false,"name":"uber-user-preempt-high","preemptionPolicy":"PreemptLowerPriority","value":2000}` | PriorityClass assigned to the Skaha API Pod. |
| deployment.skaha.sessions.tolerations | list | `[]` | Tolerations applied to user session Pods. |
| deployment.skaha.sessions.userStorage.homeDirectory | string | `"home"` | Relative path under topLevelDirectory for user home directories. |
| deployment.skaha.sessions.userStorage.projectsDirectory | string | `"projects"` | Relative path under topLevelDirectory for shared projects storage. |
| deployment.skaha.sessions.userStorage.topLevelDirectory | string | `"/cavern"` | Absolute mount path containing user home and projects directories. |
| experimentalFeatures.enabled | bool | `false` | Enable processing of experimental feature gates. |
| grafanaDashboards.annotations | object | `{}` | Extra annotations added to every dashboard ConfigMap. |
| grafanaDashboards.datasourceName | string | `"Mimir"` | Display name shown for `datasourceUid` in the data source picker. |
| grafanaDashboards.datasourceUid | string | `"mimir"` | Uid of the Prometheus-compatible data source the dashboards query. |
| grafanaDashboards.enabled | bool | `false` | Render the CANFAR Grafana dashboard ConfigMaps. |
| grafanaDashboards.folder | string | `"CANFAR Science Platform"` | Grafana folder the dashboards are filed under, via the `grafana_folder` annotation. |
| grafanaDashboards.lokiDatasourceName | string | `"Loki"` | Display name shown for `lokiDatasourceUid` in the logs source picker. |
| grafanaDashboards.lokiDatasourceUid | string | `"loki"` | Uid of the Loki data source backing the Session Drilldown logs panel. |
| grafanaDashboards.namespace | string | `"canfar-dashboards"` | Namespace the dashboard ConfigMaps are created in. Empty falls back to the release namespace. |
| grafanaDashboards.namespaceCreate | bool | `false` | Create `namespace` if it differs from the release namespace. Leave false where namespaces are managed externally. |
| grafanaDashboards.scrapeIntervalSeconds | int | `15` | Scrape interval of container metrics, in seconds, used to integrate gauges into hours. |
| grafanaDashboards.sidecarLabel | string | `"grafana_dashboard"` | Label the Grafana sidecar selects on. |
| grafanaDashboards.sidecarLabelValue | string | `"1"` | Value for `sidecarLabel`. |
| ingress.enabled | bool | `true` | Enable ingress routing for the Skaha API. |
| ingress.path | string | `"/skaha"` | Ingress path prefix routed to the Skaha API Service. |
| kubernetesClusterDomain | string | `"cluster.local"` | Kubernetes DNS domain used when building internal service hostnames. |
| livenessProbe | object | `{"httpGet":{"path":"/skaha/availability","port":"http"},"periodSeconds":60}` | HTTP liveness probe for the Skaha API container. |
| metrics.clusterName | string | `""` | Required stable lower-case DNS identity for this Metrics deployment. It scopes cache identities and must match the Prometheus/Mimir cluster label. |
| metrics.env | object | `{}` | Map of METRICS_* environment variables for the Metrics container. GitOps supplies per-environment values — at minimum METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES and METRICS_PROVIDERS__KUEUE__NAMESPACES (JSON array strings, for example '["cq-a"]' and '["canfar-workloads"]'). Kubernetes endpoint, credentials, and CA are discovered by kr8s from the pod ServiceAccount and are not settings. See metrics/docs/environment-contracts.md. |
| metrics.image.pullPolicy | string | `"IfNotPresent"` | imagePullPolicy for the Metrics API container. |
| metrics.image.repository | string | `"images.opencadc.org/platform/metrics"` | Metrics container image repository. |
| metrics.image.tag | string | `"v0.1.7"` | Metrics container image tag. Note the env contract boundary: images after v0.1.5 discover the Kubernetes endpoint/credentials/CA via kr8s from the pod ServiceAccount and reject the removed transport settings listed under `metrics.env`. |
| metrics.ingress.enabled | bool | `false` | When true and top-level ingress.enabled is true, add a path on the same host routing to the Metrics Service. |
| metrics.ingress.path | string | `"/apis/canfar.net/v1alpha1/metrics"` | Ingress path prefix for the Metrics API. It is forwarded unchanged, so it must match the FastAPI route prefix unless the ingress controller is configured with an external rewrite. |
| metrics.networkPolicy | object | `{"egress":{"dns":[{"ports":[{"port":53,"protocol":"UDP"},{"port":53,"protocol":"TCP"}]}],"kubeApiServer":[],"otlp":[],"promql":[],"redis":[]},"enabled":true,"ingress":{"additional":[],"skaha":{"ports":[{"port":8000,"protocol":"TCP"}]}}}` | Isolate the co-deployed Metrics API. Egress to every Pod in the release namespace, including the bundled Redis, is always allowed. Empty operator-supplied destination lists deny that traffic. |
| metrics.networkPolicy.egress.dns | list | `[{"ports":[{"port":53,"protocol":"UDP"},{"port":53,"protocol":"TCP"}]}]` | DNS egress on port 53 to any destination, so node-local DNS caches on a host link-local address work as well as CoreDNS Pods. Keep both protocols because resolver behavior is environment-dependent. |
| metrics.networkPolicy.egress.kubeApiServer | list | `[]` | Kubernetes API destinations, required for readiness. Match what the CNI enforces: Cilium evaluates the API server endpoint after Service translation, so use its port (for example a port-only `6443/TCP` rule) rather than the `kubernetes` Service ClusterIP. |
| metrics.networkPolicy.egress.otlp | list | `[]` | Optional metrics-only OTLP destinations. Leave empty when application-state telemetry is disabled. |
| metrics.networkPolicy.egress.promql | list | `[]` | Optional Prometheus/Mimir destinations. Leave empty when efficiency is disabled. |
| metrics.networkPolicy.egress.redis | list | `[]` | Redis destinations for an external `metrics.redis.url` outside the release namespace. |
| metrics.networkPolicy.enabled | bool | `true` | Create a default-deny Metrics NetworkPolicy for the co-deployed Metrics API. |
| metrics.networkPolicy.ingress | object | `{"additional":[],"skaha":{"ports":[{"port":8000,"protocol":"TCP"}]}}` | Allow only the co-deployed Skaha API to call Metrics; the template derives its exact `run` selector from the release name. |
| metrics.networkPolicy.ingress.additional | list | `[]` | Additional raw NetworkPolicy ingress rules. These are appended unchanged. |
| metrics.networkPolicy.ingress.skaha.ports | list | `[{"port":8000,"protocol":"TCP"}]` | Port rules for the co-deployed Skaha caller. An explicitly empty list denies this caller. |
| metrics.otlp | object | `{"endpoint":""}` | Optional external OTLP/HTTP endpoint for Metrics application-state metrics. When set, the chart enables OTLP metrics export. |
| metrics.platformName | string | `"canfar"` | Public Metrics platform subject (path .../platform/{name} and Skaha client). Default canfar. Sets METRICS_PLATFORM_NAME and SKAHA_METRICS_PLATFORM_NAME unless overridden in env maps. |
| metrics.prometheus | object | `{"url":""}` | Optional Prometheus/Mimir HTTP(S) endpoint for server-owned efficiency queries. When set, the chart enables the fixed PromQL provider. |
| metrics.rbac.enabled | bool | `true` | When true, create the least-privilege ClusterQueue and configured-namespace LocalQueue RBAC. Set false to provide those permissions out of band. |
| metrics.rbac.namespaces | list | `[]` | Namespaces in which Metrics lists LocalQueues for User metrics. Required; these must match METRICS_PROVIDERS__KUEUE__NAMESPACES when both are supplied. |
| metrics.redis.url | string | `""` | Optional `redis://` or `rediss://` URL for the Metrics cache. Empty uses this release's bundled Redis (`<release>-redis-master`, database 0), shared with Skaha. |
| metrics.replicaCount | int | `1` | Fixed replica count for the Metrics API (no HPA in this chart version). |
| metrics.resources | object | `{"limits":{"cpu":"1","memory":"1Gi"},"requests":{"cpu":"100m","memory":"256Mi"}}` | Resource requests and limits for the Metrics API container. |
| metrics.revisionHistoryLimit | int | `3` | revisionHistoryLimit for the Metrics API Deployment. |
| metrics.test.enabled | bool | `true` | Run helm test hook that retries /readyz until success. |
| metrics.test.image | string | `"busybox:1.37.0"` | Image for the helm test hook Pod. |
| metrics.test.maxWaitSeconds | int | `180` | Maximum seconds to wait for Metrics /readyz (should exceed startupProbe worst case plus scheduling margin). |
| podSecurityContext | object | `{}` |  |
| rbac.clusterRole.create | bool | `true` |  |
| rbac.create | bool | `true` |  |
| readinessProbe | object | `{"httpGet":{"path":"/skaha/availability","port":"http"},"periodSeconds":15}` | HTTP readiness probe for the Skaha API container. |
| redis.architecture | string | `"standalone"` | Redis deployment architecture. |
| redis.auth.enabled | bool | `false` | Enable Redis authentication. |
| redis.image.repository | string | `"redis"` | Redis image repository used by the bundled chart dependency. |
| redis.image.tag | string | `"8.2.2-bookworm"` | Redis image tag used by the bundled chart dependency. |
| redis.master.containerSecurityContext.allowPrivilegeEscalation | bool | `false` | Disallow privilege escalation in the Redis master container. |
| redis.master.containerSecurityContext.capabilities.drop | list | `["ALL"]` | Linux capabilities dropped from the Redis master container. |
| redis.master.containerSecurityContext.readOnlyRootFilesystem | bool | `true` | Mount Redis master root filesystem as read-only. |
| redis.master.containerSecurityContext.runAsGroup | int | `1001` | Group ID for the Redis master container. |
| redis.master.containerSecurityContext.runAsNonRoot | bool | `true` | Require Redis master to run as a non-root user. |
| redis.master.containerSecurityContext.runAsUser | int | `1001` | User ID for the Redis master container. |
| redis.master.containerSecurityContext.seccompProfile.type | string | `"RuntimeDefault"` | Seccomp profile type for Redis master. |
| redis.master.persistence.enabled | bool | `false` | Enable persistence for the Redis master StatefulSet. |
| replicaCount | int | `1` | Number of skaha-tomcat replicas when autoscaling is disabled. |
| secrets | string | `nil` |  |
| securityContext | object | `{}` | Optional Pod-level security context for the Skaha API Deployment. |
| service.port | int | `8080` | Service port exposed for the Skaha API Service. |
| serviceAccount | object | `{"annotations":{},"automount":true,"create":true,"name":""}` | Shared ServiceAccount used by Skaha, Metrics, chart Jobs, and Helm test Pods. |
| startupProbe | object | `{"failureThreshold":30,"httpGet":{"path":"/skaha/availability","port":"http"},"initialDelaySeconds":10,"periodSeconds":5,"timeoutSeconds":30}` | HTTP startup probe for the Skaha API container. |
| telemetry.controller | bool | `false` | Enable OpenTelemetry metrics for the Skaha controller Tomcat workload (`OTEL_SERVICE_NAME=skaha-controller`). Requires telemetry.otlp.destination. |
| telemetry.metrics | bool | `false` | Reserved for future OpenTelemetry metrics emitted by the Python metrics backend (`OTEL_SERVICE_NAME=skaha-metrics`). Must remain false in this chart version. |
| telemetry.otlp.destination | string | `""` | OTLP HTTP collector endpoint where enabled telemetry services POST metrics. |
| telemetry.otlp.interval | int | `30` | Export interval in seconds for enabled telemetry services. |
| tolerations | list | `[]` | Tolerations applied to the Skaha API Pod. |

## User storage (Cavern)

User home allocations in Cavern are configured under `deployment.skaha.sessions.userStorage`. Set **`nodeURIPrefix`** to a **`vos://` scheme** URI. The host (authority) and Cavern service name are separated by a tilde (`~`).

Example:

```yaml
deployment:
  skaha:
    sessions:
      userStorage:
        nodeURIPrefix: "vos://storage.example.com~cavern"
        homeDirectory: "home"
        topLevelDirectory: "/cavern"
```

Skaha sets `SKAHA_USER_STORAGE_USER_HOME_URI` to the prefix plus `homeDirectory`, for example **`vos://storage.example.com~cavern/home`**. That URI identifies the VOSpace folder where per-user ContainerNodes are created (a value such as `vos://storage.example.com~cavern/home/` refers to the same home folder; trailing slashes are normalized).

Skaha resolves the backing Cavern service for registry lookup as **`ivo://storage.example.com/cavern`**: the `ivo://` authority matches the `vos://` host, and the service path is the segment immediately after `~` (`cavern`), not the subsequent node path (`/home`).

## Session authorization

Platform access for user sessions is configured under `deployment.skaha.sessions.authorization`. Enable **exactly one** mode: `group` or `permissionsAPI`. The deprecated `deployment.skaha.usersGroup` value still enables group mode when `authorization.group.enabled` is false.

### Group mode (`authorization.group`)

When `group.enabled` is true, Helm sets `SKAHA_SESSIONS_AUTHORIZATION_GROUP_ENABLED=true` and `SKAHA_USERS_GROUP` from `group.uri`. Skaha checks GMS group membership.

### Permissions API mode (`authorization.permissionsAPI`)

When `permissionsAPI.enabled` is true, Helm sets `SKAHA_SESSIONS_AUTHORIZATION_PERMISSIONS_API_ENABLED=true`, `SKAHA_PERMISSIONS_API_NAME` from `name`, and `SKAHA_PERMISSIONS_API_VERSION` when `version` is non-empty.

API endpoint URLs are **not** passed as environment variables. Instead, Helm writes them into the mounted `cadc-registry.properties` ConfigMap:

| Value field | Registry entry |
|-------------|------------------|
| `authAPIBaseURL` | `ivo://skao.int/std/AuthAPI` |
| `baseURL` | `ivo://skao.int/std/PermissionsAPI` |

Skaha resolves those standard IDs at runtime via `LocalAuthority` and authorizes each request using the incoming HTTP method and route path. The former `type`, `route`, and `method` values are no longer configurable.

Example:

```yaml
deployment:
  skaha:
    sessions:
      authorization:
        permissionsAPI:
          enabled: true
          baseURL: "https://permissions.example.org/v1/"
          authAPIBaseURL: "https://auth.example.org/v1/"
          name: "skaha"
          version: "1"
```

API specification: https://permissions.srcnet.skao.int/api/openapi.json

## Skaha OpenTelemetry

Skaha OpenTelemetry is off by default. To enable v1 metrics export for the Tomcat controller workload, set `telemetry.controller: true` and `telemetry.otlp.destination` to the ops-provided OTLP HTTP collector endpoint.

When enabled, the chart attaches the image-bundled OpenTelemetry Java agent with `CATALINA_OPTS` and emits standard `OTEL_*` environment variables for metrics-only OTLP export. Skaha controller metrics always use `OTEL_SERVICE_NAME=skaha-controller`; `telemetry.metrics` is reserved for the future Python metrics backend name `skaha-metrics` and must remain false in this chart version. Traces and logs are explicitly disabled with `OTEL_TRACES_EXPORTER=none` and `OTEL_LOGS_EXPORTER=none`; v1 does not deploy a collector or add Prometheus scrape endpoints.

```yaml
telemetry:
  controller: true
  metrics: false
  otlp:
    destination: "http://otel-collector.observability.svc.cluster.local:4318"
    interval: 30
```

## Harbor publishing (Science Platform CI)

Successful Skaha image releases (`CD: Skaha Release Build`) also package this chart (`helm dependency build`, `helm package`) and upload the `.tgz` to Harbor via the **classic Helm chartrepo API**:

`POST {base}/chartrepo/{project}/charts` (multipart `chart=@skaha-<version>.tgz`), **not** `helm push oci://`.

That publishes to the Harbor **Helm Charts** tab. Repo variables (defaults in the workflow):

- **`HARBOR_HELM_BASE_URL`** — HTTPS origin only (example `https://images.opencadc.org`), when it differs from `https://<Docker registry hostname>`.
- **`HARBOR_HELM_REPO_PROJECT`** — Harbor project that hosts Helm charts (default `platform`).

Uses the same `SKAHA_REGISTRY_USERNAME` / `SKAHA_REGISTRY_TOKEN` secrets as Docker login.

## Cookie verification public key (RSA)

Deployments that rely on verifying browser cookies signed with platform RSA material can mount **only the public half** via `deployment.skaha.cookieSignaturePublicKey.existingSecret` (Kubernetes Secret, release namespace—better optics than a ConfigMap plain-text entry). The projected volume always reads Secret data key **`RsaSignaturePub.key`**.

1. Create the Secret outside Helm (do not paste PEM into `values.yaml` in prod). Store the PEM under **`RsaSignaturePub.key`**, for example:

   `kubectl create secret generic my-skaha-cookie-verify-pub -n <release-namespace> --from-file=RsaSignaturePub.key=./public.pem`

2. Reference the Secret from values (optional **`existingSecret.path`** overrides the basename under `/config`; default **`RsaSignaturePub.key`**):

```yaml
deployment:
  skaha:
    cookieSignaturePublicKey:
      existingSecret:
        name: my-skaha-cookie-verify-pub
        path: RsaSignaturePub.key
```

Omit `existingSecret.name` (leave default empty) when you do not use this stack—the pod still uses WAR classpath material if packaged in the image.

**Runtime note:** Signed session callbacks also read RSA key material from workload Secret **`pre-auth-token-skaha`** (data keys **`public`** / **`private`**). Confirm with ops which path your environment uses (`/config/RsaSignaturePub.key`, that Secret, classpath, or combinations).

## metrics install ordering

The Metrics API is required and always installed with Skaha. The chart emits one shared Skaha/Metrics `ServiceAccount`, a Kueue `ClusterRole`/`ClusterRoleBinding`, one namespaced `Role`/`RoleBinding` per configured LocalQueue namespace, the Metrics `Service`, the Metrics `NetworkPolicy`, and the Metrics `Deployment`. Setting `metrics.enabled=false` fails the render. Skaha, Metrics, chart-owned Jobs, and test Pods all use the same ServiceAccount resolved by `serviceAccount` or `deployment.skaha.serviceAccountName`. There is no Metrics-specific ServiceAccount setting. The Metrics bindings add configured ClusterQueue and namespaced Kueue/Job/Pod reads to that shared identity. Bitnami Redis retains its dependency-managed identity. Configure the JSON namespace list with `METRICS_PROVIDERS__KUEUE__NAMESPACES` or `metrics.rbac.namespaces`; when both are supplied they must match. Helm applies manifest groups in a deterministic [kind order](https://github.com/helm/helm/blob/main/pkg/releaseutil/kind_sorter.go), so RBAC objects are reconciled before typical namespaced workload kinds. If the API server rejects creating or updating those cluster-scoped RBAC rules (for example the caller lacks permission), the release fails instead of only rolling out a broken Metrics `Deployment`. `helm test` (optional) still targets the running Service after install; it does not replace RBAC admission checks.

If `metrics.rbac.enabled=false`, this chart will not create any Metrics RBAC. The chart still validates the configured ClusterQueue and namespace lists. In that mode, the deployer is responsible for ensuring the shared Skaha ServiceAccount already has `get` permission on each configured Kueue `ClusterQueue` and `list` permission on LocalQueues in every configured namespace.

## metrics NetworkPolicy

The co-deployed Metrics API is isolated by default. Configure
`metrics.networkPolicy.ingress.skaha.ports` for the default caller; its
selector is the Skaha Pod's exact `run: <release>-skaha-tomcat` label on port
8000. Egress to every Pod in the release namespace, including the bundled
Redis, is always allowed. DNS egress is allowed on port 53 to any destination,
because node-local DNS caches listen on a host link-local address (for example
`169.254.25.10`) that namespace selectors and, on Cilium, `ipBlock` rules do
not match. Kubernetes API, Redis outside the release namespace, optional
Prometheus/Mimir, and optional metrics-only OTLP egress are empty by default
and must be supplied as exact rules under
`metrics.networkPolicy.egress`. Empty lists remain denied, and the chart
never deploys or selects an OTLP dependency. Additional ingress rules
may be appended under `metrics.networkPolicy.ingress.additional`.

## metrics environment handling

The chart owns `METRICS_PLATFORM_NAME`, the required lower-case DNS `METRICS_CLUSTER_NAME`, and `METRICS_OTEL__POD_UID` from the Pod's downward API. Values for those reserved variables in `metrics.env` cannot create duplicate entries; `unknown` is not a valid cluster identity. The obsolete `METRICS_CACHE__BACKEND` key is never rendered; a transitional custom value is accepted only when it is exactly `redis`, then stripped before it reaches Settings.

Metrics shares the release's bundled Redis with Skaha by default (`redis://<release>-redis-master.<namespace>.svc.<clusterDomain>:6379/0`); Metrics keys are prefixed `metrics:`. Set `metrics.redis.url` to a plain `redis://` or `rediss://` URL to use a different Redis. The chart owns the immutable cache integrity Secret (`<release>-skaha-metrics-cache`). Helm's `randBytes 32` generates a base64-encoded value from 32 cryptographically random bytes on install, and `lookup` reuses the existing Secret during native Helm upgrades. Plaintext `METRICS_CACHE__KEY_SECRET` and `METRICS_REDIS_URL` in `metrics.env` are stripped and never accepted as configuration. There is no cache backend selector. The configured ClusterQueue and namespace lists are required. Prometheus/Mimir efficiency is enabled only when `metrics.prometheus.url` is set, and application-state OTLP metrics are enabled only when `metrics.otlp.endpoint` is set.

### Argo CD cache-key lifecycle

Argo CD renders Helm charts offline, so `lookup` cannot retrieve the live key. The Skaha Applications in keel-deploy configure `ignoreDifferences` directly: an exact Secret name and namespace match, with `jsonPointers: ["/data/key-secret"]`. They also set `RespectIgnoreDifferences=true`. Both settings are required to preserve the key on later syncs, including deployments installed from scratch. Use the rendered Secret's `metadata.name` because long release names are hashed. All other Secret fields and resources remain reconciled. The immutable Secret rejects accidental changes if the sync option is omitted.

Refreshes, full syncs, image upgrades, and replacement replicas keep the same key. Helm uninstall or **cascading** Argo CD Application deletion removes the Secret, and a fresh install generates a new key. Non-cascading deletion preserves the installed resources and key by design. There are no Secret hooks, keep annotations, bootstrap Jobs, or additional ServiceAccount permissions.

Run the lifecycle regression test with Docker running:

```sh
uv run --no-project --with pyyaml==6.0.3 python helm/tests/test-metrics-cache-lifecycle.py
```

The test first validates the shared ServiceAccount in full-chart renders for default, custom, existing, and legacy Skaha configurations, including an ignored Metrics identity override. It then creates and removes a disposable kind cluster with Argo CD, deploys the production Secret/ServiceAccount/Service/test-hook templates with two lightweight HTTP/key consumers, and checks their projected ServiceAccount identities. It verifies cache refresh, full sync, restart, deletion, and reinstall with a long release name, explicitly runs the Metrics test Pod, and runs both hooks through `helm test` on a native Helm fixture release. The fixture uses separate Skaha and Metrics consumers with the production Services and Metrics NetworkPolicy. It does not run the full Skaha/Metrics application stack.

## Required deployment configuration

Metrics is now required and its values live under `metrics` (the former `metricsBackend` key fails the render). Remove `metrics.enabled` and configure `metrics.clusterName`, the ClusterQueue JSON list, and the LocalQueue namespace list before upgrading. `metrics.redis.urlSecret` is retired; Metrics uses the bundled Redis unless `metrics.redis.url` is set. The operator-provided `metrics.cacheKeySecret` setting is retired; the chart generates the integrity key. Configure the Argo CD cache-key rules above before syncing, and use cascading deletion when uninstalling the application.

## Running chart tests

The Pods under `templates/tests` are Helm `test` hooks. After a native Helm install, run:

```sh
helm test <release> --namespace <namespace>
```

Helm creates those Pods in that cluster and reports success when they exit with status 0. The Metrics hook retries the running Metrics Service's `/readyz` endpoint; the Skaha hook checks HTTP connectivity to the running Skaha Service. They use the shared ServiceAccount without mounting its API token. The Metrics NetworkPolicy allows only the labelled Metrics test Pod to reach port 8000 when that test is enabled.

`helm template` and the shell render tests validate YAML offline. Argo CD skips Helm `test` hooks during sync and does not create Helm release history, so `helm test` is not available for an Argo-only deployment. Run rendered test Pods explicitly against that deployment, or configure a separate Argo PostSync test if automatic checks are wanted. The lifecycle CI uses a disposable local kind cluster; these checks have not been run against CANFAR staging or production.
