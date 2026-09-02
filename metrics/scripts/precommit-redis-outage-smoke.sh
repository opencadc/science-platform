#!/usr/bin/env bash
# Manual pre-commit / CI stage for real Redis coordination and outage behavior.
set -euo pipefail

container="metrics-redis-smoke-${$}"

cleanup() {
  docker unpause "${container}" >/dev/null 2>&1 || true
  docker rm -f "${container}" >/dev/null 2>&1 || true
}
trap cleanup EXIT

docker run --detach --rm \
  --name "${container}" \
  --publish 127.0.0.1::6379 \
  redis:7-alpine >/dev/null

mapping="$(docker port "${container}" 6379/tcp)"
port="${mapping##*:}"
if [[ -z "${port}" ]]; then
  echo "Could not determine Redis smoke port" >&2
  exit 1
fi

METRICS_TEST_REDIS_URL="redis://127.0.0.1:${port}/0" \
METRICS_TEST_REDIS_CONTAINER="${container}" \
UV_CACHE_DIR="${UV_CACHE_DIR:-/tmp/canfar-uv-cache}" \
  uv run pytest tests/integration/test_redis_cache.py -q
