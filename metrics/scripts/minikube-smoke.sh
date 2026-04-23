#!/usr/bin/env bash
#
# Minikube integration smoke: Kueue + test-setup.yaml + skaffold build + Helm + pytest -m integration.
# Uses `skaffold build` + `helm upgrade` (not `skaffold run` / `skaffold dev`) to avoid a broken skaffold Helm plugin
# and to keep the flow deterministic and CI-aligned.
# CI (MINIKUBE_SMOKE_CI=1): `skaffold build --detect-minikube=false` uses the host Docker daemon, then `minikube image load`
# (Skaffold otherwise calls `minikube docker-env` and hits broken cluster DNS for registry pulls).
# Local: build inside Minikube via `minikube docker-env` so the image is on the node without a load step.
#
# Local: start Minikube only if the profile is not running; on success, leave a nohup port-forward
#   (state under METRICS_MINIKUBE_SMOKE_STATE_DIR) until `scripts/minikube-smoke-teardown.sh`.
# CI: MINIKUBE_SMOKE_CI=1 — no Minikube start; port-forward is torn down on exit.
#
# Key env: KUBE_CONTEXT, MINIKUBE_PROFILE, NAMESPACE, PORT_FORWARD_PORT, MINIKUBE_SMOKE_CI,
#   MINIKUBE_SMOKE_EXIT_AFTER_TESTS, HELM_INSTALL_RETRIES, KUEUE_*, etc.
#
set -euo pipefail

_SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
_METRICS_ROOT="$(cd "${_SCRIPT_DIR}/.." && pwd)"
if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  cat <<'EOF'
Usage: bash scripts/minikube-smoke.sh

  Full Kueue + metrics API smoke on Minikube, then integration tests.
  For options and environment, see the header comment in this file.

  After a successful local run, stop the background API port-forward with:
    bash scripts/minikube-smoke-teardown.sh

  CI: set MINIKUBE_SMOKE_CI=1 (no Minikube start; cleans up the port-forward on exit).
EOF
  exit 0
fi
# shellcheck disable=SC1091
source "${_SCRIPT_DIR}/check-prerequisites.sh"
# shellcheck disable=SC1091
source "${_SCRIPT_DIR}/lib-minikube-smoke.sh"
require_helm
require_kubectl
cd "${_METRICS_ROOT}"
# Stale minikube docker-env in the parent shell breaks host `docker pull` / `docker build`.
unset DOCKER_HOST DOCKER_TLS_VERIFY DOCKER_CERT_PATH 2>/dev/null || true

MINIKUBE_PROFILE="${MINIKUBE_PROFILE:-minikube}"
KUBE_CONTEXT="${KUBE_CONTEXT:-${MINIKUBE_PROFILE}}"
export KUBE_CONTEXT
export MINIKUBE_PROFILE
MINIKUBE_SMOKE_CI="${MINIKUBE_SMOKE_CI:-0}"
KCTX="${KUBE_CONTEXT}"
NAMESPACE="${NAMESPACE:-metrics}"
PORT_FORWARD_PORT="${PORT_FORWARD_PORT:-18080}"
METRICS_SMOKE_PF_LOG="${METRICS_SMOKE_PF_LOG:-/tmp/metrics-port-forward.log}"
METRICS_MINIKUBE_SMOKE_STATE_DIR="${METRICS_MINIKUBE_SMOKE_STATE_DIR:-${_METRICS_ROOT}/.minikube-smoke}"
MINIKUBE_DRIVER="${MINIKUBE_DRIVER:-docker}"
MINIKUBE_ENABLE_METRICS_SERVER="${MINIKUBE_ENABLE_METRICS_SERVER:-true}"
MINIKUBE_DELETE_ON_EXIT="${MINIKUBE_DELETE_ON_EXIT:-false}"
# Local: 0 = leave port-forward running (see minikube-smoke-teardown.sh). 1 = kill on script exit.
MINIKUBE_SMOKE_EXIT_AFTER_TESTS="${MINIKUBE_SMOKE_EXIT_AFTER_TESTS:-0}"
# When set (seconds) and `timeout` is on PATH, cap `minikube image load` so transfers cannot hang forever (typical in CI).
MINIKUBE_IMAGE_LOAD_TIMEOUT_SECONDS="${MINIKUBE_IMAGE_LOAD_TIMEOUT_SECONDS:-}"
if [[ "${MINIKUBE_SMOKE_CI}" == "1" && -z "${MINIKUBE_IMAGE_LOAD_TIMEOUT_SECONDS}" ]]; then
  MINIKUBE_IMAGE_LOAD_TIMEOUT_SECONDS="${MINIKUBE_IMAGE_LOAD_TIMEOUT_CI_DEFAULT:-900}"
fi
KUEUE_CHART_VERSION="${KUEUE_CHART_VERSION:-0.17.0}"
KUEUE_RELEASE_NAME="${KUEUE_RELEASE_NAME:-kueue}"
KUEUE_NAMESPACE="${KUEUE_NAMESPACE:-kueue-system}"
KUEUE_IMAGE="${KUEUE_IMAGE:-registry.k8s.io/kueue/kueue:v${KUEUE_CHART_VERSION}}"
KUEUE_SMOKE_WAIT_TIMEOUT="${KUEUE_SMOKE_WAIT_TIMEOUT:-180}"

if ! command -v skaffold >/dev/null 2>&1; then
  echo "error: skaffold not in PATH (see https://skaffold.dev/docs/install/)" >&2
  exit 1
fi
if ! command -v python3 >/dev/null 2>&1; then
  echo "error: python3 required (parses skaffold --file-output)" >&2
  exit 1
fi

kubectl_args() {
  if [[ -n "${KUBE_CONTEXT}" ]]; then
    kubectl --context "${KUBE_CONTEXT}" "$@"
  else
    kubectl "$@"
  fi
}

helm_kctx=()
if [[ -n "${KUBE_CONTEXT}" ]]; then
  helm_kctx=(--kube-context "${KUBE_CONTEXT}")
fi

# Load an image from the host Docker daemon into the Minikube node (can be slow; optional timeout in CI).
_minikube_image_load() {
  local _img="${1:?}"
  echo "minikube image load: ${_img}"
  if [[ -n "${MINIKUBE_IMAGE_LOAD_TIMEOUT_SECONDS:-}" ]] && command -v timeout >/dev/null 2>&1; then
    timeout "${MINIKUBE_IMAGE_LOAD_TIMEOUT_SECONDS}" \
      minikube image load "${_img}" -p "${MINIKUBE_PROFILE}" --overwrite
  else
    minikube image load "${_img}" -p "${MINIKUBE_PROFILE}" --overwrite
  fi
}

# docker pull + minikube image load (alpine, redis, Kueue image).
_preload_to_minikube() {
  require_docker
  local _img="${1:?}"
  docker pull "${_img}"
  _minikube_image_load "${_img}"
}

# kubectl … port-forward … (no shell arrays exported to child except via "${_KPF[@]}").
_kpf_set() {
  if [[ -n "${KUBE_CONTEXT}" ]]; then
    _KPF=(kubectl --context "${KUBE_CONTEXT}" -n "${NAMESPACE}" port-forward "svc/metrics-api-metrics-api" "${PORT_FORWARD_PORT}:8000")
  else
    _KPF=(kubectl -n "${NAMESPACE}" port-forward "svc/metrics-api-metrics-api" "${PORT_FORWARD_PORT}:8000")
  fi
}

# Re-run safety: free port / PID from a prior local run.
smoke_stop_previous_port_forward() {
  local _f="${METRICS_MINIKUBE_SMOKE_STATE_DIR}/port-forward.env" _p
  [[ -f "${_f}" ]] || return 0
  _p="$(metrics_smoke_read_port_forward_pid "${_f}" || true)"
  if [[ -n "${_p}" ]] && kill -0 "${_p}" 2>/dev/null; then
    echo "Stopping previous metrics port-forward (PID ${_p})"
    metrics_smoke_stop_pid "${_p}"
  fi
  rm -f "${_f}"
}

install_kueue_and_fixtures() {
  if command -v minikube >/dev/null 2>&1 && minikube status -p "${MINIKUBE_PROFILE}" >/dev/null 2>&1; then
    echo "Pre-load Kueue image: ${KUEUE_IMAGE}"
    _preload_to_minikube "${KUEUE_IMAGE}"
  fi

  _helm_kueue() {
    helm "${helm_kctx[@]}" upgrade --install "${KUEUE_RELEASE_NAME}" oci://registry.k8s.io/kueue/charts/kueue \
      --version "${KUEUE_CHART_VERSION}" \
      --namespace "${KUEUE_NAMESPACE}" \
      --create-namespace \
      --wait --timeout 300s
  }
  echo "Helm: Kueue ${KUEUE_CHART_VERSION} → ${KUEUE_NAMESPACE}"
  _m="${HELM_INSTALL_RETRIES:-3}"
  _a=1
  while true; do
    if _helm_kueue; then
      break
    fi
    if [[ "${_a}" -ge "${_m}" ]]; then
      echo "error: Kueue Helm install failed" >&2
      exit 1
    fi
    echo "Helm retry ${_a}…"
    sleep 15
    _a=$((_a + 1))
  done

  kubectl_args wait deploy/kueue-controller-manager -n "${KUEUE_NAMESPACE}" \
    --for=condition=available --timeout=5m

  # The Deployment can be Available before webhooks listen (mutating/validating admission).
  echo "Wait for Kueue webhook service endpoints"
  _wh_ready=0
  _wh_deadline=$((SECONDS + 300))
  while ((SECONDS < _wh_deadline)); do
    _addr="$(kubectl_args get endpoints -n "${KUEUE_NAMESPACE}" kueue-webhook-service \
      -o jsonpath='{.subsets[0].addresses[0].ip}' 2>/dev/null || true)"
    if [[ -n "${_addr}" ]]; then
      echo "Kueue webhook ready (${_addr})"
      sleep 8
      _wh_ready=1
      break
    fi
    sleep 3
  done
  if [[ "${_wh_ready}" -ne 1 ]]; then
    echo "error: kueue-webhook-service has no ready endpoints (webhook not up)" >&2
    kubectl_args get endpoints,svc,pods -n "${KUEUE_NAMESPACE}" >&2 || true
    exit 1
  fi

  echo "Apply scripts/test-setup.yaml"
  kubectl_args apply -f "${_SCRIPT_DIR}/test-setup.yaml"
}

# Build in Minikube docker (see eval); deploy with plain Helm to avoid skaffold's Helm plugin.
build_and_deploy_app() {
  local _art _tag _full_tag
  _art="$(mktemp "${TMPDIR:-/tmp}/minikube-smoke-art.XXXXXX.json")"
  if [[ "${MINIKUBE_SMOKE_CI}" == "1" ]]; then
    # Skaffold auto-enables Minikube's Docker when the kubectl context is minikube; that daemon often
    # cannot resolve Docker Hub / PyPI. --detect-minikube=false keeps builds on the host daemon (CI runner).
    skaffold build -p minikube --detect-minikube=false --file-output="${_art}"
    _full_tag="$(
      python3 -c "import json,sys; print(json.load(open(sys.argv[1]))['builds'][0]['tag'])" "${_art}"
    )"
    _minikube_image_load "${_full_tag}"
  else
    (
      eval "$(minikube -p "${MINIKUBE_PROFILE}" docker-env)"
      skaffold build -p minikube --file-output="${_art}"
    )
  fi
  _tag="$(
    python3 -c "import json,sys; t=json.load(open(sys.argv[1]))['builds'][0]['tag']; print(t.split(':',1)[1])" "${_art}"
  )"
  rm -f "${_art}"
  echo "Helm: metrics-api (tag from skaffold build)"
  helm "${helm_kctx[@]}" upgrade --install metrics-api \
    "${_METRICS_ROOT}/helm/metrics-api" \
    --namespace "${NAMESPACE}" \
    --create-namespace \
    -f "${_METRICS_ROOT}/scripts/minikube-values.yaml" \
    --set "image.repository=canfar-metrics-local" \
    --set "image.tag=${_tag}" \
    --wait --timeout=300s
}

wait_workload_admitted() {
  local _name="${KUEUE_SMOKE_WORKLOAD:-integration-idle}" _ns="${KUEUE_SMOKE_NAMESPACE:-metrics}"
  local _want="${KUEUE_SMOKE_CLUSTER_QUEUE:-cq-proton}" _deadline=$((SECONDS + KUEUE_SMOKE_WAIT_TIMEOUT))
  echo "Wait Workload ${_ns}/${_name} → ${_want} (${KUEUE_SMOKE_WAIT_TIMEOUT}s)"
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
  if [[ "${MINIKUBE_SMOKE_CI}" == "0" && "${MINIKUBE_DELETE_ON_EXIT}" == "true" ]]; then
    kubectl --context "${KCTX}" delete namespace "${NAMESPACE}" --wait=false >/dev/null 2>&1 || true
    minikube delete -p "${MINIKUBE_PROFILE}" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT
trap 'cleanup; exit 130' INT
trap 'cleanup; exit 143' TERM

if [[ "${MINIKUBE_SMOKE_CI}" == "0" ]]; then
  require_minikube
  require_docker
  if minikube status -p "${MINIKUBE_PROFILE}" >/dev/null 2>&1; then
    echo "Minikube: profile ${MINIKUBE_PROFILE} is already running — skipping cluster start and image preloads"
    kubectl config use-context "${KCTX}" >/dev/null
    if [[ "${MINIKUBE_ENABLE_METRICS_SERVER}" == "true" ]]; then
      minikube addons enable metrics-server -p "${MINIKUBE_PROFILE}" 2>/dev/null || true
    fi
  else
    echo "Minikube: start profile=${MINIKUBE_PROFILE}"
    minikube start -p "${MINIKUBE_PROFILE}" --driver="${MINIKUBE_DRIVER}" --wait=all
    kubectl config use-context "${KCTX}" >/dev/null
    _preload_to_minikube "alpine:3.20"
    _preload_to_minikube "redis:7-alpine"
    if [[ "${MINIKUBE_ENABLE_METRICS_SERVER}" == "true" ]]; then
      minikube addons enable metrics-server -p "${MINIKUBE_PROFILE}"
    fi
  fi
else
  if command -v minikube >/dev/null 2>&1 && ! minikube status -p "${MINIKUBE_PROFILE}" >/dev/null 2>&1; then
    echo "error: MINIKUBE_SMOKE_CI=1 but minikube not running" >&2
    exit 1
  fi
  kubectl config use-context "${KCTX}" >/dev/null 2>&1 || true
fi

install_kueue_and_fixtures

build_and_deploy_app

wait_workload_admitted

kubectl_args -n "${NAMESPACE}" rollout status deploy/metrics-api-redis --timeout=300s
kubectl_args -n "${NAMESPACE}" rollout status deploy/metrics-api-metrics-api --timeout=300s

# Replace any prior port-forward on this state path / port (local iterative runs).
smoke_stop_previous_port_forward

_PERSIST=0
if [[ "${MINIKUBE_SMOKE_CI}" == "0" && "${MINIKUBE_SMOKE_EXIT_AFTER_TESTS}" == "0" ]]; then
  _PERSIST=1
fi

_kpf_set
if [[ "${_PERSIST}" -eq 1 ]]; then
  mkdir -p "${METRICS_MINIKUBE_SMOKE_STATE_DIR}"
  nohup "${_KPF[@]}" > "${METRICS_SMOKE_PF_LOG}" 2>&1 &
else
  "${_KPF[@]}" > "${METRICS_SMOKE_PF_LOG}" 2>&1 &
fi
PORT_FORWARD_PID=$!
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
  {
    echo "# minikube-smoke.sh"
    echo "PORT_FORWARD_PID=${PORT_FORWARD_PID}"
    echo "PORT_FORWARD_PORT=${PORT_FORWARD_PORT}"
    echo "KUBE_CONTEXT=${KCTX}"
    echo "NAMESPACE=${NAMESPACE}"
    echo "MINIKUBE_PROFILE=${MINIKUBE_PROFILE}"
    echo "METRICS_SMOKE_PF_LOG=${METRICS_SMOKE_PF_LOG}"
  } > "${METRICS_MINIKUBE_SMOKE_STATE_DIR}/port-forward.env"
  chmod 600 "${METRICS_MINIKUBE_SMOKE_STATE_DIR}/port-forward.env" 2>/dev/null || true
  disown -h "$PORT_FORWARD_PID" 2>/dev/null || disown "$PORT_FORWARD_PID" 2>/dev/null || true
  PORT_FORWARD_PID=""
  echo "Port-forward: http://127.0.0.1:${PORT_FORWARD_PORT} (log: ${METRICS_SMOKE_PF_LOG})"
  echo "Tear down: bash scripts/minikube-smoke-teardown.sh"
  exit 0
fi
