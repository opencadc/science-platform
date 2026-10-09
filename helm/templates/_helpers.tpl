{{/*
Expand the name of the chart.
*/}}
{{- define "skaha.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
We truncate at 63 chars because some Kubernetes name fields are limited to this (by the DNS naming spec).
If release name contains chart name it will be used as a full name.
*/}}
{{- define "skaha.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/*
Create chart name and version as used by the chart label.
*/}}
{{- define "skaha.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels
*/}}
{{- define "skaha.labels" -}}
helm.sh/chart: {{ include "skaha.chart" . }}
{{ include "skaha.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Obtain a comma-delimited string of Experimental Features and a flag to set if any are enabled.
*/}}
{{- define "skaha.experimentalFeatureGates" -}}
{{- $features := "" -}}
{{- $featureEnabled := false -}}

{{- with .Values.experimentalFeatures -}}

{{- if .enabled -}}
{{- range $feature, $map := . -}}

{{/* Skip the 'enabled' key itself */}}
{{- if and (ne $feature "enabled") (ne $feature "") -}}
{{- $thisMap := $map | default dict }}

{{- if or (not (hasKey $thisMap "enabled")) (not (kindIs "bool" $thisMap.enabled)) -}}
{{- fail ( printf "Feature gate '%s' must have 'enabled' (false | true) key" $feature ) -}}
{{- end }}

{{- if eq $features "" -}}
{{- $features = printf "%s=%t" $feature $thisMap.enabled -}}
{{- else -}}
{{- $features = printf "%s,%s=%t" $features $feature $thisMap.enabled -}}
{{- end -}}

{{- end -}}
{{/* End check for enabled key to skip */}}

{{- end -}}
{{/* End range */}}

{{- printf "%s" $features -}}

{{- end -}}
{{/* End global if enabled */}}

{{- end -}}
{{/* End with */}}

{{- end -}}

{{/*
Selector labels
*/}}
{{- define "skaha.selectorLabels" -}}
app.kubernetes.io/name: {{ include "skaha.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
Create the name of the service account to use
*/}}
{{- define "skaha.serviceAccountName" -}}
{{- $name := .Values.serviceAccount.name -}}
{{- $legacy := .Values.deployment.skaha.serviceAccountName -}}
{{- if .Values.serviceAccount.create }}
{{- coalesce $name $legacy (include "skaha.fullname" .) }}
{{- else }}
{{- coalesce $name $legacy "default" }}
{{- end }}
{{- end }}

{{/*
Namespace for user session workloads. String sessions.namespace wins outright. Map form uses skahaWorkload.namespace first (legacy), then namespace.name, then skaha-workload — so chart defaults for name do not hide a legacy skahaWorkload.namespace.
*/}}
{{- define "skaha.workloadNamespace" -}}
{{- $sw := .Values.skahaWorkload | default dict -}}
{{- $ns := .Values.deployment.skaha.sessions.namespace -}}
{{- if kindIs "map" $ns -}}
{{- coalesce $sw.namespace $ns.name "skaha-workload" -}}
{{- else -}}
{{- coalesce $ns $sw.namespace "skaha-workload" -}}
{{- end -}}
{{- end -}}

{{/*
Effective API PriorityClass map: chart defaults from sessions.priorityClass, then overlay legacy deployment.skaha.priorityClass
(keys in legacy win so existing releases that only set deployment.skaha.priorityClass keep working).
*/}}
{{- define "skaha.effectiveApiPriorityClassJSON" -}}
{{- $sess := .Values.deployment.skaha.sessions.priorityClass | default dict }}
{{- $legacy := .Values.deployment.skaha.priorityClass | default dict }}
{{- mergeOverwrite (deepCopy $sess) $legacy | toJson -}}
{{- end -}}

{{/*
Skaha API pod PriorityClass name: merged priorityClass.name, else legacy deployment.skaha.priorityClassName.
*/}}
{{- define "skaha.apiPriorityClassName" -}}
{{- $pc := include "skaha.effectiveApiPriorityClassJSON" . | fromJson }}
{{- coalesce $pc.name .Values.deployment.skaha.priorityClassName -}}
{{- end -}}

{{/*
Effective headless PriorityClass map: normalized legacy deployment.skaha.headlessPriorityClass (string or map) merged with
sessions.headlessPriorityClass (sessions wins). Preserves backwards compatibility when headlessPriorityClass was a plain string name.
*/}}
{{- define "skaha.effectiveHeadlessPriorityClassJSON" -}}
{{- $sessionsH := .Values.deployment.skaha.sessions.headlessPriorityClass | default dict }}
{{- $old := .Values.deployment.skaha.headlessPriorityClass }}
{{- $legacyH := dict }}
{{- if kindIs "string" $old }}
{{- $legacyH = dict "name" $old }}
{{- else if kindIs "map" $old }}
{{- $legacyH = $old }}
{{- end }}
{{- mergeOverwrite (deepCopy $legacyH) $sessionsH | toJson -}}
{{- end -}}

{{/*
Headless jobs PriorityClass name for SKAHA_HEADLESS_PRIORITY_CLASS from the effective merged configuration.
*/}}
{{- define "skaha.headlessPriorityClassName" -}}
{{- $h := include "skaha.effectiveHeadlessPriorityClassJSON" . | fromJson }}
{{- $h.name -}}
{{- end -}}

{{- define "skaha.metricsBackend.baseName" -}}
{{- .Release.Name -}}
{{- end }}

{{- define "skaha.metricsBackend.nameWithSuffix" -}}
{{- $suffix := .suffix | trunc 62 | trimSuffix "-" -}}
{{- $baseLength := int (sub 62 (len $suffix)) -}}
{{- $base := include "skaha.metricsBackend.baseName" .context -}}
{{- if gt (len $base) $baseLength -}}
{{- $hash := sha256sum $base | trunc 10 -}}
{{- $prefixLength := int (sub $baseLength 11) -}}
{{- if gt $prefixLength 0 -}}
{{- printf "%s-%s-%s" ($base | trunc $prefixLength | trimSuffix "-") $hash $suffix | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" $hash $suffix | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- else if gt $baseLength 0 -}}
{{- printf "%s-%s" $base $suffix | trimSuffix "-" -}}
{{- else -}}
{{- $suffix -}}
{{- end -}}
{{- end }}

{{- define "skaha.metricsBackend.deploymentName" -}}
{{- include "skaha.metricsBackend.nameWithSuffix" (dict "context" . "suffix" "skaha-metrics-api") -}}
{{- end }}

{{- define "skaha.metricsBackend.serviceName" -}}
{{- include "skaha.metricsBackend.nameWithSuffix" (dict "context" . "suffix" "skaha-metrics-api-svc") -}}
{{- end }}

{{- define "skaha.metricsBackend.internalURL" -}}
{{- printf "http://%s.%s.svc.%s:8000" (include "skaha.metricsBackend.serviceName" .) .Release.Namespace .Values.kubernetesClusterDomain -}}
{{- end }}

{{- define "skaha.metricsBackend.selectorLabels" -}}
app.kubernetes.io/name: skaha-metrics-api
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: metrics-api
{{- end }}

{{- define "skaha.metricsBackend.labels" -}}
helm.sh/chart: {{ include "skaha.chart" . }}
{{ include "skaha.metricsBackend.selectorLabels" . }}
{{- $mb := .Values.metricsBackend | default dict -}}
{{- with $mb.image }}
{{- with .tag }}
app.kubernetes.io/version: {{ . | quote }}
{{- end }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{- define "skaha.metricsBackend.platformName" -}}
{{- $mb := .Values.metricsBackend | default dict -}}
{{- $platformName := "canfar" -}}
{{- if hasKey $mb "platformName" -}}
{{- $platformName = get $mb "platformName" | default "" | toString -}}
{{- end -}}
{{- $customEnv := $mb.env | default dict -}}
{{- if hasKey $customEnv "METRICS_PLATFORM_NAME" -}}
{{- $platformName = get $customEnv "METRICS_PLATFORM_NAME" | default "" | toString -}}
{{- end -}}
{{- $platformName = trim $platformName -}}
{{- if or (eq $platformName "") (gt (len $platformName) 63) (not (regexMatch "^[A-Za-z0-9]([-A-Za-z0-9_.]{0,61}[A-Za-z0-9])?$" $platformName)) -}}
{{- fail "metricsBackend.platformName must match ^[A-Za-z0-9](?:[-A-Za-z0-9_.]{0,61}[A-Za-z0-9])?$ after trimming" -}}
{{- end -}}
{{- $platformName -}}
{{- end }}

{{- define "skaha.metricsBackend.clusterName" -}}
{{- $mb := .Values.metricsBackend | default dict -}}
{{- $clusterName := get $mb "clusterName" | default "" | toString | trim -}}
{{- if or (eq $clusterName "") (eq $clusterName "unknown") (gt (len $clusterName) 253) (not (regexMatch "^[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?([.][a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?)*$" $clusterName)) -}}
{{- fail "metricsBackend.clusterName must be a real lower-case DNS cluster identity; unknown is not allowed" -}}
{{- end -}}
{{- $clusterName -}}
{{- end }}

{{- define "skaha.metricsBackend.serviceAccountName" -}}
{{- $mb := .Values.metricsBackend | default dict -}}
{{- $serviceAccount := $mb.serviceAccount | default dict -}}
{{- $create := true -}}
{{- if hasKey $serviceAccount "create" -}}
{{- $create = get $serviceAccount "create" -}}
{{- end -}}
{{- $configuredName := get $serviceAccount "name" | default "" | toString | trim -}}
{{- $name := $configuredName -}}
{{- if $create -}}
{{- $name = default (include "skaha.metricsBackend.nameWithSuffix" (dict "context" . "suffix" "skaha-metrics-sa")) $configuredName -}}
{{- else -}}
{{- $name = required "metricsBackend.serviceAccount.name is required when metricsBackend.serviceAccount.create is false" $configuredName -}}
{{- end -}}
{{- if or (gt (len $name) 253) (not (regexMatch "^[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?([.][a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?)*$" $name)) -}}
{{- fail "metricsBackend.serviceAccount.name must be a lower-case DNS name" -}}
{{- end -}}
{{- $mbRbac := $mb.rbac | default dict -}}
{{- $rbacEnabled := true -}}
{{- if hasKey $mbRbac "enabled" -}}
{{- $rbacEnabled = get $mbRbac "enabled" -}}
{{- end -}}
{{- if and $rbacEnabled (eq $name "default") -}}
{{- fail "metricsBackend.serviceAccount.name must not be default when metricsBackend.rbac.enabled is true" -}}
{{- end -}}
{{- if eq $name (include "skaha.serviceAccountName" .) -}}
{{- fail "metricsBackend.serviceAccount.name must be distinct from the Skaha ServiceAccount" -}}
{{- end -}}
{{- $name -}}
{{- end }}

{{- define "skaha.metricsBackend.clusterQueues" -}}
{{- $mb := .Values.metricsBackend | default dict -}}
{{- $customEnv := $mb.env | default dict -}}
{{- $raw := get $customEnv "METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES" | default "" | toString | trim -}}
{{- if not $raw -}}
{{- fail "metricsBackend.enabled requires METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES" -}}
{{- end -}}
{{- $configured := fromJsonArray $raw -}}
{{- if not (kindIs "slice" $configured) -}}
{{- fail "METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES must be a non-empty JSON array" -}}
{{- end -}}
{{- if eq (len $configured) 0 -}}
{{- fail "METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES must be a non-empty JSON array" -}}
{{- end -}}
{{- if gt (len $configured) 256 -}}
{{- fail "METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES must contain at most 256 entries" -}}
{{- end -}}
{{- $normalized := list -}}
{{- range $queue := $configured -}}
{{- if not (kindIs "string" $queue) -}}
{{- fail "METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES entries must be non-empty strings" -}}
{{- end -}}
{{- $name := trim $queue -}}
{{- if or (not $name) (gt (len $name) 253) -}}
{{- fail "METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES entries must be bounded non-empty strings" -}}
{{- end -}}
{{- range $label := splitList "." $name -}}
{{- if not (regexMatch "^[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?$" $label) -}}
{{- fail "METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES entries must use Kubernetes DNS-subdomain names" -}}
{{- end -}}
{{- end -}}
{{- if has $name $normalized -}}
{{- fail "METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES entries must be unique" -}}
{{- end -}}
{{- $normalized = append $normalized $name -}}
{{- end -}}
{{- toJson $normalized -}}
{{- end }}

{{- define "skaha.metricsBackend.normalizeKueueNamespaces" -}}
{{- $configured := .values -}}
{{- $field := .field -}}
{{- if not (kindIs "slice" $configured) -}}
{{- fail (printf "%s must be a list" $field) -}}
{{- end -}}
{{- if eq (len $configured) 0 -}}
{{- fail (printf "%s must be a non-empty JSON array" $field) -}}
{{- end -}}
{{- if gt (len $configured) 256 -}}
{{- fail (printf "%s must contain at most 256 entries" $field) -}}
{{- end -}}
{{- $normalized := list -}}
{{- range $namespace := $configured -}}
{{- if not (kindIs "string" $namespace) -}}
{{- fail (printf "%s entries must be non-empty strings" $field) -}}
{{- end -}}
{{- $name := trim $namespace -}}
{{- if or (not $name) (gt (len $name) 63) (not (regexMatch "^[a-z0-9]([-a-z0-9]*[a-z0-9])?$" $name)) -}}
{{- fail (printf "%s entries must be valid Kubernetes namespace names" $field) -}}
{{- end -}}
{{- if has $name $normalized -}}
{{- fail (printf "%s entries must be unique" $field) -}}
{{- end -}}
{{- $normalized = append $normalized $name -}}
{{- end -}}
{{- $normalized = sortAlpha $normalized -}}
{{- toJson $normalized -}}
{{- end }}

{{- define "skaha.metricsBackend.kueueNamespaces" -}}
{{- $mb := .Values.metricsBackend | default dict -}}
{{- $mbRbac := $mb.rbac | default dict -}}
{{- $configured := $mbRbac.namespaces | default list -}}
{{- if not (kindIs "slice" $configured) -}}
{{- fail "metricsBackend.rbac.namespaces must be a list" -}}
{{- end -}}
{{- $customEnv := $mb.env | default dict -}}
{{- $fromEnv := get $customEnv "METRICS_PROVIDERS__KUEUE__NAMESPACES" | default "" | toString | trim -}}
{{- $hasConfigured := gt (len $configured) 0 -}}
{{- $hasEnv := ne $fromEnv "" -}}
{{- $configuredJSON := "[]" -}}
{{- $configuredNamespaces := list -}}
{{- if $hasConfigured -}}
{{- $configuredJSON = include "skaha.metricsBackend.normalizeKueueNamespaces" (dict "values" $configured "field" "metricsBackend.rbac.namespaces") | trim -}}
{{- $configuredNamespaces = fromJsonArray $configuredJSON -}}
{{- end -}}
{{- $envJSON := "[]" -}}
{{- $envNamespaces := list -}}
{{- if $hasEnv -}}
{{- $envNamespaces = fromJsonArray $fromEnv -}}
{{- $envJSON = include "skaha.metricsBackend.normalizeKueueNamespaces" (dict "values" $envNamespaces "field" "METRICS_PROVIDERS__KUEUE__NAMESPACES") | trim -}}
{{- $envNamespaces = fromJsonArray $envJSON -}}
{{- end -}}
{{- if and $hasConfigured $hasEnv -}}
{{- if ne (toJson $configuredNamespaces) (toJson $envNamespaces) -}}
{{- fail "metricsBackend.rbac.namespaces must exactly match METRICS_PROVIDERS__KUEUE__NAMESPACES after normalization" -}}
{{- end -}}
{{- end -}}
{{- if $hasEnv -}}
{{- $envJSON -}}
{{- else if $hasConfigured -}}
{{- $configuredJSON -}}
{{- else -}}
{{- fail "metricsBackend.enabled requires metricsBackend.rbac.namespaces or METRICS_PROVIDERS__KUEUE__NAMESPACES" -}}
{{- end -}}
{{- end }}

{{- define "skaha.metricsBackend.rbacKueueNamespaces" -}}
{{- include "skaha.metricsBackend.kueueNamespaces" . -}}
{{- end }}

{{- define "skaha.metricsBackend.env" -}}
{{- $mb := .Values.metricsBackend | default dict -}}
{{- $customEnv := $mb.env | default dict -}}
{{- $redis := $mb.redis | default dict -}}
{{- $redisURLSecret := $redis.urlSecret | default dict -}}
{{- $cacheKeySecret := $mb.cacheKeySecret | default dict -}}
{{- $prometheus := $mb.prometheus | default dict -}}
{{- $prometheusURL := trim (get $prometheus "url" | default "" | toString) -}}
{{- $otlp := $mb.otlp | default dict -}}
{{- $otlpEndpoint := trim (get $otlp "endpoint" | default "" | toString) -}}
{{- $env := dict -}}
{{- range $key, $value := $customEnv -}}
{{- $_ := set $env $key $value -}}
{{- end -}}
{{- $platformName := include "skaha.metricsBackend.platformName" . -}}
{{- $clusterName := include "skaha.metricsBackend.clusterName" . -}}
{{- $clusterQueues := include "skaha.metricsBackend.clusterQueues" . | trim -}}
{{- if and (hasKey $customEnv "METRICS_CACHE__BACKEND") (ne (get $customEnv "METRICS_CACHE__BACKEND" | toString) "redis") -}}
{{- fail "metricsBackend.env.METRICS_CACHE__BACKEND is obsolete and must be exactly redis when supplied for transition" -}}
{{- end -}}
{{- $redisSecretName := required "metricsBackend.redis.urlSecret.name is required" (get $redisURLSecret "name" | default "" | toString | trim) -}}
{{- $redisSecretKey := required "metricsBackend.redis.urlSecret.key is required" (get $redisURLSecret "key" | default "" | toString | trim) -}}
{{- $cacheSecretName := required "metricsBackend.cacheKeySecret.name is required" (get $cacheKeySecret "name" | default "" | toString | trim) -}}
{{- $cacheSecretKey := required "metricsBackend.cacheKeySecret.key is required" (get $cacheKeySecret "key" | default "" | toString | trim) -}}
{{- $_ := unset $env "METRICS_PLATFORM_NAME" -}}
{{- $_ := unset $env "METRICS_CLUSTER_NAME" -}}
{{- $_ := unset $env "METRICS_OTEL__POD_UID" -}}
{{- $_ := unset $env "METRICS_CACHE__BACKEND" -}}
{{- $_ := unset $env "METRICS_PROVIDERS__PROMQL__ENABLED" -}}
{{- $_ := unset $env "METRICS_OTEL__TRACES_ENABLED" -}}
{{- $_ := unset $env "METRICS_OTEL__LOGS_ENABLED" -}}
{{- $_ := unset $env "METRICS_REDIS_URL" -}}
{{- $_ := unset $env "METRICS_CACHE__KEY_SECRET" -}}
{{- if $prometheusURL -}}
{{- $_ := unset $env "METRICS_PROVIDERS__PROMQL__BASE_URL" -}}
{{- end -}}
{{- if $otlpEndpoint -}}
{{- $_ := unset $env "METRICS_OTEL__METRICS_ENABLED" -}}
{{- $_ := unset $env "METRICS_OTEL__EXPORTER_OTLP_ENDPOINT" -}}
{{- end -}}
{{- $_ := unset $env "METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES" -}}
{{- $_ := set $env "METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES" $clusterQueues -}}
{{- $_ := unset $env "METRICS_PROVIDERS__KUEUE__NAMESPACES" -}}
{{- $_ := set $env "METRICS_PROVIDERS__KUEUE__NAMESPACES" (include "skaha.metricsBackend.kueueNamespaces" . | trim) -}}
- name: METRICS_PLATFORM_NAME
  value: {{ $platformName | quote }}
- name: METRICS_CLUSTER_NAME
  value: {{ $clusterName | quote }}
- name: METRICS_OTEL__POD_UID
  valueFrom:
    fieldRef:
      fieldPath: metadata.uid
- name: METRICS_REDIS_URL
  valueFrom:
    secretKeyRef:
      name: {{ $redisSecretName | quote }}
      key: {{ $redisSecretKey | quote }}
- name: METRICS_CACHE__KEY_SECRET
  valueFrom:
    secretKeyRef:
      name: {{ $cacheSecretName | quote }}
      key: {{ $cacheSecretKey | quote }}
{{- if $prometheusURL }}
- name: METRICS_PROVIDERS__PROMQL__BASE_URL
  value: {{ $prometheusURL | quote }}
{{- end }}
{{- if $otlpEndpoint }}
- name: METRICS_OTEL__METRICS_ENABLED
  value: "true"
- name: METRICS_OTEL__EXPORTER_OTLP_ENDPOINT
  value: {{ $otlpEndpoint | quote }}
{{- end }}
{{- range $key := keys $env | sortAlpha }}
- name: {{ $key }}
  value: {{ get $env $key | quote }}
{{- end }}
{{- end }}

{{- define "skaha.metricsBackend.clusterRoleName" -}}
{{- $raw := printf "skaha-metrics-%s-%s-kueue-read" .Release.Namespace .Release.Name | replace "." "-" -}}
{{- if gt (len $raw) 63 -}}
{{- printf "skaha-metrics-%s-kueue-read-%s" ($raw | trunc 26 | trimSuffix "-") (sha256sum $raw | trunc 10) -}}
{{- else -}}
{{- $raw | trimSuffix "-" -}}
{{- end -}}
{{- end }}

{{/*
Structural validation only; Skaha rejects conflicting modes at runtime.
*/}}
{{- define "skaha.validatePlatformAccess" }}
{{- $auth := mergeOverwrite (.Values.deployment.skaha.authorization | default dict) (.Values.deployment.skaha.sessions.authorization | default dict) }}
{{- $g := $auth.group | default dict -}}
{{- $p := $auth.permissionsAPI | default dict -}}
{{- $permURL := trim (default "" $p.baseURL) -}}
{{- $permAuthURL := trim (default "" $p.authAPIBaseURL) -}}
{{- $permEn := $p.enabled | default false -}}
{{- $uri := trim (default "" $g.uri) -}}
{{- $groupEn := $g.enabled | default false -}}
{{- if and $permEn (not $permURL) }}
{{- fail "deployment.skaha.sessions.authorization.permissionsAPI.enabled is true but permissionsAPI.baseURL is empty." }}
{{- end }}
{{- if and $permEn (not $permAuthURL) }}
{{- fail "deployment.skaha.sessions.authorization.permissionsAPI.enabled is true but permissionsAPI.authAPIBaseURL is empty." }}
{{- end }}
{{- if and $groupEn (not $uri) }}
{{- fail "deployment.skaha.sessions.authorization.group.enabled is true but authorization.group.uri is empty." }}
{{- end }}
{{- end }}

{{/*
OpenTelemetry validation for the Skaha Java agent.
*/}}
{{- define "skaha.validateTelemetry" }}
{{- $telemetry := .Values.telemetry | default dict -}}
{{- $otlp := $telemetry.otlp | default dict -}}
{{- $endpoint := trim (default "" $otlp.destination | toString) -}}
{{- $controllerEnabled := default false $telemetry.controller -}}
{{- $interval := trim ((default 30 $otlp.interval) | toString) -}}
{{- if default false $telemetry.metrics }}
{{- fail "telemetry.metrics is reserved for future skaha-metrics OpenTelemetry support and must remain false in this chart version." }}
{{- end }}
{{- if and $controllerEnabled (not $endpoint) }}
{{- fail "telemetry.controller is true but telemetry.otlp.destination is empty." }}
{{- end }}
{{- if and $controllerEnabled (not (regexMatch "^[1-9][0-9]*$" $interval)) }}
{{- fail "telemetry.otlp.interval must be a positive integer number of seconds when telemetry.controller is true." }}
{{- end }}
{{- if and $controllerEnabled $endpoint }}
{{- range $index, $env := (.Values.deployment.skaha.extraEnv | default list) }}
{{- if eq (default "" $env.name | toString) "CATALINA_OPTS" }}
{{- fail "deployment.skaha.extraEnv cannot set CATALINA_OPTS when telemetry.controller is true; telemetry manages the OpenTelemetry Java agent CATALINA_OPTS." }}
{{- end }}
{{- end }}
{{- end }}
{{- end }}

{{/*
USER SESSION TEMPLATE DEFINITIONS
*/}}

{{/*
Validated deployment.skaha.sessions.userStorage.nodeURIPrefix (must use vos:// scheme).
*/}}
{{- define "skaha.job.userStorage.nodeURIPrefix" -}}
{{- $nodeURIPrefix := trim (required ".Values.deployment.skaha.sessions.userStorage.nodeURIPrefix nodeURIPrefix is required." .Values.deployment.skaha.sessions.userStorage.nodeURIPrefix) -}}
{{- if not (hasPrefix "vos://" $nodeURIPrefix) -}}
{{- fail "deployment.skaha.sessions.userStorage.nodeURIPrefix must be a vos:// URI (e.g. vos://example.org~cavern)" -}}
{{- end -}}
{{- $nodeURIPrefix -}}
{{- end -}}

{{/*
The Home VOSpace Node URI (uses vos:// scheme) for the User Home directory in Cavern.
*/}}
{{- define "skaha.job.userStorage.homeURI" -}}
{{- $nodeURIPrefix := include "skaha.job.userStorage.nodeURIPrefix" . -}}
{{- $homeDirectoryName := trimAll "/" (required ".Values.deployment.skaha.sessions.userStorage.homeDirectory home folder name is required." .Values.deployment.skaha.sessions.userStorage.homeDirectory) -}}
{{- printf "%s/%s" $nodeURIPrefix $homeDirectoryName -}}
{{- end -}}

{{/*
The Home Directory base absolute path.
*/}}
{{- define "skaha.job.userStorage.homeBaseDirectory" -}}
{{- $topLevelDirectory := trimAll "/" (required ".Values.deployment.skaha.sessions.userStorage.topLevelDirectory topLevelDirectory is required." .Values.deployment.skaha.sessions.userStorage.topLevelDirectory) -}}
{{- $homeDirectoryName := trimAll "/" (required ".Values.deployment.skaha.sessions.userStorage.homeDirectory home folder name is required." .Values.deployment.skaha.sessions.userStorage.homeDirectory) -}}
{{- printf "/%s/%s" $topLevelDirectory $homeDirectoryName -}}
{{- end -}}

{{/*
The Projects Directory base absolute path.
*/}}
{{- define "skaha.job.userStorage.projectsBaseDirectory" -}}
{{- $topLevelDirectory := trimAll "/" (required ".Values.deployment.skaha.sessions.userStorage.topLevelDirectory topLevelDirectory is required." .Values.deployment.skaha.sessions.userStorage.topLevelDirectory) -}}
{{- $projectsDirectoryName := trimAll "/" (required ".Values.deployment.skaha.sessions.userStorage.projectsDirectory projects folder name is required." .Values.deployment.skaha.sessions.userStorage.projectsDirectory) -}}
{{- printf "/%s/%s" $topLevelDirectory $projectsDirectoryName -}}
{{- end -}}

{{/*
Volume source YAML for the session "cavern-volume" (content below volume name in a Pod spec).
Uses userStorage.spec when non-empty; else persistentVolumeClaimName or default claim skaha-workload-cavern-pvc.
*/}}
{{- define "skaha.session.userStorageVolumeSpec" -}}
{{- $us := .Values.deployment.skaha.sessions.userStorage }}
{{- $spec := $us.spec }}
{{- if and $spec (gt (len $spec) 0) }}
{{- toYaml $spec | indent 8 }}
{{- else }}
        persistentVolumeClaim:
          claimName: {{ $us.persistentVolumeClaimName | default "skaha-workload-cavern-pvc" }}
{{- end }}
{{- end }}

{{/*
The init containers for the launch scripts.
*/}}
{{- define "skaha.job.initContainers" -}}
      - name: backup-original-passwd-groups
        image: ${software.imageid}
        command: ["/bin/sh", "-c", "cp /etc/passwd /etc-passwd/passwd-orig && cp /etc/group /etc-group/group-orig"]
        volumeMounts:
        - mountPath: "/etc-passwd"
          name: etc-passwd
        - mountPath: "/etc-group"
          name: etc-group
        securityContext:
          privileged: false
          allowPrivilegeEscalation: false
          capabilities:
            drop:
              - ALL
        resources:
          requests:
            memory: "64Mi"
            cpu: "200m"
          limits:
            memory: "64Mi"
            cpu: "200m"
      - name: init-users-groups
        image: {{ .Values.deployment.skaha.sessions.initContainerImage | default "redis:8.2.2-bookworm" }}
        command: ["/init-users-groups/init-users-groups.sh"]
        env:
        - name: HOME
          value: "{{ template "skaha.job.userStorage.homeBaseDirectory" . }}/${skaha.userid}"
        - name: REDIS_URL
          value: "redis://{{ .Release.Name }}-redis-master.{{ .Release.Namespace }}.svc.{{ .Values.kubernetesClusterDomain }}:6379"
        volumeMounts:
        - mountPath: "/etc-passwd"
          name: etc-passwd
        - mountPath: "/etc-group"
          name: etc-group
        - mountPath: "/init-users-groups"
          name: init-users-groups
        securityContext:
          privileged: false
          allowPrivilegeEscalation: false
          capabilities:
            drop:
              - ALL
        resources:
          requests:
            memory: "64Mi"
            cpu: "200m"
          limits:
            memory: "64Mi"
            cpu: "200m"
{{- with .Values.deployment.extraHosts }}
      hostAliases:
{{- range $extraHost := . }}
        - ip: {{ $extraHost.ip }}
          hostnames:
            - {{ $extraHost.hostname }}
{{- end }}
{{- end }}
{{- end }}

{{/*
The affinity for Jobs.  This will import the YAML as defined by the user in the deployment.skaha.sessions.nodeAffinity stanza.
*/}}
{{- define "skaha.job.nodeAffinity" -}}
{{- with .Values.deployment.skaha.sessions.nodeAffinity }}
      affinity:
        nodeAffinity:
{{ . | toYaml | indent 10 }}
{{- end }}
{{- end }}

{{/*
Common security context settings for User Session Jobs
*/}}
{{- define "skaha.job.securityContext" -}}
        runAsUser: ${skaha.posixid}
        runAsGroup: ${skaha.posixid}
        supplementalGroups: [${skaha.supgroups}]
        runAsNonRoot: true
        seccompProfile:
          type: RuntimeDefault
{{- end }}
