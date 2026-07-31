#!/usr/bin/env bash
#
# Kind integration smoke: Kueue + test-setup.yaml + docker build + Helm + pytest -m integration.
# Local: creates a one-node kind cluster when missing. CI (KIND_SMOKE_CI=1): cluster must exist.
#
# Key env:
#   KIND_CLUSTER_NAME, KUBE_CONTEXT, NAMESPACE, PORT_FORWARD_PORT, KIND_SMOKE_CI,
#   KIND_IMAGE_LOAD_TIMEOUT_SECONDS, KIND_PRELOAD_IMAGES, KUEUE_CHART_VERSION,
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
  CI: set KIND_SMOKE_CI=1 (cluster must already exist).

  To poke the API manually afterwards:
    kubectl -n metrics port-forward svc/metrics-api-metrics-api 18080:8000
EOF
  exit 0
fi

# shellcheck disable=SC1091
source "${_SCRIPT_DIR}/check-prerequisites.sh"
require docker kind helm kubectl uv curl

cd "${_METRICS_ROOT}"

KIND_CLUSTER_NAME="${KIND_CLUSTER_NAME:-metrics}"
KUBE_CONTEXT="${KUBE_CONTEXT:-kind-${KIND_CLUSTER_NAME}}"
KIND_SMOKE_CI="${KIND_SMOKE_CI:-0}"
KIND_IMAGE_LOAD_TIMEOUT_SECONDS="${KIND_IMAGE_LOAD_TIMEOUT_SECONDS:-}"
KIND_PRELOAD_IMAGES="${KIND_PRELOAD_IMAGES:-true}"
if [[ "${KIND_SMOKE_CI}" == "1" && -z "${KIND_IMAGE_LOAD_TIMEOUT_SECONDS}" ]]; then
  KIND_IMAGE_LOAD_TIMEOUT_SECONDS=900
fi

NAMESPACE="${NAMESPACE:-metrics}"
PORT_FORWARD_PORT="${PORT_FORWARD_PORT:-18080}"

KUEUE_CHART_VERSION="${KUEUE_CHART_VERSION:-0.17.0}"
KUEUE_RELEASE_NAME="${KUEUE_RELEASE_NAME:-kueue}"
KUEUE_NAMESPACE="${KUEUE_NAMESPACE:-kueue-system}"

METRICS_IMAGE_REPOSITORY="${METRICS_IMAGE_REPOSITORY:-canfar-metrics-local}"
METRICS_IMAGE_TAG="${METRICS_IMAGE_TAG:-}"
KIND_SMOKE_SKIP_BUILD="${KIND_SMOKE_SKIP_BUILD:-0}"

kubectl_args() {
  kubectl --context "${KUBE_CONTEXT}" "$@"
}

helm_kctx=(--kube-context "${KUBE_CONTEXT}")

ensure_kind_cluster() {
  if kind get clusters | grep -Fxq "${KIND_CLUSTER_NAME}"; then
    echo "kind: cluster ${KIND_CLUSTER_NAME} already exists"
  elif [[ "${KIND_SMOKE_CI}" == "1" ]]; then
    echo "error: kind cluster ${KIND_CLUSTER_NAME} not found" >&2
    exit 1
  else
    echo "kind: creating cluster ${KIND_CLUSTER_NAME}"
    kind create cluster --name "${KIND_CLUSTER_NAME}" --image kindest/node:v1.30.0 --wait 180s
  fi
  kubectl config use-context "${KUBE_CONTEXT}" >/dev/null
}

kind_image_load() {
  local _img="${1:?}"
  echo "kind load docker-image: ${_img}"
  if [[ -n "${KIND_IMAGE_LOAD_TIMEOUT_SECONDS}" ]] && command -v timeout >/dev/null 2>&1; then
    timeout "${KIND_IMAGE_LOAD_TIMEOUT_SECONDS}" \
      kind load docker-image "${_img}" --name "${KIND_CLUSTER_NAME}"
  else
    kind load docker-image "${_img}" --name "${KIND_CLUSTER_NAME}"
  fi
}

preload_to_kind() {
  local _img="${1:?}"
  { docker pull "${_img}" && kind_image_load "${_img}"; } ||
    echo "warning: could not preload ${_img}; continuing (node pull will be used)" >&2
}

install_kueue_and_fixtures() {
  if [[ "${KIND_PRELOAD_IMAGES}" == "true" ]]; then
    preload_to_kind "registry.k8s.io/kueue/kueue:v${KUEUE_CHART_VERSION}"
    preload_to_kind "alpine:3.20"
    preload_to_kind "redis:7-alpine"
  fi

  echo "Helm: Kueue ${KUEUE_CHART_VERSION} -> ${KUEUE_NAMESPACE}"
  helm "${helm_kctx[@]}" upgrade --install "${KUEUE_RELEASE_NAME}" \
    oci://registry.k8s.io/kueue/charts/kueue \
    --version "${KUEUE_CHART_VERSION}" \
    --namespace "${KUEUE_NAMESPACE}" \
    --create-namespace \
    --atomic --timeout 600s

  kubectl_args wait deploy/kueue-controller-manager -n "${KUEUE_NAMESPACE}" \
    --for=condition=available --timeout=5m
  echo "Wait for Kueue webhook service endpoints"
  local _deadline=$((SECONDS + 300))
  until kubectl_args get endpoints -n "${KUEUE_NAMESPACE}" kueue-webhook-service \
    -o jsonpath='{.subsets[0].addresses[0].ip}' 2>/dev/null | grep -q .; do
    ((SECONDS < _deadline)) || {
      echo "error: kueue-webhook-service has no ready endpoints" >&2
      exit 1
    }
    sleep 3
  done
  sleep 8 # webhook TLS needs a beat after endpoints appear

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
  echo "Wait Workload ${NAMESPACE}/integration-idle -> cq-electron (180s)"
  local _deadline=$((SECONDS + 180))
  until [[ "$(kubectl_args get workload.kueue.x-k8s.io/integration-idle -n "${NAMESPACE}" \
    -o jsonpath='{.status.admission.clusterQueue}' 2>/dev/null)" == "cq-electron" ]]; do
    ((SECONDS < _deadline)) || {
      echo "error: Workload not admitted in time" >&2
      kubectl_args get workload.kueue.x-k8s.io/integration-idle -n "${NAMESPACE}" -o yaml >&2 || true
      exit 1
    }
    sleep 2
  done
  echo "Workload admitted"
}

PORT_FORWARD_PID=""
cleanup() {
  if [[ -n "${PORT_FORWARD_PID}" ]]; then
    kill "${PORT_FORWARD_PID}" >/dev/null 2>&1 || true
    wait "${PORT_FORWARD_PID}" 2>/dev/null || true
    PORT_FORWARD_PID=""
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

kubectl_args -n "${NAMESPACE}" port-forward "svc/metrics-api-metrics-api" \
  "${PORT_FORWARD_PORT}:8000" >/dev/null 2>&1 &
PORT_FORWARD_PID=$!

echo "Wait for metrics API on 127.0.0.1:${PORT_FORWARD_PORT}"
_deadline=$((SECONDS + 120))
until curl -fsS --max-time 2 "http://127.0.0.1:${PORT_FORWARD_PORT}/healthz" >/dev/null 2>&1; do
  ((SECONDS < _deadline)) || {
    echo "error: metrics API did not respond on /healthz within 120s" >&2
    exit 1
  }
  sleep 2
done

METRICS_BASE_URL="http://127.0.0.1:${PORT_FORWARD_PORT}" uv run pytest tests/integration -m integration -q
echo "OK"
echo "To poke the API: kubectl -n ${NAMESPACE} port-forward svc/metrics-api-metrics-api ${PORT_FORWARD_PORT}:8000"
