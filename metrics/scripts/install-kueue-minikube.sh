#!/usr/bin/env bash
#
# Install Kueue controller (0.17.x+) into the active kubectl context via the
# official Helm chart (OCI). Same entry point for local Minikube and CI.
#
# Environment:
#   KUEUE_CHART_VERSION — Helm chart semver, e.g. 0.17.0 (default: 0.17.0)
#   KUEUE_IMAGE — full image ref to preload (default: registry.k8s.io/kueue/kueue:v$KUEUE_CHART_VERSION)
#   HELM_INSTALL_RETRIES — helm upgrade attempts (default: 3)
#
# Docs: https://kueue.sigs.k8s.io/docs/installation/#install-by-helm
#
set -euo pipefail

_SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${_SCRIPT_DIR}/check-prerequisites.sh"
require_kubectl
require_helm

KUEUE_CHART_VERSION="${KUEUE_CHART_VERSION:-0.17.0}"
KUEUE_RELEASE_NAME="${KUEUE_RELEASE_NAME:-kueue}"
KUEUE_NAMESPACE="${KUEUE_NAMESPACE:-kueue-system}"
# Controller image tag defaults to chart semver (e.g. 0.17.0 -> v0.17.0); override if they diverge.
KUEUE_IMAGE="${KUEUE_IMAGE:-registry.k8s.io/kueue/kueue:v${KUEUE_CHART_VERSION}}"

if command -v minikube >/dev/null 2>&1 && minikube status >/dev/null 2>&1; then
  echo "Pre-loading Kueue image on runner and into Minikube: ${KUEUE_IMAGE}"
  require_docker
  docker pull "${KUEUE_IMAGE}"
  minikube image load "${KUEUE_IMAGE}"
fi

echo "Installing Kueue Helm chart version ${KUEUE_CHART_VERSION} (release=${KUEUE_RELEASE_NAME}, ns=${KUEUE_NAMESPACE})"
_helm_install_kueue() {
  helm upgrade --install "${KUEUE_RELEASE_NAME}" oci://registry.k8s.io/kueue/charts/kueue \
    --version "${KUEUE_CHART_VERSION}" \
    --namespace "${KUEUE_NAMESPACE}" \
    --create-namespace \
    --wait --timeout 300s
}

_max="${HELM_INSTALL_RETRIES:-3}"
_attempt=1
while true; do
  if _helm_install_kueue; then
    break
  fi
  if [[ "${_attempt}" -ge "${_max}" ]]; then
    echo "error: helm install failed after ${_attempt} attempt(s)" >&2
    exit 1
  fi
  echo "Helm install attempt ${_attempt} failed; retrying ($(("${_attempt}" + 1))/${_max}) after 15s..."
  sleep 15
  _attempt=$((_attempt + 1))
done

echo "Waiting for kueue-controller-manager to become available"
kubectl wait deploy/kueue-controller-manager -n "${KUEUE_NAMESPACE}" \
  --for=condition=available --timeout=5m
