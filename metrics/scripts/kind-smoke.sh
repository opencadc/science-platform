#!/usr/bin/env bash
# Compatibility wrapper for the one supported development stack.
set -euo pipefail

_SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${_SCRIPT_DIR}/.."
uv run metrics-dev up
exec uv run metrics-dev smoke
