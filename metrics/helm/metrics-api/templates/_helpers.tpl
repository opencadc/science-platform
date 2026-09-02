{{- define "metrics-api.baseName" -}}
{{- if contains .Chart.Name .Release.Name }}
{{- .Release.Name }}
{{- else }}
{{- printf "%s-%s" .Release.Name .Chart.Name }}
{{- end }}
{{- end }}

{{- define "metrics-api.fullname" -}}
{{- $base := include "metrics-api.baseName" . -}}
{{- if gt (len $base) 63 -}}
{{- printf "%s-%s" ($base | trunc 52 | trimSuffix "-") (sha256sum $base | trunc 10) | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $base | trimSuffix "-" -}}
{{- end -}}
{{- end }}

{{- define "metrics-api.fullnameWithSuffix" -}}
{{- $suffix := .suffix | trunc 62 | trimSuffix "-" }}
{{- $baseLength := int (sub 62 (len $suffix)) }}
{{- $base := include "metrics-api.baseName" .context }}
{{- if gt (len $base) $baseLength }}
{{- $hash := sha256sum $base | trunc 10 }}
{{- $prefixLength := int (sub $baseLength 11) }}
{{- if gt $prefixLength 0 }}
{{- printf "%s-%s-%s" ($base | trunc $prefixLength | trimSuffix "-") $hash $suffix | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" $hash $suffix | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- else if gt $baseLength 0 }}
{{- printf "%s-%s" $base $suffix | trimSuffix "-" }}
{{- else }}
{{- $suffix }}
{{- end }}
{{- end }}

{{- define "metrics-api.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "metrics-api.labels" -}}
helm.sh/chart: {{ include "metrics-api.chart" . }}
{{ include "metrics-api.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{- define "metrics-api.selectorLabels" -}}
app.kubernetes.io/name: {{ .Chart.Name }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{- define "metrics-api.serviceAccountName" -}}
{{- $configuredName := .Values.serviceAccount.name | default "" | toString | trim -}}
{{- $name := "" -}}
{{- if .Values.rbac.create }}
{{- if .Values.serviceAccount.create }}
{{- $name = default (include "metrics-api.fullname" .) $configuredName -}}
{{- else }}
{{- $name = required "serviceAccount.name is required when rbac.create is true and serviceAccount.create is false" $configuredName -}}
{{- end }}
{{- else if .Values.serviceAccount.create }}
{{- $name = default (include "metrics-api.fullname" .) $configuredName -}}
{{- else }}
{{- $name = default "default" $configuredName -}}
{{- end }}
{{- if and .Values.rbac.create (eq $name "default") }}
{{- fail "serviceAccount.name must not be default when rbac.create is true" -}}
{{- end }}
{{- $name -}}
{{- end }}

{{- define "metrics-api.platformName" -}}
{{- $platformName := "canfar" -}}
{{- if hasKey .Values "platformName" -}}
{{- $platformName = get .Values "platformName" | default "" | toString | trim -}}
{{- end -}}
{{- $env := .Values.env | default dict -}}
{{- if hasKey $env "METRICS_PLATFORM_NAME" -}}
{{- $platformName = get $env "METRICS_PLATFORM_NAME" | default "" | toString | trim -}}
{{- end -}}
{{- if or (eq $platformName "") (gt (len $platformName) 63) (not (regexMatch "^[A-Za-z0-9]([-A-Za-z0-9_.]{0,61}[A-Za-z0-9])?$" $platformName)) -}}
{{- fail "platformName must match ^[A-Za-z0-9](?:[-A-Za-z0-9_.]{0,61}[A-Za-z0-9])?$ after trimming" -}}
{{- end -}}
{{- $platformName -}}
{{- end }}

{{- define "metrics-api.clusterName" -}}
{{- $clusterName := get .Values "clusterName" | default "" | toString | trim -}}
{{- if or (eq $clusterName "") (eq $clusterName "unknown") (gt (len $clusterName) 253) (not (regexMatch "^[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?([.][a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?)*$" $clusterName)) -}}
{{- fail "clusterName must be a real lower-case DNS cluster identity; unknown is not allowed" -}}
{{- end -}}
{{- $clusterName -}}
{{- end }}

{{- define "metrics-api.normalizeClusterQueues" -}}
{{- $configured := .values -}}
{{- $field := .field -}}
{{- if not (kindIs "slice" $configured) -}}
{{- fail (printf "%s must be a list" $field) -}}
{{- end -}}
{{- if gt (len $configured) 256 -}}
{{- fail (printf "%s must contain at most 256 entries" $field) -}}
{{- end -}}
{{- $normalized := list -}}
{{- range $queue := $configured -}}
{{- if not (kindIs "string" $queue) -}}
{{- fail (printf "%s entries must be non-empty strings" $field) -}}
{{- end -}}
{{- $name := trim $queue -}}
{{- if or (not $name) (gt (len $name) 253) -}}
{{- fail (printf "%s entries must use Kubernetes DNS-subdomain names" $field) -}}
{{- end -}}
{{- range $label := splitList "." $name -}}
{{- if not (regexMatch "^[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?$" $label) -}}
{{- fail (printf "%s entries must use Kubernetes DNS-subdomain names" $field) -}}
{{- end -}}
{{- end -}}
{{- if has $name $normalized -}}
{{- fail (printf "%s entries must be unique" $field) -}}
{{- end -}}
{{- $normalized = append $normalized $name -}}
{{- end -}}
{{- toJson $normalized -}}
{{- end }}

{{- define "metrics-api.normalizeNamespaces" -}}
{{- $configured := .values -}}
{{- $field := .field -}}
{{- if not (kindIs "slice" $configured) -}}
{{- fail (printf "%s must be a list" $field) -}}
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
{{- toJson (sortAlpha $normalized) -}}
{{- end }}

{{- define "metrics-api.clusterQueues" -}}
{{- $values := .Values.kueue.clusterQueues | default list -}}
{{- $env := .Values.env | default dict -}}
{{- $raw := get $env "METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES" | default "" | toString | trim -}}
{{- $fromValues := include "metrics-api.normalizeClusterQueues" (dict "values" $values "field" "kueue.clusterQueues") | trim -}}
{{- $fromEnv := "[]" -}}
{{- if $raw -}}
{{- $fromEnv = include "metrics-api.normalizeClusterQueues" (dict "values" (fromJsonArray $raw) "field" "METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES") | trim -}}
{{- end -}}
{{- if and (gt (len (fromJsonArray $fromValues)) 0) (ne $fromEnv "[]") -}}
{{- if ne $fromValues $fromEnv -}}
{{- fail "kueue.clusterQueues must match METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES after normalization" -}}
{{- end -}}
{{- end -}}
{{- if ne $fromEnv "[]" -}}{{- $fromEnv -}}{{- else -}}{{- $fromValues -}}{{- end -}}
{{- end }}

{{- define "metrics-api.kueueNamespaces" -}}
{{- $values := .Values.kueue.namespaces | default list -}}
{{- $env := .Values.env | default dict -}}
{{- $raw := get $env "METRICS_PROVIDERS__KUEUE__NAMESPACES" | default "" | toString | trim -}}
{{- $fromValues := include "metrics-api.normalizeNamespaces" (dict "values" $values "field" "kueue.namespaces") | trim -}}
{{- $fromEnv := "[]" -}}
{{- if $raw -}}
{{- $fromEnv = include "metrics-api.normalizeNamespaces" (dict "values" (fromJsonArray $raw) "field" "METRICS_PROVIDERS__KUEUE__NAMESPACES") | trim -}}
{{- end -}}
{{- if and (gt (len (fromJsonArray $fromValues)) 0) (ne $fromEnv "[]") -}}
{{- if ne $fromValues $fromEnv -}}
{{- fail "kueue.namespaces must match METRICS_PROVIDERS__KUEUE__NAMESPACES after normalization" -}}
{{- end -}}
{{- end -}}
{{- if ne $fromEnv "[]" -}}{{- $fromEnv -}}{{- else -}}{{- $fromValues -}}{{- end -}}
{{- end }}

{{- define "metrics-api.env" -}}
{{- $env := dict -}}
{{- range $key, $value := .Values.env -}}
{{- $_ := set $env $key $value -}}
{{- end -}}
{{- $clusterQueues := include "metrics-api.clusterQueues" . | trim -}}
{{- $namespaces := include "metrics-api.kueueNamespaces" . | trim -}}
{{- $clusterName := include "metrics-api.clusterName" . | trim -}}
{{- if and (hasKey $env "METRICS_CACHE__BACKEND") (ne (get $env "METRICS_CACHE__BACKEND" | toString) "redis") -}}
{{- fail "env.METRICS_CACHE__BACKEND is obsolete and must be exactly redis when supplied for transition" -}}
{{- end -}}
{{- $promqlBaseURL := .Values.promql.baseUrl | default "" | trim -}}
{{- if not $promqlBaseURL -}}
{{- $promqlBaseURL = get $env "METRICS_PROVIDERS__PROMQL__BASE_URL" | default "" | toString | trim -}}
{{- end -}}
{{- $promqlTenantID := .Values.promql.mimirTenantId | default "" | trim -}}
{{- if not $promqlTenantID -}}
{{- $promqlTenantID = get $env "METRICS_PROVIDERS__PROMQL__MIMIR_TENANT_ID" | default "" | toString | trim -}}
{{- end -}}
{{- $otelEndpoint := .Values.otel.endpoint | default "" | trim -}}
{{- if not $otelEndpoint -}}
{{- $otelEndpoint = get $env "METRICS_OTEL__EXPORTER_OTLP_ENDPOINT" | default "" | toString | trim -}}
{{- end -}}
{{- if eq $clusterQueues "[]" -}}{{- fail "Deployment requires at least one ClusterQueue" -}}{{- end -}}
{{- if eq $namespaces "[]" -}}{{- fail "Deployment requires at least one Kueue namespace" -}}{{- end -}}
{{- $_ := unset $env "METRICS_PLATFORM_NAME" -}}
{{- $_ := unset $env "METRICS_CLUSTER_NAME" -}}
{{- $_ := unset $env "METRICS_OTEL__POD_UID" -}}
{{- $_ := unset $env "METRICS_CACHE__BACKEND" -}}
{{- $_ := unset $env "METRICS_REDIS_URL" -}}
{{- $_ := unset $env "METRICS_CACHE__KEY_SECRET" -}}
{{- $_ := unset $env "METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES" -}}
{{- $_ := unset $env "METRICS_PROVIDERS__KUEUE__NAMESPACES" -}}
{{- $_ := unset $env "METRICS_PROVIDERS__PROMQL__BASE_URL" -}}
{{- $_ := unset $env "METRICS_PROVIDERS__PROMQL__MIMIR_TENANT_ID" -}}
{{- $_ := unset $env "METRICS_OTEL__EXPORTER_OTLP_ENDPOINT" -}}
{{- $_ := unset $env "METRICS_OTEL__METRICS_ENABLED" -}}
- name: METRICS_PLATFORM_NAME
  value: {{ include "metrics-api.platformName" . | quote }}
- name: METRICS_CLUSTER_NAME
  value: {{ $clusterName | quote }}
- name: METRICS_OTEL__POD_UID
  valueFrom:
    fieldRef:
      fieldPath: metadata.uid
- name: METRICS_REDIS_URL
  valueFrom:
    secretKeyRef:
      name: {{ required "redis.urlSecret.name is required" .Values.redis.urlSecret.name | quote }}
      key: {{ required "redis.urlSecret.key is required" .Values.redis.urlSecret.key | quote }}
- name: METRICS_CACHE__KEY_SECRET
  valueFrom:
    secretKeyRef:
      name: {{ required "cacheKeySecret.name is required" .Values.cacheKeySecret.name | quote }}
      key: {{ required "cacheKeySecret.key is required" .Values.cacheKeySecret.key | quote }}
{{- if ne $clusterQueues "[]" }}
- name: METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES
  value: {{ $clusterQueues | quote }}
{{- end }}
{{- if ne $namespaces "[]" }}
- name: METRICS_PROVIDERS__KUEUE__NAMESPACES
  value: {{ $namespaces | quote }}
{{- end }}
{{- if $promqlBaseURL }}
- name: METRICS_PROVIDERS__PROMQL__BASE_URL
  value: {{ $promqlBaseURL | quote }}
{{- if $promqlTenantID }}
- name: METRICS_PROVIDERS__PROMQL__MIMIR_TENANT_ID
  value: {{ $promqlTenantID | quote }}
{{- end }}
{{- end }}
{{- if $otelEndpoint }}
- name: METRICS_OTEL__EXPORTER_OTLP_ENDPOINT
  value: {{ $otelEndpoint | quote }}
- name: METRICS_OTEL__METRICS_ENABLED
  value: "true"
{{- end }}
{{- range $key := keys $env | sortAlpha }}
{{- if not (regexMatch "^METRICS_OTEL__(TRACES|LOGS)_ENABLED$" $key) }}
- name: {{ $key }}
  value: {{ get $env $key | quote }}
{{- end }}
{{- end }}
{{- end }}
