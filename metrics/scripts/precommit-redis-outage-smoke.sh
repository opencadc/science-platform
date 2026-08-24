#!/usr/bin/env bash
# Manual pre-commit / CI stage for Redis outage behavior.
# Fails closed until the Redis coordination package lands a real proof.
set -euo pipefail
echo "Redis outage smoke is not implemented yet; refusing empty pass." >&2
exit 2
