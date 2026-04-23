#!/usr/bin/env bash
# Shared utilities for kind-smoke.sh and kind-smoke-teardown.sh (source only).
# shellcheck shell=bash

# Read a numeric PORT_FORWARD_PID= line; never source the file.
metrics_kind_smoke_read_port_forward_pid() {
  local _f="${1:-}" _p
  [[ -n "${_f}" && -f "${_f}" ]] || return 1
  _p="$(awk -F= '/^PORT_FORWARD_PID=/{p=$2; gsub(/[^0-9].*/,"",p); if (p+0>0) {print p; exit}}' "${_f}")"
  [[ -n "${_p}" ]] || return 1
  echo "${_p}"
}

# Best-effort stop a process (may not be a child of this shell).
metrics_kind_smoke_stop_pid() {
  local _p="${1:-}"
  [[ -n "${_p}" ]] && [[ "${_p}" =~ ^[0-9]+$ ]] || return 0
  kill "${_p}" 2>/dev/null || return 0
  local _i
  for _i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do
    kill -0 "${_p}" 2>/dev/null || return 0
    sleep 0.15
  done
  kill -9 "${_p}" 2>/dev/null || true
}
