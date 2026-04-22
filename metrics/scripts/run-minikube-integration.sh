#!/usr/bin/env bash
#
# Local integration loop using Minikube (same stack as CI). Minikube supports
# addons such as metrics-server, which we rely on for upcoming kube-metrics work.
#
# Environment:
#   MINIKUBE_PROFILE          — Minikube profile (default: metrics-local). Kept
#                               separate from the default "minikube" profile so
#                               trap cleanup can delete this profile without
#                               wiping the developer's usual local cluster.
#   MINIKUBE_DRIVER           — vm driver (default: docker)
#   MINIKUBE_ENABLE_METRICS_SERVER — "true"|"false" (default: true)
#

set -euo pipefail

_SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${_SCRIPT_DIR}/check-prerequisites.sh"
require_docker
require_minikube
require_helm
require_kubectl

METRICS_ROOT="$(cd "${_SCRIPT_DIR}/.." && pwd)"
cd "${METRICS_ROOT}"

MINIKUBE_PROFILE="${MINIKUBE_PROFILE:-metrics-local}"
NAMESPACE="${NAMESPACE:-metrics}"
RELEASE_NAME="${RELEASE_NAME:-metrics-api}"
LOCAL_IMAGE="${LOCAL_IMAGE:-canfar-metrics-local:latest}"
VALUES_FILE="${VALUES_FILE:-scripts/minikube-values.yaml}"
CHART_PATH="${CHART_PATH:-helm/metrics-api}"
PORT_FORWARD_PORT="${PORT_FORWARD_PORT:-18080}"
MINIKUBE_DRIVER="${MINIKUBE_DRIVER:-docker}"
MINIKUBE_ENABLE_METRICS_SERVER="${MINIKUBE_ENABLE_METRICS_SERVER:-true}"

KCTX="${MINIKUBE_PROFILE}"

cleanup() {
  if [[ -n "${PORT_FORWARD_PID:-}" ]]; then
    kill "${PORT_FORWARD_PID}" >/dev/null 2>&1 || true
  fi
  kubectl --context "${KCTX}" delete namespace "${NAMESPACE}" --wait=false >/dev/null 2>&1 || true
  minikube delete -p "${MINIKUBE_PROFILE}" >/dev/null 2>&1 || true
}

trap cleanup EXIT

echo "Starting Minikube (profile=${MINIKUBE_PROFILE}, driver=${MINIKUBE_DRIVER})"
minikube start -p "${MINIKUBE_PROFILE}" --driver="${MINIKUBE_DRIVER}" --wait=all

if [[ "${MINIKUBE_ENABLE_METRICS_SERVER}" == "true" ]]; then
  echo "Enabling metrics-server addon (resource metrics API; aligns with upcoming milestones)"
  minikube addons enable metrics-server -p "${MINIKUBE_PROFILE}"
fi

echo "Installing Kueue and M2 test fixtures (ClusterQueues + Cohort)"
bash "${_SCRIPT_DIR}/install-kueue-minikube.sh"
kubectl --context "${KCTX}" apply -f "${METRICS_ROOT}/tests/fixtures/kueue/"

echo "Building local metrics image: ${LOCAL_IMAGE}"
docker build -t "${LOCAL_IMAGE}" .

echo "Loading image into Minikube"
minikube image load "${LOCAL_IMAGE}" -p "${MINIKUBE_PROFILE}"

echo "Deploying chart"
helm upgrade --install "${RELEASE_NAME}" "${CHART_PATH}" \
  --kube-context "${KCTX}" \
  --namespace "${NAMESPACE}" \
  --create-namespace \
  -f "${VALUES_FILE}" \
  --set "image.repository=${LOCAL_IMAGE%:*}" \
  --set "image.tag=${LOCAL_IMAGE##*:}"

kubectl --context "${KCTX}" -n "${NAMESPACE}" \
  rollout status deploy/"${RELEASE_NAME}-metrics-api" --timeout=180s

kubectl --context "${KCTX}" -n "${NAMESPACE}" port-forward \
  svc/"${RELEASE_NAME}-metrics-api" "${PORT_FORWARD_PORT}":8000 >/tmp/metrics-port-forward.log 2>&1 &
PORT_FORWARD_PID=$!

sleep 3
METRICS_BASE_URL="http://127.0.0.1:${PORT_FORWARD_PORT}" \
  uv run pytest tests/integration -m integration -q

echo "Integration tests succeeded"
