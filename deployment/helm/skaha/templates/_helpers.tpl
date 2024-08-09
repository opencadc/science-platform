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
JOB CONFIGURATION DEFINITIONS
*/}}

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
      - name: init-users-groups
        image: images.opencadc.org/library/cadc-tomcat:1
        command: ["/init-users-groups/init-users-groups-from-service.sh"]
        volumeMounts:
        - mountPath: "/etc-passwd"
          name: etc-passwd
        - mountPath: "/etc-group"
          name: etc-group
        - mountPath: "/init-users-groups"
          name: init-users-groups
        - mountPath: "${SKAHA_TLD}"
          name: cavern-volume
          subPath: "cavern"
        env:
        - name: HOME
          value: "${SKAHA_TLD}/home/${skaha.userid}"
        - name: POSIX_MAPPER_URI
          value: ${POSIX_MAPPER_URI}
        - name: REGISTRY_URL
          value: ${REGISTRY_URL}
        securityContext:
          privileged: false
          allowPrivilegeEscalation: false
          capabilities:
            drop:
              - ALL
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
The affinity for Jobs.
*/}}
{{- define "skaha.job.nodeSelector" -}}
  selector:
    matchExpressions:
    - key: skaha.opencadc.org/node-type
      operator: NotIn
      values:
      - service-node
{{- end }}

{{/*
Common environment variables for User Sessions.
*/}}
{{- define "skaha.job.environment.common" -}}
        - name: HOME
          value: "${SKAHA_TLD}/home/${skaha.userid}"
        - name: skaha_sessionid
          value: "${skaha.sessionid}"
        - name: "NVIDIA_CUDA_MAJOR_VERSION"
          value: "${software.gpu.cuda.majorVersion}"
{{- end }}


{{/*
Obtain the environment variable array for Jupyter Notebooks.
Usage:
  {{ template "skaha.job.environment.notebook" }}
*/}}
{{- define "skaha.job.environment.notebook" -}}
        {{ template "skaha.job.environment.common" . }}
        - name: skaha_hostname
          value: "${skaha.hostname}"
        - name: skaha_username
          value: "${skaha.userid}"
        - name: JUPYTER_TOKEN
          value: "${skaha.sessionid}"
        - name: JUPYTER_CONFIG_DIR
          value: "${SKAHA_TLD}/home/${skaha.userid}/.jupyter/"
        - name: JUPYTER_DATA_DIR
          value: "${SKAHA_TLD}/home/${skaha.userid}/.local/share/jupyter/"
        - name: JUPYTER_RUNTIME_DIR
          value: "${SKAHA_TLD}/home/${skaha.userid}/.local/share/jupyter/runtime/"
        - name: JUPYTER_PATH
          value: "${SKAHA_TLD}/home/${skaha.userid}/.jupyter/"
        - name: JUPYTERLAB_WORKSPACES_DIR
          value: "${SKAHA_TLD}/home/${skaha.userid}/.jupyter/lab/workspaces/"
        - name: JUPYTERLAB_SETTINGS_DIR
          value: "${SKAHA_TLD}/home/${skaha.userid}/.jupyter/lab/user-settings/"
        - name: NB_USER
          value: "${skaha.userid}"
        - name: NB_UID
          value: "${skaha.posixid}"
        - name: PWD
          value: "${SKAHA_TLD}/home/${skaha.userid}"
        - name: XDG_CACHE_HOME
          value: "${SKAHA_TLD}/home/${skaha.userid}"
        - name: JULIA_NUM_THREADS
          value: "${software.requests.cores}"
        - name: OPENBLAS_NUM_THREADS
          value: "${software.requests.cores}"
        - name: MKL_NUM_THREADS
          value: "${software.requests.cores}"
        - name: OMP_NUM_THREADS
          value: "${software.requests.cores}"
{{- end }}

{{/*
Obtain the environment variable array for CARTA sessions.
Usage:
  {{ template "skaha.job.environment.carta" }}
*/}}
{{- define "skaha.job.environment.carta" -}}
        {{ template "skaha.job.environment.common" . }}
        - name: skaha_hostname
          value: "${skaha.hostname}"
        - name: skaha_username
          value: "${skaha.userid}"
        - name: PWD
          value: "${SKAHA_TLD}/home/${skaha.userid}"
        - name: OMP_NUM_THREADS
          value: "${software.requests.cores}"
{{- end }}

{{/*
Obtain the environment variable array for Desktop sessions.
*/}}
{{- define "skaha.job.environment.desktop" -}}
        {{ template "skaha.job.environment.common" . }}
        - name: VNC_PW
          value: "${skaha.sessionid}"
        - name: skaha_hostname
          value: "${skaha.hostname}"
        - name: skaha_username
          value: "${skaha.userid}"
        - name: MOZ_FORCE_DISABLE_E10S
          value: "1"
        - name: SKAHA_API_VERSION
          value: "v0"
{{- end }}

{{/*
Obtain the environment variable array for Desktop Applications launched from within a Desktop session.
*/}}
{{- define "skaha.job.environment.desktopApp" -}}
        {{ template "skaha.job.environment.common" . }}
        - name: DISPLAY
          value: "${software.targetip}"
        - name: GDK_SYNCHRONIZE
          value: "1"
        - name: SHELL
          value: "/bin/bash"
        - name: PWD
          value: "${SKAHA_TLD}/home/${skaha.userid}"
        - name: OMP_NUM_THREADS
          value: "${software.requests.cores}"
{{- end }}

{{/*
Obtain the environment variable array for Headless sessions.
*/}}
{{- define "skaha.job.environment.headless" -}}
        {{ template "skaha.job.environment.common" . }}
        - name: skaha_hostname
          value: "${skaha.hostname}"
        - name: skaha_username
          value: "${skaha.userid}"
        - name: PWD
          value: "${SKAHA_TLD}/home/${skaha.userid}"
        - name: OMP_NUM_THREADS
          value: "${software.requests.cores}"
{{- end }}

{{/*
Obtain the environment variable array for Contributed sessions.
*/}}
{{- define "skaha.job.environment.contributed" -}}
        {{ template "skaha.job.environment.common" . }}
        - name: skaha_hostname
          value: "${skaha.hostname}"
        - name: skaha_username
          value: "${skaha.userid}"
        - name: PWD
          value: "${SKAHA_TLD}/home/${skaha.userid}"
        - name: JULIA_NUM_THREADS
          value: "${software.requests.cores}"
        - name: OPENBLAS_NUM_THREADS
          value: "${software.requests.cores}"
        - name: MKL_NUM_THREADS
          value: "${software.requests.cores}"
        - name: OMP_NUM_THREADS
          value: "${software.requests.cores}"
{{- end }}
