#!/usr/bin/env bash

set -euo pipefail

ENVIRONMENT="${1:-dev}"
NAMESPACE="${NAMESPACE:-metrics}"
RELEASE_NAME="${RELEASE_NAME:-metrics-api}"
CHART_PATH="${CHART_PATH:-../helm/metrics-api}"
VALUES_FILE="${VALUES_FILE:-${CHART_PATH}/values-${ENVIRONMENT}.yaml}"

if [[ ! -f "${VALUES_FILE}" ]]; then
  echo "Values file not found: ${VALUES_FILE}" >&2
  exit 1
fi

helm upgrade --install "${RELEASE_NAME}" "${CHART_PATH}" \
  --namespace "${NAMESPACE}" \
  --create-namespace \
  -f "${VALUES_FILE}"

kubectl -n "${NAMESPACE}" rollout status deployment/"${RELEASE_NAME}-metrics-api" --timeout=180s

echo "Deployment complete for environment: ${ENVIRONMENT}"
