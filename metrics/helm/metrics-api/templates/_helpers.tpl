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

{{- /*
Helm renders the configuration Kubernetes needs before the pod starts (RBAC
names, Secret references) and passes values through; Metrics validates every
setting at startup.
*/}}
{{- define "metrics-api.clusterQueues" -}}
{{- $queues := .Values.kueue.clusterQueues | default list | uniq -}}
{{- if not $queues -}}
{{- fail "kueue.clusterQueues must name at least one ClusterQueue" -}}
{{- end -}}
{{- toJson $queues -}}
{{- end }}

{{- define "metrics-api.kueueNamespaces" -}}
{{- $namespaces := .Values.kueue.namespaces | default list | uniq -}}
{{- if not $namespaces -}}
{{- fail "kueue.namespaces must name at least one Kueue namespace" -}}
{{- end -}}
{{- toJson $namespaces -}}
{{- end }}

{{- /*
Keys rendered from structured values. Setting one in env would add a second,
possibly plaintext, source for it, so the render fails instead.
*/}}
{{- define "metrics-api.renderedEnv" -}}
{{- toJson (list
  "METRICS_PLATFORM_NAME"
  "METRICS_CLUSTER_NAME"
  "METRICS_OTEL__POD_UID"
  "METRICS_REDIS_URL"
  "METRICS_CACHE__KEY_SECRET"
  "METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES"
  "METRICS_PROVIDERS__KUEUE__NAMESPACES"
  "METRICS_PROVIDERS__PROMQL__BASE_URL"
  "METRICS_PROVIDERS__PROMQL__MIMIR_TENANT_ID"
  "METRICS_OTEL__EXPORTER_OTLP_ENDPOINT"
  "METRICS_OTEL__METRICS_ENABLED") -}}
{{- end }}

{{- define "metrics-api.env" -}}
{{- $env := .Values.env | default dict -}}
{{- $rendered := include "metrics-api.renderedEnv" . | fromJsonArray -}}
{{- range $key := keys $env | sortAlpha -}}
{{- if has $key $rendered -}}
{{- fail (printf "env.%s is rendered from structured values; set those instead" $key) -}}
{{- end -}}
{{- end -}}
- name: METRICS_PLATFORM_NAME
  value: {{ .Values.platformName | default "canfar" | quote }}
- name: METRICS_CLUSTER_NAME
  value: {{ required "clusterName is required" .Values.clusterName | quote }}
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
- name: METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES
  value: {{ include "metrics-api.clusterQueues" . | quote }}
- name: METRICS_PROVIDERS__KUEUE__NAMESPACES
  value: {{ include "metrics-api.kueueNamespaces" . | quote }}
{{- with .Values.promql.baseUrl }}
- name: METRICS_PROVIDERS__PROMQL__BASE_URL
  value: {{ . | quote }}
{{- with $.Values.promql.mimirTenantId }}
- name: METRICS_PROVIDERS__PROMQL__MIMIR_TENANT_ID
  value: {{ . | quote }}
{{- end }}
{{- end }}
{{- with .Values.otel.endpoint }}
- name: METRICS_OTEL__EXPORTER_OTLP_ENDPOINT
  value: {{ . | quote }}
- name: METRICS_OTEL__METRICS_ENABLED
  value: "true"
{{- end }}
{{- range $key := keys $env | sortAlpha }}
- name: {{ $key }}
  value: {{ get $env $key | quote }}
{{- end }}
{{- end }}
