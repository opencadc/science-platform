#!/usr/bin/env bash
set -euo pipefail

chart_dir="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"

helm dependency build "${chart_dir}" >/dev/null

render_skaha_deployment() {
    helm template otel-test "${chart_dir}" \
        --show-only templates/skaha-tomcat-deployment.yaml \
        --set deployment.skaha.sessions.userStorage.nodeURIPrefix=vos://storage.example.org~cavern \
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

disabled_render="$(render_skaha_deployment)"
assert_not_contains "${disabled_render}" "OTEL_"
assert_not_contains "${disabled_render}" "-javaagent:/opt/opentelemetry-javaagent/opentelemetry-javaagent.jar"

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
