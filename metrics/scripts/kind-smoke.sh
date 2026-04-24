#!/usr/bin/env bash
#
# Kind integration smoke: Kueue + test-setup.yaml + docker build + Helm + pytest -m integration.
# Local: creates one-node kind cluster when missing; keeps a background port-forward by default.
# CI (KIND_SMOKE_CI=1): expects cluster to already exist and tears down the port-forward on exit.
#
# Key env:
#   KIND_CLUSTER_NAME, KUBE_CONTEXT, NAMESPACE, PORT_FORWARD_PORT, KIND_SMOKE_CI,
#   KIND_SMOKE_EXIT_AFTER_TESTS, KIND_IMAGE_LOAD_TIMEOUT_SECONDS, KIND_DELETE_ON_EXIT,
#   METRICS_IMAGE_REPOSITORY, METRICS_IMAGE_TAG, KIND_SMOKE_SKIP_BUILD.
#
set -euo pipefail

_SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
_METRICS_ROOT="$(cd "${_SCRIPT_DIR}/.." && pwd)"

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  cat <<'EOF'
Usage: bash scripts/kind-smoke.sh

  Full Kueue + metrics API smoke on kind, then integration tests.
  For options and environment, see the header comment in this file.

  After a successful local run, stop the background API port-forward with:
    bash scripts/kind-smoke-teardown.sh

  CI: set KIND_SMOKE_CI=1 (cluster must already exist; cleans up the port-forward).
EOF
  exit 0
fi

# shellcheck disable=SC1091
source "${_SCRIPT_DIR}/check-prerequisites.sh"
# shellcheck disable=SC1091
source "${_SCRIPT_DIR}/lib-kind-smoke.sh"
require_docker
require_kind
require_helm
require_kubectl

if ! command -v uv >/dev/null 2>&1; then
  echo "error: uv not in PATH (required for integration tests)" >&2
  exit 1
fi
if ! command -v python3 >/dev/null 2>&1; then
  echo "error: python3 required (healthz fallback probe)" >&2
  exit 1
fi

cd "${_METRICS_ROOT}"

KIND_CLUSTER_NAME="${KIND_CLUSTER_NAME:-metrics}"
KUBE_CONTEXT="${KUBE_CONTEXT:-kind-${KIND_CLUSTER_NAME}}"
KIND_NODE_IMAGE="${KIND_NODE_IMAGE:-kindest/node:v1.30.0}"
KIND_SMOKE_CI="${KIND_SMOKE_CI:-0}"
KIND_DELETE_ON_EXIT="${KIND_DELETE_ON_EXIT:-false}"
KIND_CREATE_CLUSTER="${KIND_CREATE_CLUSTER:-true}"
KIND_SMOKE_EXIT_AFTER_TESTS="${KIND_SMOKE_EXIT_AFTER_TESTS:-0}"
KIND_IMAGE_LOAD_TIMEOUT_SECONDS="${KIND_IMAGE_LOAD_TIMEOUT_SECONDS:-}"
KIND_PRELOAD_IMAGES="${KIND_PRELOAD_IMAGES:-true}"
if [[ "${KIND_SMOKE_CI}" == "1" && -z "${KIND_IMAGE_LOAD_TIMEOUT_SECONDS}" ]]; then
  KIND_IMAGE_LOAD_TIMEOUT_SECONDS="${KIND_IMAGE_LOAD_TIMEOUT_CI_DEFAULT:-900}"
fi

NAMESPACE="${NAMESPACE:-metrics}"
PORT_FORWARD_PORT="${PORT_FORWARD_PORT:-18080}"
METRICS_SMOKE_PF_LOG="${METRICS_SMOKE_PF_LOG:-/tmp/metrics-kind-port-forward.log}"
METRICS_KIND_SMOKE_STATE_DIR="${METRICS_KIND_SMOKE_STATE_DIR:-${_METRICS_ROOT}/.kind-smoke}"

KUEUE_CHART_VERSION="${KUEUE_CHART_VERSION:-0.17.0}"
KUEUE_RELEASE_NAME="${KUEUE_RELEASE_NAME:-kueue}"
KUEUE_NAMESPACE="${KUEUE_NAMESPACE:-kueue-system}"
KUEUE_IMAGE="${KUEUE_IMAGE:-registry.k8s.io/kueue/kueue:v${KUEUE_CHART_VERSION}}"
KUEUE_SMOKE_WAIT_TIMEOUT="${KUEUE_SMOKE_WAIT_TIMEOUT:-180}"

METRICS_IMAGE_REPOSITORY="${METRICS_IMAGE_REPOSITORY:-canfar-metrics-local}"
METRICS_IMAGE_TAG="${METRICS_IMAGE_TAG:-}"
KIND_SMOKE_SKIP_BUILD="${KIND_SMOKE_SKIP_BUILD:-0}"

kubectl_args() {
  kubectl --context "${KUBE_CONTEXT}" "$@"
}

helm_kctx=(--kube-context "${KUBE_CONTEXT}")

kind_cluster_exists() {
  kind get clusters | grep -Fxq "${KIND_CLUSTER_NAME}"
}

ensure_kind_cluster() {
  if kind_cluster_exists; then
    echo "kind: cluster ${KIND_CLUSTER_NAME} already exists"
  else
    if [[ "${KIND_SMOKE_CI}" == "1" || "${KIND_CREATE_CLUSTER}" != "true" ]]; then
      echo "error: kind cluster ${KIND_CLUSTER_NAME} not found" >&2
      exit 1
    fi
    echo "kind: creating cluster ${KIND_CLUSTER_NAME}"
    kind create cluster --name "${KIND_CLUSTER_NAME}" --image "${KIND_NODE_IMAGE}" --wait 180s
  fi
  kubectl config use-context "${KUBE_CONTEXT}" >/dev/null
}

kind_image_load() {
  local _img="${1:?}"
  echo "kind load docker-image: ${_img}"
  if [[ -n "${KIND_IMAGE_LOAD_TIMEOUT_SECONDS:-}" ]] && command -v timeout >/dev/null 2>&1; then
    timeout "${KIND_IMAGE_LOAD_TIMEOUT_SECONDS}" \
      kind load docker-image "${_img}" --name "${KIND_CLUSTER_NAME}"
  else
    kind load docker-image "${_img}" --name "${KIND_CLUSTER_NAME}"
  fi
}

preload_to_kind() {
  local _img="${1:?}"
  docker pull "${_img}"
  kind_image_load "${_img}"
}

preload_to_kind_or_warn() {
  local _img="${1:?}"
  if preload_to_kind "${_img}"; then
    return 0
  fi
  echo "warning: could not preload ${_img}; continuing (node pull will be used)" >&2
  return 0
}

wait_kueue_webhook() {
  local _wh_ready=0 _wh_deadline _addr
  _wh_deadline=$((SECONDS + 300))
  while ((SECONDS < _wh_deadline)); do
    _addr="$(kubectl_args get endpoints -n "${KUEUE_NAMESPACE}" kueue-webhook-service \
      -o jsonpath='{.subsets[0].addresses[0].ip}' 2>/dev/null || true)"
    if [[ -n "${_addr}" ]]; then
      sleep 8
      _wh_ready=1
      break
    fi
    sleep 3
  done
  if [[ "${_wh_ready}" -ne 1 ]]; then
    echo "error: kueue-webhook-service has no ready endpoints" >&2
    kubectl_args get endpoints,svc,pods -n "${KUEUE_NAMESPACE}" >&2 || true
    exit 1
  fi
}

install_kueue_and_fixtures() {
  if [[ "${KIND_PRELOAD_IMAGES}" == "true" ]]; then
    echo "Pre-load Kueue image: ${KUEUE_IMAGE}"
    preload_to_kind_or_warn "${KUEUE_IMAGE}"
    preload_to_kind_or_warn "alpine:3.20"
    preload_to_kind_or_warn "redis:7-alpine"
  else
    echo "Skip optional image preloads (KIND_PRELOAD_IMAGES=false)"
  fi

  _helm_kueue() {
    helm "${helm_kctx[@]}" upgrade --install "${KUEUE_RELEASE_NAME}" oci://registry.k8s.io/kueue/charts/kueue \
      --version "${KUEUE_CHART_VERSION}" \
      --namespace "${KUEUE_NAMESPACE}" \
      --create-namespace \
      --wait --timeout 300s
  }

  echo "Helm: Kueue ${KUEUE_CHART_VERSION} -> ${KUEUE_NAMESPACE}"
  local _attempt=1 _max="${HELM_INSTALL_RETRIES:-3}"
  while true; do
    if _helm_kueue; then
      break
    fi
    if [[ "${_attempt}" -ge "${_max}" ]]; then
      echo "error: Kueue Helm install failed" >&2
      exit 1
    fi
    echo "Helm retry ${_attempt}..."
    sleep 15
    _attempt=$((_attempt + 1))
  done

  kubectl_args wait deploy/kueue-controller-manager -n "${KUEUE_NAMESPACE}" \
    --for=condition=available --timeout=5m
  echo "Wait for Kueue webhook service endpoints"
  wait_kueue_webhook

  echo "Apply scripts/test-setup.yaml"
  kubectl_args apply -f "${_SCRIPT_DIR}/test-setup.yaml"
}

build_image_if_needed() {
  local _image_ref
  if [[ -z "${METRICS_IMAGE_TAG}" ]]; then
    if [[ "${KIND_SMOKE_CI}" == "1" && -n "${GITHUB_SHA:-}" ]]; then
      METRICS_IMAGE_TAG="${GITHUB_SHA:0:12}"
    else
      METRICS_IMAGE_TAG="$(date +%Y%m%d%H%M%S)"
    fi
  fi
  _image_ref="${METRICS_IMAGE_REPOSITORY}:${METRICS_IMAGE_TAG}"

  if [[ "${KIND_SMOKE_SKIP_BUILD}" == "1" ]]; then
    docker image inspect "${_image_ref}" >/dev/null 2>&1 || {
      echo "error: KIND_SMOKE_SKIP_BUILD=1 but image missing locally: ${_image_ref}" >&2
      exit 1
    }
    echo "Skip image build (using existing image): ${_image_ref}" >&2
    echo "${_image_ref}"
    return 0
  fi

  echo "Docker build: ${_image_ref}" >&2
  docker build -t "${_image_ref}" .
  echo "${_image_ref}"
}

deploy_metrics_api() {
  local _full_image _tag
  _full_image="$(build_image_if_needed)"
  _tag="${_full_image##*:}"
  kind_image_load "${_full_image}"

  echo "Helm: metrics-api"
  helm "${helm_kctx[@]}" upgrade --install metrics-api \
    "${_METRICS_ROOT}/helm/metrics-api" \
    --namespace "${NAMESPACE}" \
    --create-namespace \
    -f "${_METRICS_ROOT}/scripts/kind-values.yaml" \
    --set "image.repository=${METRICS_IMAGE_REPOSITORY}" \
    --set "image.tag=${_tag}" \
    --wait --timeout=300s
}

wait_workload_admitted() {
  local _name="${KUEUE_SMOKE_WORKLOAD:-integration-idle}" _ns="${KUEUE_SMOKE_NAMESPACE:-metrics}"
  local _want="${KUEUE_SMOKE_CLUSTER_QUEUE:-cq-electron}" _deadline _cq
  _deadline=$((SECONDS + KUEUE_SMOKE_WAIT_TIMEOUT))
  echo "Wait Workload ${_ns}/${_name} -> ${_want} (${KUEUE_SMOKE_WAIT_TIMEOUT}s)"
  while ((SECONDS < _deadline)); do
    _cq="$(kubectl_args get "workload.kueue.x-k8s.io/${_name}" -n "${_ns}" -o jsonpath='{.status.admission.clusterQueue}' 2>/dev/null || true)"
    if [[ "${_cq}" == "${_want}" ]]; then
      echo "Workload admitted"
      return 0
    fi
    sleep 2
  done
  echo "error: Workload not admitted in time" >&2
  kubectl_args get "workload.kueue.x-k8s.io/${_name}" -n "${_ns}" -o yaml >&2 || true
  exit 1
}

smoke_stop_previous_port_forward() {
  local _state_file="${METRICS_KIND_SMOKE_STATE_DIR}/port-forward.env" _pid
  [[ -f "${_state_file}" ]] || return 0
  _pid="$(metrics_kind_smoke_read_port_forward_pid "${_state_file}" || true)"
  if [[ -n "${_pid}" ]] && kill -0 "${_pid}" 2>/dev/null; then
    echo "Stopping previous metrics port-forward (PID ${_pid})"
    metrics_kind_smoke_stop_pid "${_pid}"
  fi
  rm -f "${_state_file}"
}

_KPF=()
set_port_forward_cmd() {
  _KPF=(kubectl --context "${KUBE_CONTEXT}" -n "${NAMESPACE}" port-forward "svc/metrics-api-metrics-api" "${PORT_FORWARD_PORT}:8000")
}

start_port_forward() {
  if [[ "${_PERSIST}" -eq 1 ]]; then
    mkdir -p "${METRICS_KIND_SMOKE_STATE_DIR}"
    PORT_FORWARD_PID="$(
      PORT_FORWARD_LOG="${METRICS_SMOKE_PF_LOG}" python3 - "${_KPF[@]}" <<'PY'
import os
import subprocess
import sys

log = open(os.environ["PORT_FORWARD_LOG"], "ab", buffering=0)
proc = subprocess.Popen(
    sys.argv[1:],
    stdin=subprocess.DEVNULL,
    stdout=log,
    stderr=subprocess.STDOUT,
    start_new_session=True,
    close_fds=True,
)
print(proc.pid)
PY
    )"
  else
    "${_KPF[@]}" > "${METRICS_SMOKE_PF_LOG}" 2>&1 &
    PORT_FORWARD_PID=$!
  fi
}

PORT_FORWARD_PID=""
_SMOKE_CLEANUP_DONE=0
cleanup() {
  if [[ "${_SMOKE_CLEANUP_DONE}" -eq 1 ]]; then
    return 0
  fi
  _SMOKE_CLEANUP_DONE=1
  if [[ -n "${PORT_FORWARD_PID}" ]]; then
    kill "${PORT_FORWARD_PID}" >/dev/null 2>&1 || true
    wait "${PORT_FORWARD_PID}" 2>/dev/null || true
    PORT_FORWARD_PID=""
  fi
  if [[ "${KIND_DELETE_ON_EXIT}" == "true" ]]; then
    kind delete cluster --name "${KIND_CLUSTER_NAME}" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT
trap 'cleanup; exit 130' INT
trap 'cleanup; exit 143' TERM

ensure_kind_cluster
install_kueue_and_fixtures
deploy_metrics_api
wait_workload_admitted

kubectl_args -n "${NAMESPACE}" rollout status deploy/metrics-api-redis --timeout=300s
kubectl_args -n "${NAMESPACE}" rollout status deploy/metrics-api-metrics-api --timeout=300s

smoke_stop_previous_port_forward

_PERSIST=0
if [[ "${KIND_SMOKE_CI}" == "0" && "${KIND_SMOKE_EXIT_AFTER_TESTS}" == "0" ]]; then
  _PERSIST=1
fi

set_port_forward_cmd
start_port_forward

echo "Wait for metrics API on 127.0.0.1:${PORT_FORWARD_PORT} (port-forward log: ${METRICS_SMOKE_PF_LOG})"
_api_deadline=$((SECONDS + 120))
_api_ok=0
while ((SECONDS < _api_deadline)); do
  if command -v curl >/dev/null 2>&1; then
    if curl -fsS --max-time 2 "http://127.0.0.1:${PORT_FORWARD_PORT}/healthz" >/dev/null 2>&1; then
      _api_ok=1
      break
    fi
  else
    if python3 -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:${PORT_FORWARD_PORT}/healthz', timeout=2).read()" 2>/dev/null; then
      _api_ok=1
      break
    fi
  fi
  sleep 2
done
if [[ "${_api_ok}" -ne 1 ]]; then
  echo "error: metrics API did not respond on /healthz within 120s (port-forward PID ${PORT_FORWARD_PID})" >&2
  [[ -f "${METRICS_SMOKE_PF_LOG}" ]] && tail -n 80 "${METRICS_SMOKE_PF_LOG}" >&2 || true
  exit 1
fi

METRICS_BASE_URL="http://127.0.0.1:${PORT_FORWARD_PORT}" uv run pytest tests/integration -m integration -q
echo "OK"

if [[ "${_PERSIST}" -eq 1 ]]; then
  if ! kill -0 "${PORT_FORWARD_PID}" 2>/dev/null; then
    echo "error: persistent port-forward exited before handoff (PID ${PORT_FORWARD_PID})" >&2
    [[ -f "${METRICS_SMOKE_PF_LOG}" ]] && tail -n 80 "${METRICS_SMOKE_PF_LOG}" >&2 || true
    exit 1
  fi
  {
    echo "# kind-smoke.sh"
    echo "PORT_FORWARD_PID=${PORT_FORWARD_PID}"
    echo "PORT_FORWARD_PORT=${PORT_FORWARD_PORT}"
    echo "KUBE_CONTEXT=${KUBE_CONTEXT}"
    echo "NAMESPACE=${NAMESPACE}"
    echo "KIND_CLUSTER_NAME=${KIND_CLUSTER_NAME}"
    echo "METRICS_SMOKE_PF_LOG=${METRICS_SMOKE_PF_LOG}"
  } > "${METRICS_KIND_SMOKE_STATE_DIR}/port-forward.env"
  chmod 600 "${METRICS_KIND_SMOKE_STATE_DIR}/port-forward.env" 2>/dev/null || true
  PORT_FORWARD_PID=""
  echo "Port-forward: http://127.0.0.1:${PORT_FORWARD_PORT} (log: ${METRICS_SMOKE_PF_LOG})"
  echo "Tear down: bash scripts/kind-smoke-teardown.sh"
  exit 0
fi
