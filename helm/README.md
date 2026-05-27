# skaha

A Helm chart to install the Skaha web service of the CANFAR Science Platform

| Chart | AppVersion | Type |
|:-----:|:----------:|:----:|
|1.6.0-rc-002<!-- x-release-please-version --> | 1.3.0 | application |

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
| deployment.skaha.apiVersion | string | `"v1"` | Skaha API version path segment (for example, `v1` -> `/skaha/v1/...`). |
| deployment.skaha.cookieSignaturePublicKey | object | `{"existingSecret":{"name":"","path":""}}` | Mount RSA public verification material via projected Secret (`existingSecret.name`). Secret `.data` key must be **`RsaSignaturePub.key`**. Filename under `/config` is `existingSecret.path`, default **`RsaSignaturePub.key`**. Leave `name` empty to omit mount. See README "Cookie verification public key". |
| deployment.skaha.defaultQuotaGB | string | `"10"` | Default user storage quota in GiB for first-time users. |
| deployment.skaha.identityManagerClass | string | `"org.opencadc.auth.StandardIdentityManager"` | Java IdentityManager implementation used for authentication. |
| deployment.skaha.image | string | `"images.opencadc.org/platform/skaha:1.3.0"` | Container image for the Skaha API service. |
| deployment.skaha.imageCache.refreshSchedule | string | `"*/30 * * * *"` | Cron schedule used to refresh cached images. |
| deployment.skaha.imagePullPolicy | string | `"Always"` | Image pull policy for the Skaha API container. |
| deployment.skaha.init.image | string | `"busybox:1.37.0"` | Init container image used to bootstrap user storage paths. |
| deployment.skaha.init.imagePullPolicy | string | `"IfNotPresent"` | Image pull policy for the bootstrap init container. |
| deployment.skaha.posixMapperCacheTTLSeconds | string | `"86400"` | TTL in seconds for cached POSIX mapper entries. |
| deployment.skaha.registryHosts | string | `"images.canfar.net"` | Space-delimited list of image registry hosts allowed for sessions. |
| deployment.skaha.resources.limits.cpu | string | `"2000m"` | CPU limit for the Skaha API container. |
| deployment.skaha.resources.limits.memory | string | `"3Gi"` | Memory limit for the Skaha API container. |
| deployment.skaha.resources.requests.cpu | string | `"1000m"` | CPU request for the Skaha API container. |
| deployment.skaha.resources.requests.memory | string | `"2Gi"` | Memory request for the Skaha API container. |
| deployment.skaha.sessions | object | `{"authorization":{"group":{"enabled":false,"uri":""},"permissionsAPI":{"authAPIBaseURL":"","baseURL":"","enabled":false,"method":"","name":"skaha","route":"","type":"route","version":""}},"expirySeconds":"345600","flexResourceRequests":{"headless":{"cpuCores":"1","memoryInGB":"4"}},"headlessPriorityClass":{"create":false,"description":"For high-priority headless jobs. Preempting.","globalDefault":false,"preemptionPolicy":"PreemptLowerPriority","value":2000},"imagePullPolicy":"Always","ingress":{"customResponseHeaders":{},"tls":{}},"initContainerImage":"redis:8.2.2-bookworm","kueue":{"api":{"group":"kueue.x-k8s.io","version":"v1beta2"},"rbac":{"create":false}},"limitRange":{"create":true,"enabled":false},"maxCount":"5","maxEphemeralStorage":"200Gi","minEphemeralStorage":"20Gi","namespace":{"create":false,"name":"skaha-workload"},"nodeLabelSelector":null,"priorityClass":{"create":false,"description":"For high-priority user pods. Preempting.","globalDefault":false,"name":"uber-user-preempt-high","preemptionPolicy":"PreemptLowerPriority","value":2000},"tolerations":[],"userStorage":{"admin":{},"homeDirectory":"home","projectsDirectory":"projects","spec":{},"topLevelDirectory":"/cavern"}}` | @deprecated Prefer deployment.skaha.sessions.authorization or deprecated usersGroup alone. Still honoured when sessions.authorization.group.enabled is false. The IVOA GMS Group URI to verify users against for permission to use the Science Platform. See https://www.ivoa.net/documents/GMS/20220222/REC-GMS-1.0.html#tth_sEc3.2 usersGroup: "ivo://example.org/gms?prototyping-groups/mini-src/platform-users" Group URI for users to ensure priority for their headless jobs. See https://www.ivoa.net/documents/GMS/20220222/REC-GMS-1.0.html#tth_sEc3.2 headlessPriorityGroup: "ivo://example.org/gms?skaha-priority-headless-users" Array of GMS Group URIs allowed to set the logging level.  If none set, then nobody can change the log level. See https://www.ivoa.net/documents/GMS/20220222/REC-GMS-1.0.html#tth_sEc3.2 for GMS Group URIs See https://github.com/opencadc/core/tree/main/cadc-log for Logging control loggingGroups:   - "ivo://example.org/gms?prototyping-groups/mini-src/platform-users" The Resource ID (URI) of the Service that contains the Posix Mapping information posixMapperResourceID: "ivo://example.org/posix-mapper" URI or URL of the OIDC (IAM) server.  Used to validate incoming tokens. oidcURI: https://iam.example.org/ The Resource ID (URI) of the GMS Service. gmsID: ivo://example.org/gms The absolute URL of the IVOA Registry where services are registered registryURL: https://spsrc27.iaa.csic.es/reg This applies to Skaha itself.  Meaning, this Pod will be scheduled as described by the nodeAffinity clause. Note the different indentation level compared to the sessions.nodeAffinity. See https://kubernetes.io/docs/tasks/configure-pod-container/assign-pods-nodes-using-node-affinity/ nodeAffinity: {} Settings for User Sessions.  Sensible defaults supplied, but can be overridden. For units of storage, see https://kubernetes.io/docs/concepts/configuration/manage-resources-containers/#meaning-of-memory. |
| deployment.skaha.sessions.authorization.group.enabled | bool | `false` | When true, SKAHA_USERS_GROUP is set from uri. Required uri when enabled (Helm validates). The Skaha service enforces mutually exclusive authorization modes at runtime. |
| deployment.skaha.sessions.authorization.permissionsAPI.authAPIBaseURL | string | `""` | Base URL for the SRCNet Auth API used by the permissions client. |
| deployment.skaha.sessions.authorization.permissionsAPI.baseURL | string | `""` | Required when enabled is true (Helm validates). |
| deployment.skaha.sessions.authorization.permissionsAPI.enabled | bool | `false` | When true, SKAHA_PERMISSIONS_API_* vars are set from baseURL, authAPIBaseURL, type, route, name, version, and method. Required baseURL and authAPIBaseURL when enabled (Helm validates). The Skaha service enforces mutually exclusive authorization modes at runtime. |
| deployment.skaha.sessions.authorization.permissionsAPI.method | string | `""` | Method of the permissions API. |
| deployment.skaha.sessions.authorization.permissionsAPI.name | string | `"skaha"` | Name of the permissions API. |
| deployment.skaha.sessions.authorization.permissionsAPI.route | string | `""` | Route of the permissions API. |
| deployment.skaha.sessions.authorization.permissionsAPI.type | string | `"route"` | Type of the permissions API. |
| deployment.skaha.sessions.authorization.permissionsAPI.version | string | `""` | Version of the permissions API. |
| deployment.skaha.sessions.expirySeconds | string | `"345600"` | Session lifetime in seconds before expiry and shutdown. |
| deployment.skaha.sessions.flexResourceRequests.headless.cpuCores | string | `"1"` | Default CPU request (cores) for flex headless sessions. |
| deployment.skaha.sessions.flexResourceRequests.headless.memoryInGB | string | `"4"` | Default memory request (GiB) for flex headless sessions. |
| deployment.skaha.sessions.headlessPriorityClass | object | `{"create":false,"description":"For high-priority headless jobs. Preempting.","globalDefault":false,"preemptionPolicy":"PreemptLowerPriority","value":2000}` | Effective headless PriorityClass configuration (merged with legacy headlessPriorityClass). |
| deployment.skaha.sessions.imagePullPolicy | string | `"Always"` | Image pull policy applied to all user session containers. |
| deployment.skaha.sessions.ingress.customResponseHeaders | object | `{}` | Custom response headers added to user-session ingress responses. |
| deployment.skaha.sessions.ingress.tls | object | `{}` | TLS configuration for the user-session Traefik IngressRoute. |
| deployment.skaha.sessions.initContainerImage | string | `"redis:8.2.2-bookworm"` | Image used by the session init container that manages POSIX data. |
| deployment.skaha.sessions.kueue | object | `{"api":{"group":"kueue.x-k8s.io","version":"v1beta2"},"rbac":{"create":false}}` | Per-session-type Kueue configuration. |
| deployment.skaha.sessions.kueue.api.group | string | `"kueue.x-k8s.io"` | Kueue API group for LocalQueue lookups. Override only if your cluster uses a non-standard CRD group. |
| deployment.skaha.sessions.kueue.api.version | string | `"v1beta2"` | Kueue API version segment for LocalQueue lookups (override to v1beta1 for older Kueue installs). |
| deployment.skaha.sessions.limitRange.create | bool | `true` | When true together with `enabled`, the chart creates the LimitRange `<release>-session-limit-range` in the workload namespace from `spec` below. When false, do not create one here; ensure the namespace has a LimitRange Skaha can use (Skaha uses the first LimitRange returned; the name is mostly irrelevant). |
| deployment.skaha.sessions.limitRange.enabled | bool | `false` | When true, set `SKAHA_SESSION_LIMIT_RANGE_ENABLED` on the Skaha deployment so it loads the **first** `LimitRange` in the workload namespace (name is mostly irrelevant). Also gates this chart's LimitRange Role/RoleBinding (`rbac.create`). Must also be true (with `create`) for the chart to render the LimitRange manifest. |
| deployment.skaha.sessions.maxCount | string | `"5"` | Maximum number of active sessions allowed per user. |
| deployment.skaha.sessions.maxEphemeralStorage | string | `"200Gi"` | Maximum ephemeral storage limit for sessions (non-desktop). |
| deployment.skaha.sessions.minEphemeralStorage | string | `"20Gi"` | Initial ephemeral storage request for new sessions (non-desktop). |
| deployment.skaha.sessions.nodeLabelSelector | string | `nil` | Node label selector used when discovering schedulable worker nodes. |
| deployment.skaha.sessions.priorityClass | object | `{"create":false,"description":"For high-priority user pods. Preempting.","globalDefault":false,"name":"uber-user-preempt-high","preemptionPolicy":"PreemptLowerPriority","value":2000}` | PriorityClass assigned to the Skaha API Pod. |
| deployment.skaha.sessions.tolerations | list | `[]` | Tolerations applied to user session Pods. |
| deployment.skaha.sessions.userStorage.admin.auth | object | `{}` | Optional. When omitted or empty, user allocations authenticate with the requesting user's Bearer token. With `certificateSecret`, the PEM is mounted under `/config/<key>` (projected volume) and `SKAHA_USER_STORAGE_ADMIN_CERTIFICATE_ENABLED` is set to `true`. `apiKey` / `apiKeySecret` are not supported. |
| deployment.skaha.sessions.userStorage.homeDirectory | string | `"home"` | Relative path under topLevelDirectory for user home directories. |
| deployment.skaha.sessions.userStorage.projectsDirectory | string | `"projects"` | Relative path under topLevelDirectory for shared projects storage. |
| deployment.skaha.sessions.userStorage.topLevelDirectory | string | `"/cavern"` | Absolute mount path containing user home and projects directories. |
| experimentalFeatures.enabled | bool | `false` | Enable processing of experimental feature gates. |
| ingress.enabled | bool | `true` | Enable ingress routing for the Skaha API. |
| ingress.path | string | `"/skaha"` | Ingress path prefix routed to the Skaha API Service. |
| kubernetesClusterDomain | string | `"cluster.local"` | Kubernetes DNS domain used when building internal service hostnames. |
| metricsBackend.enabled | bool | `false` | When true, install Kueue-read ClusterRole/Binding first (Helm kind order), then Metrics Service and Deployment. Applies fail if cluster RBAC cannot be created (for example forbidden). |
| metricsBackend.env | object | `{}` | Map of environment variables for the Metrics container (typically METRICS_*). GitOps should supply the full map per environment. |
| metricsBackend.image.pullPolicy | string | `"IfNotPresent"` | imagePullPolicy for the Metrics API container. |
| metricsBackend.image.repository | string | `"images.opencadc.org/platform/metrics"` | Metrics container image repository. |
| metricsBackend.image.tag | string | `"v0.1.5"` | Metrics container image tag. |
| metricsBackend.ingress.enabled | bool | `false` | When true and top-level ingress.enabled is true, add a path on the same host routing to the Metrics Service. |
| metricsBackend.ingress.path | string | `"/metrics"` | Ingress path prefix for the Metrics API (Traefik). |
| metricsBackend.rbac.enabled | bool | `false` | When true, create metricsBackend Kueue-read ClusterRole/ClusterRoleBinding. Set false to disable cluster-scoped RBAC while keeping the metrics workload enabled. |
| metricsBackend.redis.enabled | bool | `true` | When true, set METRICS_REDIS_URL to this release's Bitnami Redis master Service (<release>-redis-master), same instance Skaha uses. Set false and supply METRICS_REDIS_URL in env if Metrics should use another Redis. |
| metricsBackend.replicaCount | int | `1` | Fixed replica count for the Metrics API (no HPA in this chart version). |
| metricsBackend.resources | object | `{"limits":{"cpu":"1","memory":"1Gi"},"requests":{"cpu":"100m","memory":"256Mi"}}` | Resource requests and limits for the Metrics API container. |
| metricsBackend.revisionHistoryLimit | int | `3` | revisionHistoryLimit for the Metrics API Deployment. |
| metricsBackend.test.enabled | bool | `true` | Run helm test hook that retries /healthz until success (requires metricsBackend.enabled). |
| metricsBackend.test.image | string | `"busybox:1.37.0"` | Image for the helm test hook Pod. |
| metricsBackend.test.maxWaitSeconds | int | `180` | Maximum seconds to wait for Metrics /healthz (should exceed startupProbe worst case plus scheduling margin). |
| rbac.clusterRole.create | bool | `false` |  |
| rbac.create | bool | `true` |  |
| podSecurityContext | object | `{}` | Optional container-level security context for the Skaha API container. |
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
| serviceAccount | object | `{"annotations":{},"automount":true,"create":true,"name":""}` | ServiceAccount used by the Skaha API Pod. |
| tolerations | list | `[]` | Tolerations applied to the Skaha API Pod. |

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

## metricsBackend install ordering

When `metricsBackend.enabled` is true, the chart emits `ClusterRole`, `ClusterRoleBinding`, `Service`, and `Deployment` for metrics. Helm applies manifest groups in a deterministic [kind order](https://github.com/helm/helm/blob/main/pkg/releaseutil/kind_sorter.go) so RBAC objects are reconciled before typical namespaced workload kinds. If the API server rejects creating or updating those cluster-scoped RBAC rules (for example the caller lacks permission), the release fails instead of only rolling out a broken metrics `Deployment`. `helm test` (optional) still targets the running Service after install; it does not replace RBAC admission checks.

If `metricsBackend.enabled=true` and `metricsBackend.rbac.enabled=false`, this chart will not create the metrics ClusterRole/ClusterRoleBinding. In that mode, the deployer is responsible for ensuring the Skaha ServiceAccount (`deployment.skaha.serviceAccountName`) already has `get`/`list` permissions on the Kueue `ClusterQueue` API before installation.
