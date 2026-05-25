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

{{- define "skaha.metricsBackend.deploymentName" -}}
{{- printf "%s-skaha-metrics-api" .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- end }}

{{- define "skaha.metricsBackend.serviceName" -}}
{{- printf "%s-skaha-metrics-api-svc" .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- end }}

{{- define "skaha.metricsBackend.chartRedisURL" -}}
{{- printf "redis://%s-redis-master.%s.svc.%s:6379/0" .Release.Name .Release.Namespace .Values.kubernetesClusterDomain -}}
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

{{- define "skaha.metricsBackend.clusterRoleName" -}}
{{- printf "skaha-metrics-%s-%s-kueue-read" .Release.Namespace .Release.Name | replace "." "-" | trunc 63 | trimSuffix "-" -}}
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
USER SESSION TEMPLATE DEFINITIONS
*/}}

{{/*
The Home VOSpace Node URI (uses vos:// scheme) for the User Home directory in Cavern.
*/}}
{{- define "skaha.job.userStorage.homeURI" -}}
{{- $nodeURIPrefix := trimAll "/" (required ".Values.deployment.skaha.sessions.userStorage.nodeURIPrefix nodeURIPrefix is required." .Values.deployment.skaha.sessions.userStorage.nodeURIPrefix) -}}
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
            memory: "32Mi"
            cpu: "100m"
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
            memory: "32Mi"
            cpu: "100m"
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
        fsGroup: ${skaha.posixid}
        fsGroupChangePolicy: "OnRootMismatch"
        supplementalGroups: [${skaha.supgroups}]
        runAsNonRoot: true
        seccompProfile:
          type: RuntimeDefault
{{- end }}
