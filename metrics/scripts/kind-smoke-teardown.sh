#!/usr/bin/env bash
#
# Tear down the background metrics API port-forward left by kind-smoke.sh (local).
# Optional: full Kubernetes dev stack (see teardown-dev-kube-setup.sh) and/or the kind cluster.
#
# State: METRICS_KIND_SMOKE_STATE_DIR (default: metrics/.kind-smoke/) + port-forward.env
#
# Environment: KUBE_CONTEXT, KIND_CLUSTER_NAME, NAMESPACE, METRICS_KIND_SMOKE_STATE_DIR
#
# Examples:
#   bash scripts/kind-smoke-teardown.sh
#   bash scripts/kind-smoke-teardown.sh --all
#   bash scripts/kind-smoke-teardown.sh --all --kind
#
set -euo pipefail

_SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
_METRICS_ROOT="$(cd "${_SCRIPT_DIR}/.." && pwd)"
# shellcheck disable=SC1091
source "${_SCRIPT_DIR}/check-prerequisites.sh"
# shellcheck disable=SC1091
source "${_SCRIPT_DIR}/lib-kind-smoke.sh"
require_kubectl

METRICS_KIND_SMOKE_STATE_DIR="${METRICS_KIND_SMOKE_STATE_DIR:-${_METRICS_ROOT}/.kind-smoke}"
KIND_CLUSTER_NAME="${KIND_CLUSTER_NAME:-metrics}"
KUBE_CONTEXT="${KUBE_CONTEXT:-kind-${KIND_CLUSTER_NAME}}"
NAMESPACE="${NAMESPACE:-metrics}"
PF_STATE="${METRICS_KIND_SMOKE_STATE_DIR}/port-forward.env"
DO_ALL=0
DO_KIND=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --all) DO_ALL=1 ;;
    --kind) DO_KIND=1 ;;
    -h | --help)
      cat <<'EOF'
Usage: bash scripts/kind-smoke-teardown.sh [options]

  Stops the metrics API kubectl port-forward (non-destructive: Kueue and cluster stay).
  State: METRICS_KIND_SMOKE_STATE_DIR (default: metrics/.kind-smoke/) + port-forward.env

  --all    Also run scripts/teardown-dev-kube-setup.sh (Helm, fixtures, Kueue).
  --kind   After the above, delete the kind cluster KIND_CLUSTER_NAME.

Environment: KUBE_CONTEXT, NAMESPACE, KIND_CLUSTER_NAME, METRICS_KIND_SMOKE_STATE_DIR
EOF
      exit 0
      ;;
    *)
      echo "unknown option: $1 (try --help)" >&2
      exit 1
      ;;
  esac
  shift
done

stop_port_forward() {
  if [[ ! -f "${PF_STATE}" ]]; then
    echo "No port-forward state at ${PF_STATE} (already torn down or never started)"
    return 0
  fi
  local _pid
  _pid="$(metrics_kind_smoke_read_port_forward_pid "${PF_STATE}" || true)"
  if [[ -n "${_pid}" ]] && kill -0 "${_pid}" 2>/dev/null; then
    echo "Stopping port-forward (PID ${_pid})"
    metrics_kind_smoke_stop_pid "${_pid}"
  else
    echo "Port-forward not running (stale PID in state; clearing)"
  fi
  rm -f "${PF_STATE}"
  echo "Port-forward state cleared."
}

echo "kind-smoke-teardown: state dir=${METRICS_KIND_SMOKE_STATE_DIR}"
stop_port_forward

if [[ "${DO_ALL}" -eq 1 ]]; then
  echo "Running scripts/teardown-dev-kube-setup.sh (KUBE_CONTEXT=${KUBE_CONTEXT})"
  KUBE_CONTEXT="${KUBE_CONTEXT}" NAMESPACE="${NAMESPACE}" \
    bash "${_SCRIPT_DIR}/teardown-dev-kube-setup.sh"
fi

if [[ "${DO_KIND}" -eq 1 ]]; then
  require_kind
  echo "kind delete cluster --name ${KIND_CLUSTER_NAME}"
  kind delete cluster --name "${KIND_CLUSTER_NAME}"
fi

echo "Done."
