#!/usr/bin/env bash

set -euo pipefail

_SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${_SCRIPT_DIR}/check-prerequisites.sh"
require_helm
require_kubectl

METRICS_ROOT="$(cd "${_SCRIPT_DIR}/.." && pwd)"
cd "${METRICS_ROOT}"

ENVIRONMENT="${1:-dev}"
NAMESPACE="${NAMESPACE:-metrics}"
RELEASE_NAME="${RELEASE_NAME:-metrics-api}"
CHART_PATH="${CHART_PATH:-helm/metrics-api}"
VALUES_FILE="${VALUES_FILE:-${CHART_PATH}/values-${ENVIRONMENT}.yaml}"

HELM_CTX=()
KUBECTL_CTX=()
if [[ -n "${KUBE_CONTEXT:-}" ]]; then
  HELM_CTX=(--kube-context "${KUBE_CONTEXT}")
  KUBECTL_CTX=(--context "${KUBE_CONTEXT}")
fi

if [[ ! -f "${VALUES_FILE}" ]]; then
  echo "Values file not found: ${VALUES_FILE}" >&2
  exit 1
fi

_ctx="$(kubectl "${KUBECTL_CTX[@]}" config current-context 2>/dev/null || echo '(unset)')"
echo "Deploying from $(pwd); kubectl context: ${_ctx}"

helm upgrade --install "${RELEASE_NAME}" "${CHART_PATH}" \
  "${HELM_CTX[@]}" \
  --namespace "${NAMESPACE}" \
  --create-namespace \
  -f "${VALUES_FILE}"

kubectl "${KUBECTL_CTX[@]}" -n "${NAMESPACE}" rollout status deployment/"${RELEASE_NAME}-metrics-api" --timeout=180s

echo "Deployment complete for environment: ${ENVIRONMENT}"
