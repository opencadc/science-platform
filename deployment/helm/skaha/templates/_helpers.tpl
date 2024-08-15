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
USER SESSION TEMPLATE DEFINITIONS
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
Common security context settings for User Session Jobs
*/}}
{{- define "skaha.job.securityContext" -}}
        runAsUser: ${skaha.posixid} 
        runAsGroup: ${skaha.posixid}
        fsGroup: ${skaha.posixid}
        supplementalGroups: [${skaha.supgroups}]
        runAsNonRoot: true
        seccompProfile:
          type: RuntimeDefault
{{- end }}
