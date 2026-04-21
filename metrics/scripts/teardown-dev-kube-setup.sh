#!/usr/bin/env bash
#
# Tear down the local dev stack from docs/dev-kueue-cluster-setup.md:
#   Metrics API Helm release (+ optional metrics namespace), Kueue test fixtures,
#   and optionally the Kueue controller Helm release.
#
# Does NOT delete your Minikube profile/cluster, host Docker images, or stop Minikube.
#
# Environment (same defaults as the dev doc):
#   KUBE_CONTEXT          — kubectl context (default: minikube)
#   NAMESPACE             — Metrics Helm namespace (default: metrics)
#   RELEASE_NAME          — Metrics Helm release name (default: metrics-api)
#   KUEUE_RELEASE_NAME    — Kueue Helm release (default: kueue)
#   KUEUE_NAMESPACE       — Kueue namespace (default: kueue-system)
#   DELETE_METRICS_NS     — after uninstall, delete the metrics namespace (default: true)
#   UNINSTALL_KUEUE       — helm uninstall Kueue release (default: true)
#   DELETE_FIXTURES       — kubectl delete M2 fixtures (default: true)
#
# Flags:
#   --skip-kueue          — do not uninstall the Kueue Helm release
#   --skip-fixtures       — do not delete tests/fixtures/kueue resources
#   --keep-metrics-ns     — do not delete the metrics namespace after helm uninstall
#
set -euo pipefail

_SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
METRICS_ROOT="$(cd "${_SCRIPT_DIR}/.." && pwd)"
# shellcheck disable=SC1091
source "${_SCRIPT_DIR}/check-prerequisites.sh"
require_helm
require_kubectl

KUBE_CONTEXT="${KUBE_CONTEXT:-minikube}"
NAMESPACE="${NAMESPACE:-metrics}"
RELEASE_NAME="${RELEASE_NAME:-metrics-api}"
KUEUE_RELEASE_NAME="${KUEUE_RELEASE_NAME:-kueue}"
KUEUE_NAMESPACE="${KUEUE_NAMESPACE:-kueue-system}"
DELETE_METRICS_NS="${DELETE_METRICS_NS:-true}"
UNINSTALL_KUEUE="${UNINSTALL_KUEUE:-true}"
DELETE_FIXTURES="${DELETE_FIXTURES:-true}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-kueue) UNINSTALL_KUEUE=false ;;
    --skip-fixtures) DELETE_FIXTURES=false ;;
    --keep-metrics-ns) DELETE_METRICS_NS=false ;;
    -h | --help)
      cat <<'EOF'
Usage: bash scripts/teardown-dev-kube-setup.sh [options]

  --skip-kueue       Leave the Kueue Helm release installed (fixtures still removed unless --skip-fixtures).
  --skip-fixtures    Do not delete tests/fixtures/kueue ClusterQueues / Cohort / ResourceFlavor.
  --keep-metrics-ns  Do not delete the metrics namespace after uninstalling the chart.

Environment: KUBE_CONTEXT, NAMESPACE, RELEASE_NAME, KUEUE_RELEASE_NAME, KUEUE_NAMESPACE,
  DELETE_METRICS_NS, UNINSTALL_KUEUE, DELETE_FIXTURES (see script header comments).
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

KUBECTL=(kubectl --context "${KUBE_CONTEXT}")
HELM_CTX=(--kube-context "${KUBE_CONTEXT}")

echo "Teardown: context=${KUBE_CONTEXT}"

if helm status "${RELEASE_NAME}" -n "${NAMESPACE}" "${HELM_CTX[@]}" >/dev/null 2>&1; then
  echo "Helm uninstall: release=${RELEASE_NAME} namespace=${NAMESPACE}"
  helm uninstall "${RELEASE_NAME}" -n "${NAMESPACE}" "${HELM_CTX[@]}" --wait --timeout 120s
else
  echo "No Helm release ${RELEASE_NAME} in ${NAMESPACE} (skipping uninstall)"
fi

if [[ "${DELETE_METRICS_NS}" == "true" ]]; then
  if "${KUBECTL[@]}" get namespace "${NAMESPACE}" >/dev/null 2>&1; then
    echo "Deleting namespace ${NAMESPACE}"
    "${KUBECTL[@]}" delete namespace "${NAMESPACE}" --wait --timeout=120s
  else
    echo "Namespace ${NAMESPACE} already absent"
  fi
fi

if [[ "${DELETE_FIXTURES}" == "true" ]]; then
  echo "Deleting Kueue fixtures (reverse apply order)"
  shopt -s nullglob
  _fixture_files=("${METRICS_ROOT}/tests/fixtures/kueue/"*.yaml)
  shopt -u nullglob
  if [[ "${#_fixture_files[@]}" -eq 0 ]]; then
    echo "No fixture YAML files under tests/fixtures/kueue/"
  else
    for ((i = ${#_fixture_files[@]} - 1; i >= 0; i--)); do
      _f="${_fixture_files[i]}"
      echo "  kubectl delete -f ${_f##*/}"
      "${KUBECTL[@]}" delete -f "${_f}" --ignore-not-found --wait --timeout=60s || true
    done
  fi
fi

if [[ "${UNINSTALL_KUEUE}" == "true" ]]; then
  if helm status "${KUEUE_RELEASE_NAME}" -n "${KUEUE_NAMESPACE}" "${HELM_CTX[@]}" >/dev/null 2>&1; then
    echo "Helm uninstall: Kueue release=${KUEUE_RELEASE_NAME} namespace=${KUEUE_NAMESPACE}"
    helm uninstall "${KUEUE_RELEASE_NAME}" -n "${KUEUE_NAMESPACE}" "${HELM_CTX[@]}" --wait --timeout 300s
  else
    echo "No Helm release ${KUEUE_RELEASE_NAME} in ${KUEUE_NAMESPACE} (skipping Kueue uninstall)"
  fi
else
  echo "Skipping Kueue uninstall (--skip-kueue)"
fi

echo "Done. (Cluster CRDs from Kueue may remain; Minikube profile and Docker images were not removed.)"
