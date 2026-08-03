#!/usr/bin/env bash
# Fail-fast prerequisite checks. Source this file, then: require docker helm kind
set -euo pipefail

require() {
  local _cmd
  for _cmd in "$@"; do
    command -v "${_cmd}" >/dev/null 2>&1 || {
      echo "error: ${_cmd} not found in PATH" >&2
      exit 1
    }
  done
}

# Back-compat helpers for existing call sites.
require_docker() { require docker; }
require_helm() { require helm; }
require_kubectl() { require kubectl; }
require_kind() { require kind; }
