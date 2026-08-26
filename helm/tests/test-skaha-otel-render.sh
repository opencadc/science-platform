#!/usr/bin/env bash
set -euo pipefail

chart_dir="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"

metrics_required_args=(
    --set metricsBackend.clusterName=prod-cluster
    --set metricsBackend.cacheKeySecret.name=metrics-cache
    --set metricsBackend.redis.urlSecret.name=metrics-redis
    --set-string 'metricsBackend.env.METRICS_PROVIDERS__KUEUE__NAMESPACES=["metrics-workloads"]'
    --set-string 'metricsBackend.env.METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES=["cq-test"]'
)

helm dependency build "${chart_dir}" >/dev/null

if helm show values "${chart_dir}" | grep -Eq '^[[:space:]]*cacheBackend:'; then
    printf 'Expected root chart values to omit obsolete cacheBackend selector\n' >&2
    exit 1
fi

render_skaha_deployment() {
    helm template otel-test "${chart_dir}" \
        --show-only templates/skaha-tomcat-deployment.yaml \
        --set deployment.skaha.sessions.userStorage.nodeURIPrefix=vos://storage.example.org~cavern \
        "$@"
}

render_metrics_rbac() {
    helm template metrics-rbac-test "${chart_dir}" \
        --show-only templates/metricsBackend-rbac.yaml \
        "${metrics_required_args[@]}" \
        --set deployment.skaha.sessions.userStorage.nodeURIPrefix=vos://storage.example.org~cavern \
        --set metricsBackend.enabled=true \
        --set metricsBackend.rbac.enabled=true \
        "$@"
}

render_metrics_deployment() {
    helm template metrics-deployment-test "${chart_dir}" \
        --show-only templates/metricsBackend-deployment.yaml \
        "${metrics_required_args[@]}" \
        --set deployment.skaha.sessions.userStorage.nodeURIPrefix=vos://storage.example.org~cavern \
        --set metricsBackend.enabled=true \
        "$@"
}

render_metrics_networkpolicy() {
    helm template metrics-networkpolicy-test "${chart_dir}" \
        --show-only templates/metricsBackend-networkpolicy.yaml \
        "${metrics_required_args[@]}" \
        --set deployment.skaha.sessions.userStorage.nodeURIPrefix=vos://storage.example.org~cavern \
        --set metricsBackend.enabled=true \
        "$@"
}

assert_not_contains() {
    local content="$1"
    local unexpected="$2"

    if grep -Fq -- "${unexpected}" <<<"${content}"; then
        printf 'Expected rendered chart not to contain:\n%s\n' "${unexpected}" >&2
        exit 1
    fi
}

assert_contains() {
    local content="$1"
    local expected="$2"

    if ! grep -Fq -- "${expected}" <<<"${content}"; then
        printf 'Expected rendered chart to contain:\n%s\n' "${expected}" >&2
        exit 1
    fi
}

assert_env_value() {
    local content="$1"
    local name="$2"
    local expected="$3"
    local block

    block="$(grep -A1 -F -- "- name: ${name}" <<<"${content}" || true)"
    if ! grep -Fq -- "value: \"${expected}\"" <<<"${block}"; then
        printf 'Expected rendered env %s to have value:\n%s\n' "${name}" "${expected}" >&2
        exit 1
    fi
}

assert_single_env_name() {
    local content="$1"
    local name="$2"
    local count

    count="$(grep -c -F -- "- name: ${name}" <<<"${content}" || true)"
    if [[ ${count} -ne 1 ]]; then
        printf 'Expected exactly one rendered env %s, got %s\n' "${name}" "${count}" >&2
        exit 1
    fi
}

assert_readiness_probe() {
    local content="$1"
    local path

    path="$(awk '/readinessProbe:/{capture=1} capture && /path:/{print $2; exit}' <<<"${content}")"
    if [[ ${path} != "/readyz" ]]; then
        printf 'Expected readiness probe path /readyz, got %s\n' "${path}" >&2
        exit 1
    fi
}

assert_liveness_probe() {
    local content="$1"
    local path

    path="$(awk '/livenessProbe:/{capture=1} capture && /path:/{print $2; exit}' <<<"${content}")"
    if [[ ${path} != "/healthz" ]]; then
        printf 'Expected liveness probe path /healthz, got %s\n' "${path}" >&2
        exit 1
    fi
}

assert_env_order() {
    local content="$1"
    local first_name="$2"
    local second_name="$3"
    local first_line
    local second_line

    first_line="$(grep -n -F -- "- name: ${first_name}" <<<"${content}" | cut -d: -f1 | head -n1)"
    second_line="$(grep -n -F -- "- name: ${second_name}" <<<"${content}" | cut -d: -f1 | head -n1)"
    if [[ -z ${first_line} || -z ${second_line} || ${first_line} -ge ${second_line} ]]; then
        printf 'Expected env %s before %s\n' "${first_name}" "${second_name}" >&2
        exit 1
    fi
}

assert_render_fails() {
    local expected="$1"
    shift
    local error
    local status

    set +e
    error="$(render_skaha_deployment "$@" 2>&1 >/dev/null)"
    status=$?
    set -e

    if [[ ${status} -eq 0 ]]; then
        printf 'Expected rendered chart to fail with:\n%s\n' "${expected}" >&2
        exit 1
    fi

    if ! grep -Fq "${expected}" <<<"${error}"; then
        printf 'Expected render error:\n%s\nGot:\n%s\n' "${expected}" "${error}" >&2
        exit 1
    fi
}

assert_metrics_render_fails() {
    local expected="$1"
    shift
    local error
    local status

    set +e
    error="$(render_metrics_deployment "$@" 2>&1 >/dev/null)"
    status=$?
    set -e

    if [[ ${status} -eq 0 ]]; then
        printf 'Expected Metrics render to fail with:\n%s\n' "${expected}" >&2
        exit 1
    fi

    if ! grep -Fq "${expected}" <<<"${error}"; then
        printf 'Expected Metrics render error:\n%s\nGot:\n%s\n' "${expected}" "${error}" >&2
        exit 1
    fi
}

disabled_render="$(render_skaha_deployment)"
assert_not_contains "${disabled_render}" "OTEL_"
assert_not_contains "${disabled_render}" "-javaagent:/opt/opentelemetry-javaagent/opentelemetry-javaagent.jar"

queue_render="$(
    render_skaha_deployment \
        --set deployment.skaha.sessions.kueue.default.queueName=cadc-default \
        --set deployment.skaha.sessions.kueue.default.priorityClass=high
)"
assert_env_value "${queue_render}" "SKAHA_QUEUE_DEFAULT_NAME" "cadc-default"
assert_env_value "${queue_render}" "SKAHA_QUEUE_DEFAULT_PRIORITY_CLASS" "high"
assert_not_contains "${queue_render}" "SKAHA_QUEUE_DEFAULT_COMMUNITY"

endpoint_only_render="$(render_skaha_deployment --set telemetry.otlp.destination=http://otel-collector:4318)"
assert_not_contains "${endpoint_only_render}" "OTEL_"
assert_not_contains "${endpoint_only_render}" "-javaagent:/opt/opentelemetry-javaagent/opentelemetry-javaagent.jar"

assert_render_fails \
    "telemetry.metrics is reserved for future skaha-metrics OpenTelemetry support" \
    --set telemetry.metrics=true \
    --set telemetry.otlp.destination=http://otel-collector:4318

enabled_render="$(
    render_skaha_deployment \
        --set telemetry.controller=true \
        --set telemetry.otlp.destination=http://otel-collector:4318 \
        --set telemetry.otlp.interval=15
)"
assert_env_value "${enabled_render}" "CATALINA_OPTS" "-javaagent:/opt/opentelemetry-javaagent/opentelemetry-javaagent.jar"
assert_env_value "${enabled_render}" "OTEL_SERVICE_NAME" "skaha-controller"
assert_env_value "${enabled_render}" "OTEL_METRICS_EXPORTER" "otlp"
assert_env_value "${enabled_render}" "OTEL_EXPORTER_OTLP_ENDPOINT" "http://otel-collector:4318"
assert_env_value "${enabled_render}" "OTEL_EXPORTER_OTLP_PROTOCOL" "http/protobuf"
assert_env_value "${enabled_render}" "OTEL_METRIC_EXPORT_INTERVAL" "15000"
assert_env_value "${enabled_render}" "OTEL_TRACES_EXPORTER" "none"
assert_env_value "${enabled_render}" "OTEL_LOGS_EXPORTER" "none"

assert_render_fails \
    "telemetry.controller is true but telemetry.otlp.destination is empty" \
    --set telemetry.controller=true

assert_render_fails \
    "telemetry.otlp.interval must be a positive integer number of seconds when telemetry.controller is true" \
    --set telemetry.controller=true \
    --set telemetry.otlp.destination=http://otel-collector:4318 \
    --set telemetry.otlp.interval=bogus

assert_render_fails \
    "deployment.skaha.extraEnv cannot set CATALINA_OPTS when telemetry.controller is true" \
    --set telemetry.controller=true \
    --set telemetry.otlp.destination=http://otel-collector:4318 \
    --set deployment.skaha.extraEnv[0].name=CATALINA_OPTS \
    --set deployment.skaha.extraEnv[0].value=-Xmx1g

metrics_rbac_render="$(
    render_metrics_rbac
)"
assert_contains "${metrics_rbac_render}" 'resources: ["clusterqueues"]'
assert_contains "${metrics_rbac_render}" 'verbs: ["get"]'
assert_contains "${metrics_rbac_render}" 'resourceNames:'
assert_contains "${metrics_rbac_render}" '- "cq-test"'
assert_contains "${metrics_rbac_render}" 'resources: ["localqueues"]'
assert_contains "${metrics_rbac_render}" 'verbs: ["list"]'
assert_not_contains "${metrics_rbac_render}" 'resources: ["pods"]'

metrics_serviceaccount_render="$(
    render_metrics_deployment \
        --set metricsBackend.serviceAccount.create=false \
        --set metricsBackend.serviceAccount.name=metrics-existing-sa
)"
assert_contains "${metrics_serviceaccount_render}" 'serviceAccountName: metrics-existing-sa'
assert_not_contains "${metrics_serviceaccount_render}" 'serviceAccountName: default'

assert_metrics_render_fails \
    "metricsBackend.serviceAccount.name must not be default" \
    --set metricsBackend.serviceAccount.name=default

metrics_networkpolicy_render="$(
    render_metrics_networkpolicy \
        --set-string 'metricsBackend.networkPolicy.egress.kubeApiServer[0].to[0].ipBlock.cidr=10.96.0.0/12' \
        --set metricsBackend.networkPolicy.egress.kubeApiServer[0].ports[0].port=443 \
        --set metricsBackend.networkPolicy.egress.kubeApiServer[0].ports[0].protocol=TCP \
        --set-string 'metricsBackend.networkPolicy.egress.redis[0].to[0].ipBlock.cidr=10.0.0.0/8' \
        --set metricsBackend.networkPolicy.egress.redis[0].ports[0].port=6379 \
        --set metricsBackend.networkPolicy.egress.redis[0].ports[0].protocol=TCP \
        --set-string 'metricsBackend.networkPolicy.egress.promql[0].to[0].ipBlock.cidr=192.0.2.0/24' \
        --set metricsBackend.networkPolicy.egress.promql[0].ports[0].port=9090 \
        --set metricsBackend.networkPolicy.egress.promql[0].ports[0].protocol=TCP \
        --set-string 'metricsBackend.networkPolicy.egress.otlp[0].to[0].ipBlock.cidr=198.51.100.0/24' \
        --set metricsBackend.networkPolicy.egress.otlp[0].ports[0].port=4318 \
        --set metricsBackend.networkPolicy.egress.otlp[0].ports[0].protocol=TCP
)"
assert_contains "${metrics_networkpolicy_render}" 'kind: NetworkPolicy'
assert_contains "${metrics_networkpolicy_render}" 'run: metrics-networkpolicy-test-skaha-tomcat'
assert_contains "${metrics_networkpolicy_render}" 'app.kubernetes.io/name: skaha-metrics-api'
assert_contains "${metrics_networkpolicy_render}" 'port: 53'
assert_contains "${metrics_networkpolicy_render}" 'cidr: 10.96.0.0/12'
assert_contains "${metrics_networkpolicy_render}" 'port: 443'
assert_contains "${metrics_networkpolicy_render}" 'cidr: 10.0.0.0/8'
assert_contains "${metrics_networkpolicy_render}" 'port: 6379'
assert_contains "${metrics_networkpolicy_render}" 'cidr: 192.0.2.0/24'
assert_contains "${metrics_networkpolicy_render}" 'port: 9090'
assert_contains "${metrics_networkpolicy_render}" 'cidr: 198.51.100.0/24'
assert_contains "${metrics_networkpolicy_render}" 'port: 4318'
assert_not_contains "${metrics_networkpolicy_render}" 'component: redis'
assert_not_contains "${metrics_networkpolicy_render}" 'component: collector'

metrics_namespace_rbac_render="$(
    render_metrics_rbac \
        --set-string 'metricsBackend.env.METRICS_PROVIDERS__KUEUE__NAMESPACES=["metrics-workloads"]'
)"
assert_contains "${metrics_namespace_rbac_render}" 'namespace: metrics-workloads'
assert_not_contains "${metrics_namespace_rbac_render}" 'namespace: canfar-workloads'

set +e
invalid_queue_error="$(
    render_metrics_rbac \
        --set-string 'metricsBackend.env.METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES=["cq/a"]' \
        2>&1 >/dev/null
)"
invalid_queue_status=$?
set -e
if [[ ${invalid_queue_status} -eq 0 ]] || ! grep -Fq 'DNS-subdomain' <<<"${invalid_queue_error}"; then
    printf 'Expected invalid Metrics ClusterQueue name to fail DNS validation; got:\n%s\n' "${invalid_queue_error}" >&2
    exit 1
fi

assert_metrics_render_fails \
    "metricsBackend.env.METRICS_CACHE__BACKEND is obsolete and must be exactly redis" \
    --set metricsBackend.env.METRICS_CACHE__BACKEND=memory

assert_metrics_render_fails \
    "metricsBackend.redis.urlSecret.name is required" \
    --set metricsBackend.redis.urlSecret.name=

assert_metrics_render_fails \
    "metricsBackend.cacheKeySecret.name is required" \
    --set metricsBackend.cacheKeySecret.name=

assert_metrics_render_fails \
    "metricsBackend.redis.urlSecret.key is required" \
    --set metricsBackend.redis.urlSecret.key=

assert_metrics_render_fails \
    "metricsBackend.cacheKeySecret.key is required" \
    --set metricsBackend.cacheKeySecret.key=

assert_metrics_render_fails \
    "metricsBackend.clusterName must be a real lower-case DNS cluster identity" \
    --set metricsBackend.clusterName=UNKNOWN

assert_metrics_render_fails \
    "metricsBackend.redis.urlSecret.name is required" \
    --set metricsBackend.redis.urlSecret.name= \
    --set metricsBackend.env.METRICS_REDIS_URL=redis://plaintext.example/0

assert_metrics_render_fails \
    "metricsBackend.cacheKeySecret.name is required" \
    --set metricsBackend.cacheKeySecret.name= \
    --set metricsBackend.env.METRICS_CACHE__KEY_SECRET=plaintext-cache-key

metrics_contract_render="$(
    helm template metrics-contract-test "${chart_dir}" \
        --namespace canfar \
        --show-only templates/skaha-tomcat-deployment.yaml \
        --show-only templates/metricsBackend-deployment.yaml \
        --show-only templates/metricsBackend-serviceaccount.yaml \
        "${metrics_required_args[@]}" \
        --set deployment.skaha.sessions.userStorage.nodeURIPrefix=vos://storage.example.org~cavern \
        --set deployment.skaha.serviceAccountName=shared-skaha \
        --set serviceAccount.create=false \
        --set metricsBackend.enabled=true
)"
if [[ $(grep -c -F 'serviceAccountName: shared-skaha' <<<"${metrics_contract_render}") -ne 1 ]]; then
    printf 'Expected only the Skaha Pod to use serviceAccountName shared-skaha\n' >&2
    exit 1
fi
if [[ $(grep -c -F 'serviceAccountName: metrics-contract-test-skaha-metrics-sa' <<<"${metrics_contract_render}") -ne 1 ]]; then
    printf 'Expected Metrics Pod to use its dedicated ServiceAccount\n' >&2
    exit 1
fi
assert_contains "${metrics_contract_render}" 'kind: ServiceAccount'
assert_contains "${metrics_contract_render}" 'name: metrics-contract-test-skaha-metrics-sa'
assert_contains "${metrics_contract_render}" 'name: SKAHA_METRICS_BACKEND_URL'
assert_contains "${metrics_contract_render}" 'value: "http://metrics-contract-test-skaha-metrics-api-svc.canfar.svc.cluster.local:8000"'
assert_env_value "${metrics_contract_render}" "METRICS_PLATFORM_NAME" "canfar"
assert_env_value "${metrics_contract_render}" "SKAHA_METRICS_PLATFORM_NAME" "canfar"
assert_contains "${metrics_contract_render}" 'image: "images.opencadc.org/platform/metrics:v0.1.5"'

metrics_probe_render="$(
    helm template metrics-probe-test "${chart_dir}" \
        --show-only templates/metricsBackend-deployment.yaml \
        --show-only templates/skaha-tomcat-deployment.yaml \
        "${metrics_required_args[@]}" \
        --set deployment.skaha.sessions.userStorage.nodeURIPrefix=vos://storage.example.org~cavern \
        --set metricsBackend.enabled=true
)"
assert_readiness_probe "${metrics_probe_render}"
assert_liveness_probe "${metrics_probe_render}"

metrics_env_render="$(
    helm template metrics-env-test "${chart_dir}" \
        --show-only templates/metricsBackend-deployment.yaml \
        --show-only templates/skaha-tomcat-deployment.yaml \
        "${metrics_required_args[@]}" \
        --set deployment.skaha.sessions.userStorage.nodeURIPrefix=vos://storage.example.org~cavern \
        --set metricsBackend.enabled=true \
        --set metricsBackend.platformName=chart-platform \
        --set metricsBackend.redis.urlSecret.name=production-redis \
        --set metricsBackend.redis.urlSecret.key=redis-url \
        --set metricsBackend.cacheKeySecret.name=production-cache \
        --set metricsBackend.cacheKeySecret.key=cache-key \
        --set metricsBackend.env.METRICS_PLATFORM_NAME=env-platform \
        --set metricsBackend.env.METRICS_OTEL__POD_UID=unsafe-pod-uid \
        --set metricsBackend.env.METRICS_CACHE__BACKEND=redis \
        --set metricsBackend.env.METRICS_REDIS_URL=unsafe-redis-url \
        --set metricsBackend.env.METRICS_CACHE__KEY_SECRET=unsafe-cache-secret \
        --set metricsBackend.env.METRICS_PROVIDERS__PROMQL__ENABLED=false \
        --set metricsBackend.env.METRICS_PROVIDERS__PROMQL__BASE_URL=http://unsafe-prometheus \
        --set metricsBackend.env.METRICS_OTEL__METRICS_ENABLED=false \
        --set metricsBackend.env.METRICS_OTEL__TRACES_ENABLED=true \
        --set metricsBackend.env.METRICS_OTEL__LOGS_ENABLED=true \
        --set metricsBackend.env.METRICS_OTEL__EXPORTER_OTLP_ENDPOINT=http://unsafe-otel \
        --set metricsBackend.prometheus.url=http://prometheus.metrics.svc:9090 \
        --set metricsBackend.otlp.endpoint=http://otel-collector.observability.svc:4318 \
        --set metricsBackend.env.METRICS_CUSTOM_Z=last \
        --set metricsBackend.env.METRICS_CUSTOM_A=first
)"
assert_single_env_name "${metrics_env_render}" "METRICS_PLATFORM_NAME"
assert_single_env_name "${metrics_env_render}" "METRICS_CLUSTER_NAME"
assert_single_env_name "${metrics_env_render}" "METRICS_OTEL__POD_UID"
assert_single_env_name "${metrics_env_render}" "METRICS_REDIS_URL"
assert_single_env_name "${metrics_env_render}" "METRICS_CACHE__KEY_SECRET"
assert_contains "${metrics_env_render}" 'fieldPath: metadata.uid'
assert_contains "${metrics_env_render}" 'name: "production-redis"'
assert_contains "${metrics_env_render}" 'key: "redis-url"'
assert_contains "${metrics_env_render}" 'name: "production-cache"'
assert_contains "${metrics_env_render}" 'key: "cache-key"'
assert_contains "${metrics_env_render}" 'value: "env-platform"'
assert_env_value "${metrics_env_render}" "METRICS_CLUSTER_NAME" "prod-cluster"
assert_not_contains "${metrics_env_render}" 'METRICS_CACHE__BACKEND'
assert_contains "${metrics_env_render}" 'value: "first"'
assert_contains "${metrics_env_render}" 'value: "last"'
assert_not_contains "${metrics_env_render}" 'unsafe-pod-uid'
assert_not_contains "${metrics_env_render}" 'unsafe-redis-url'
assert_not_contains "${metrics_env_render}" 'unsafe-cache-secret'
assert_not_contains "${metrics_env_render}" 'METRICS_PROVIDERS__PROMQL__ENABLED'
assert_env_value "${metrics_env_render}" "METRICS_PROVIDERS__PROMQL__BASE_URL" "http://prometheus.metrics.svc:9090"
assert_env_value "${metrics_env_render}" "METRICS_OTEL__METRICS_ENABLED" "true"
assert_env_value "${metrics_env_render}" "METRICS_OTEL__EXPORTER_OTLP_ENDPOINT" "http://otel-collector.observability.svc:4318"
assert_not_contains "${metrics_env_render}" 'METRICS_OTEL__TRACES_ENABLED'
assert_not_contains "${metrics_env_render}" 'METRICS_OTEL__LOGS_ENABLED'
assert_not_contains "${metrics_env_render}" 'unsafe-prometheus'
assert_not_contains "${metrics_env_render}" 'unsafe-otel'
assert_env_order "${metrics_env_render}" "METRICS_CUSTOM_A" "METRICS_CUSTOM_Z"
assert_env_value "${metrics_env_render}" "SKAHA_METRICS_PLATFORM_NAME" "env-platform"

long_release="release-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
long_name_render="$(
    helm template "${long_release}" "${chart_dir}" \
        --show-only templates/metricsBackend-deployment.yaml \
        --show-only templates/metricsBackend-service.yaml \
        "${metrics_required_args[@]}" \
        --set deployment.skaha.sessions.userStorage.nodeURIPrefix=vos://storage.example.org~cavern \
        --set metricsBackend.enabled=true
)"
deployment_name="$(awk '/^kind: Deployment/{capture=1; next} capture && /^  name:/{print $2; exit}' <<<"${long_name_render}")"
service_name="$(awk '/^kind: Service/{capture=1; next} capture && /^  name:/{print $2; exit}' <<<"${long_name_render}")"
if [[ -z ${deployment_name} || -z ${service_name} || ${deployment_name} == "${service_name}" ]]; then
    printf 'Expected distinct long-release Metrics Deployment and Service names, got %s and %s\n' "${deployment_name}" "${service_name}" >&2
    exit 1
fi
if [[ ${#deployment_name} -gt 63 || ${#service_name} -gt 63 ]]; then
    printf 'Expected long-release Metrics names to fit DNS limits, got %s and %s\n' "${deployment_name}" "${service_name}" >&2
    exit 1
fi
if [[ ${deployment_name} != *-skaha-metrics-api || ${service_name} != *-skaha-metrics-api-svc ]]; then
    printf 'Expected long-release Metrics names to preserve suffixes, got %s and %s\n' "${deployment_name}" "${service_name}" >&2
    exit 1
fi

long_release_variant="${long_release}y"
long_variant_render="$(
    helm template "${long_release_variant}" "${chart_dir}" \
        --show-only templates/metricsBackend-deployment.yaml \
        "${metrics_required_args[@]}" \
        --set deployment.skaha.sessions.userStorage.nodeURIPrefix=vos://storage.example.org~cavern \
        --set metricsBackend.enabled=true
)"
variant_deployment_name="$(awk '/^kind: Deployment/{capture=1; next} capture && /^  name:/{print $2; exit}' <<<"${long_variant_render}")"
if [[ -z ${variant_deployment_name} || ${deployment_name} == "${variant_deployment_name}" ]]; then
    printf 'Expected distinct long-release Metrics Deployment names, got %s and %s\n' "${deployment_name}" "${variant_deployment_name}" >&2
    exit 1
fi

metrics_ingress_default="$(
    helm template metrics-ingress-default "${chart_dir}" \
        --show-only templates/skaha-ingress.yaml \
        "${metrics_required_args[@]}" \
        --set deployment.skaha.sessions.userStorage.nodeURIPrefix=vos://storage.example.org~cavern \
        --set metricsBackend.enabled=true
)"
assert_not_contains "${metrics_ingress_default}" "skaha-metrics-api-svc"

metrics_ingress_enabled="$(
    helm template metrics-ingress-enabled "${chart_dir}" \
        --show-only templates/skaha-ingress.yaml \
        "${metrics_required_args[@]}" \
        --set deployment.skaha.sessions.userStorage.nodeURIPrefix=vos://storage.example.org~cavern \
        --set metricsBackend.enabled=true \
        --set metricsBackend.ingress.enabled=true
)"
assert_contains "${metrics_ingress_enabled}" "skaha-metrics-api-svc"
assert_contains "${metrics_ingress_enabled}" "path: /apis/canfar.net/v1alpha1/metrics"
