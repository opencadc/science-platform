#!/usr/bin/env bash
#
# Fail-fast checks for Metrics local and CI helper scripts.
# Usage: source this file and call require_* helpers, or:
#   bash scripts/check-prerequisites.sh docker helm kind
#
set -euo pipefail

_SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [[ "${BASH_SOURCE[0]}" != "${0}" ]]; then
  _PREREQ_SOURCED=1
else
  _PREREQ_SOURCED=0
fi

_die() {
  echo "error: ${1}" >&2
  echo "${2:-}" >&2
  if [[ "${_PREREQ_SOURCED}" -eq 1 ]]; then
    return 1
  fi
  exit 1
}

require_docker() {
  command -v docker >/dev/null 2>&1 ||
    _die "docker CLI not found in PATH" "Install Docker Engine or Docker Desktop: https://docs.docker.com/engine/install/"
}

require_compose() {
  require_docker
  if docker compose version >/dev/null 2>&1; then
    return 0
  fi
  _die "Docker Compose plugin not available" \
    "Install Docker Compose v2 (included with Docker Desktop): https://docs.docker.com/compose/install/"
}

require_helm() {
  command -v helm >/dev/null 2>&1 ||
    _die "helm CLI not found in PATH" "Install Helm: https://helm.sh/docs/intro/install/"
}

require_kubectl() {
  command -v kubectl >/dev/null 2>&1 ||
    _die "kubectl CLI not found in PATH" "Install kubectl from your Kubernetes distribution."
}

require_kind() {
  command -v kind >/dev/null 2>&1 ||
    _die "kind CLI not found in PATH" "Install kind: https://kind.sigs.k8s.io/docs/user/quick-start/"
}

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  if [[ $# -eq 0 ]]; then
    set -- docker helm kind
  fi
  for arg in "$@"; do
    case "${arg}" in
      docker) require_docker ;;
      compose) require_compose ;;
      helm) require_helm ;;
      kubectl) require_kubectl ;;
      kind) require_kind ;;
      *)
        echo "unknown prerequisite: ${arg}" >&2
        echo "usage: $0 [docker|compose|helm|kubectl|kind] ..." >&2
        exit 2
        ;;
    esac
  done
fi
