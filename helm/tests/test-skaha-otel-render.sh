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

disabled_render="$(render_skaha_deployment)"
assert_not_contains "${disabled_render}" "OTEL_"
assert_not_contains "${disabled_render}" "-javaagent:/opt/opentelemetry-javaagent/opentelemetry-javaagent.jar"

endpoint_only_render="$(render_skaha_deployment --set telemetry.otlpEndpoint=http://otel-collector:4318)"
assert_not_contains "${endpoint_only_render}" "OTEL_"
assert_not_contains "${endpoint_only_render}" "-javaagent:/opt/opentelemetry-javaagent/opentelemetry-javaagent.jar"

enabled_render="$(
    render_skaha_deployment \
        --set telemetry.skahaEnabled=true \
        --set telemetry.otlpEndpoint=http://otel-collector:4318 \
        --set telemetry.exportIntervalSeconds=15
)"
assert_env_value "${enabled_render}" "CATALINA_OPTS" "-javaagent:/opt/opentelemetry-javaagent/opentelemetry-javaagent.jar"
assert_env_value "${enabled_render}" "OTEL_SERVICE_NAME" "canfar-skaha"
assert_env_value "${enabled_render}" "OTEL_METRICS_EXPORTER" "otlp"
assert_env_value "${enabled_render}" "OTEL_EXPORTER_OTLP_ENDPOINT" "http://otel-collector:4318"
assert_env_value "${enabled_render}" "OTEL_EXPORTER_OTLP_PROTOCOL" "http/protobuf"
assert_env_value "${enabled_render}" "OTEL_METRIC_EXPORT_INTERVAL" "15000"
assert_env_value "${enabled_render}" "OTEL_TRACES_EXPORTER" "none"
assert_env_value "${enabled_render}" "OTEL_LOGS_EXPORTER" "none"

if missing_endpoint_err="$(render_skaha_deployment --set telemetry.skahaEnabled=true 2>&1 >/dev/null)"; then
    printf 'Expected telemetry.skahaEnabled=true without telemetry.otlpEndpoint to fail.\n' >&2
    exit 1
fi

if ! grep -Fq "telemetry.skahaEnabled is true but telemetry.otlpEndpoint is empty" <<<"${missing_endpoint_err}"; then
    printf 'Expected missing endpoint error, got:\n' >&2
    printf '%s\n' "${missing_endpoint_err}" >&2
    exit 1
fi

if catalina_conflict_err="$(
    render_skaha_deployment \
        --set telemetry.skahaEnabled=true \
        --set telemetry.otlpEndpoint=http://otel-collector:4318 \
        --set deployment.skaha.extraEnv[0].name=CATALINA_OPTS \
        --set deployment.skaha.extraEnv[0].value=-Xmx1g \
        2>&1 >/dev/null
)"; then
    printf 'Expected telemetry-managed CATALINA_OPTS to reject deployment.skaha.extraEnv CATALINA_OPTS.\n' >&2
    exit 1
fi

if ! grep -Fq "deployment.skaha.extraEnv cannot set CATALINA_OPTS when telemetry.skahaEnabled is true" <<<"${catalina_conflict_err}"; then
    printf 'Expected CATALINA_OPTS conflict error, got:\n' >&2
    printf '%s\n' "${catalina_conflict_err}" >&2
    exit 1
fi
