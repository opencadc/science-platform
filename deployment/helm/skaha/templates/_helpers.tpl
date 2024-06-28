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
{{- if .Values.serviceAccount.create }}
{{- default (include "skaha.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{/*
CVMFS Volume
*/}}
{{- define "skaha.cvmfsVolume" -}}
{{- if .Values.deployment.skaha.cvmfsEnabled }}
- name: cvmfs
  hostPath:
    path: /cvmfs
    type: Directory
{{- end }}
{{- end }}

{{/*
CVMFS VolumeMount
*/}}
{{- define "skaha.cvmfsVolumeMount" -}}
{{- if .Values.deployment.skaha.cvmfsEnabled }}
- name: cvmfs
  mountPath: /cvmfs
  readOnly: true
  mountPropagation: HostToContainer
{{- end }}
{{- end }}
