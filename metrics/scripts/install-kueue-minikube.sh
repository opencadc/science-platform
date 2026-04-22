#!/usr/bin/env bash
#
# Install Kueue controller (0.17.x+) into the active kubectl context via the
# official Helm chart (OCI). Same entry point for local Minikube and CI.
#
# Environment:
#   KUEUE_CHART_VERSION — Helm chart semver, e.g. 0.17.0 (default: 0.17.0)
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

echo "Installing Kueue Helm chart version ${KUEUE_CHART_VERSION} (release=${KUEUE_RELEASE_NAME}, ns=${KUEUE_NAMESPACE})"
helm upgrade --install "${KUEUE_RELEASE_NAME}" oci://registry.k8s.io/kueue/charts/kueue \
  --version "${KUEUE_CHART_VERSION}" \
  --namespace "${KUEUE_NAMESPACE}" \
  --create-namespace \
  --wait --timeout 300s

echo "Waiting for kueue-controller-manager to become available"
kubectl wait deploy/kueue-controller-manager -n "${KUEUE_NAMESPACE}" \
  --for=condition=available --timeout=5m
