#!/usr/bin/env bash
#
# Tear down the background metrics API port-forward left by minikube-smoke.sh (local).
# Optional: full Kubernetes dev stack (see teardown-dev-kube-setup.sh) and/or the Minikube profile.
#
# State: METRICS_MINIKUBE_SMOKE_STATE_DIR (default: metrics/.minikube-smoke/) + port-forward.env
#
# Environment: KUBE_CONTEXT, MINIKUBE_PROFILE, NAMESPACE, METRICS_MINIKUBE_SMOKE_STATE_DIR
#
# Examples:
#   bash scripts/minikube-smoke-teardown.sh
#   bash scripts/minikube-smoke-teardown.sh --all
#   bash scripts/minikube-smoke-teardown.sh --all --minikube
#
set -euo pipefail

_SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
_METRICS_ROOT="$(cd "${_SCRIPT_DIR}/.." && pwd)"
# shellcheck disable=SC1091
source "${_SCRIPT_DIR}/check-prerequisites.sh"
# shellcheck disable=SC1091
source "${_SCRIPT_DIR}/lib-minikube-smoke.sh"
require_kubectl

METRICS_MINIKUBE_SMOKE_STATE_DIR="${METRICS_MINIKUBE_SMOKE_STATE_DIR:-${_METRICS_ROOT}/.minikube-smoke}"
MINIKUBE_PROFILE="${MINIKUBE_PROFILE:-minikube}"
KUBE_CONTEXT="${KUBE_CONTEXT:-minikube}"
NAMESPACE="${NAMESPACE:-metrics}"
PF_STATE="${METRICS_MINIKUBE_SMOKE_STATE_DIR}/port-forward.env"
DO_ALL=0
DO_MINIKUBE=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --all) DO_ALL=1 ;;
    --minikube) DO_MINIKUBE=1 ;;
    -h | --help)
      cat <<'EOF'
Usage: bash scripts/minikube-smoke-teardown.sh [options]

  Stops the metrics API kubectl port-forward (non-destructive: Kueue and the cluster stay).
  State: METRICS_MINIKUBE_SMOKE_STATE_DIR (default: metrics/.minikube-smoke/) + port-forward.env

  --all         Also run scripts/teardown-dev-kube-setup.sh (Helm, fixtures, Kueue).
  --minikube    After the above, run minikube delete for MINIKUBE_PROFILE (destructive).

Environment: KUBE_CONTEXT, NAMESPACE, MINIKUBE_PROFILE, METRICS_MINIKUBE_SMOKE_STATE_DIR
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
  local _p
  _p="$(metrics_smoke_read_port_forward_pid "${PF_STATE}" || true)"
  if [[ -n "${_p}" ]] && kill -0 "${_p}" 2>/dev/null; then
    echo "Stopping port-forward (PID ${_p})"
    metrics_smoke_stop_pid "${_p}"
  else
    echo "Port-forward not running (stale PID in state; clearing)"
  fi
  rm -f "${PF_STATE}"
  echo "Port-forward state cleared."
}

echo "minikube-smoke-teardown: state dir=${METRICS_MINIKUBE_SMOKE_STATE_DIR}"
stop_port_forward

if [[ "${DO_ALL}" -eq 1 ]]; then
  echo "Running scripts/teardown-dev-kube-setup.sh (KUBE_CONTEXT=${KUBE_CONTEXT})"
  KUBE_CONTEXT="${KUBE_CONTEXT}" NAMESPACE="${NAMESPACE}" \
    bash "${_SCRIPT_DIR}/teardown-dev-kube-setup.sh"
fi

if [[ "${DO_MINIKUBE}" -eq 1 ]]; then
  if ! command -v minikube >/dev/null 2>&1; then
    echo "error: minikube not in PATH (cannot use --minikube)" >&2
    exit 1
  fi
  echo "minikube delete -p ${MINIKUBE_PROFILE}"
  minikube delete -p "${MINIKUBE_PROFILE}"
fi

echo "Done."
