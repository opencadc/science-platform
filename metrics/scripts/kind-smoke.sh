#!/usr/bin/env bash
# Compatibility wrapper. The installed Python entrypoint owns the lifecycle.
set -euo pipefail

_SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${_SCRIPT_DIR}/.."
exec uv run metrics-dev smoke "$@"
