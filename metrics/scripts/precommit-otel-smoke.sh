#!/usr/bin/env bash
# Manual local Collector export and privacy proof.
set -euo pipefail

context="kind-metrics"
namespace="metrics"
trace_id="0123456789abcdef0123456789abcdef"
tmp="$(mktemp -d)"
forward_pid=""

cleanup() {
  if [[ -n "$forward_pid" ]]; then
    kill "$forward_pid" 2>/dev/null || true
    wait "$forward_pid" 2>/dev/null || true
  fi
  rm -rf "$tmp"
}
trap cleanup EXIT

# Every mutation below is permitted only after the exact local kind target is proven.
UV_CACHE_DIR="${UV_CACHE_DIR:-/tmp/canfar-uv-cache}" \
  uv run python -c 'from metrics.dev.stack import assert_safe_context; assert_safe_context()'

kubectl --context "$context" --namespace "$namespace" \
  rollout restart deployment/metrics-api-collector
kubectl --context "$context" --namespace "$namespace" \
  rollout status deployment/metrics-api-collector --timeout=90s
kubectl --context "$context" --namespace "$namespace" \
  exec deployment/metrics-api-redis -- redis-cli FLUSHDB >/dev/null
kubectl --context "$context" --namespace "$namespace" \
  rollout restart deployment/metrics-api-metrics-api
kubectl --context "$context" --namespace "$namespace" \
  rollout status deployment/metrics-api-metrics-api --timeout=90s

kubectl --context "$context" --namespace "$namespace" \
  port-forward service/metrics-api-metrics-api 18086:8000 \
  >"$tmp/port-forward.log" 2>&1 &
forward_pid="$!"

UV_CACHE_DIR="${UV_CACHE_DIR:-/tmp/canfar-uv-cache}" uv run python - "$trace_id" <<'PY'
import sys
import time
import urllib.error
import urllib.request

trace_id = sys.argv[1]
request = urllib.request.Request(
    "http://127.0.0.1:18086/apis/canfar.net/v1alpha1/metrics/platform/canfar",
    headers={"traceparent": f"00-{trace_id}-0123456789abcdef-01"},
)
deadline = time.monotonic() + 30
while True:
    try:
        with urllib.request.urlopen(request, timeout=5) as response:
            if response.status != 200:
                raise SystemExit(f"platform request returned HTTP {response.status}")
            break
    except (OSError, urllib.error.URLError):
        if time.monotonic() >= deadline:
            raise SystemExit("metrics API port-forward did not become ready")
        time.sleep(0.25)
time.sleep(4)
PY

collector_pod="$(
  kubectl --context "$context" --namespace "$namespace" get pods \
    -l app.kubernetes.io/component=collector \
    --sort-by=.metadata.creationTimestamp \
    -o jsonpath='{.items[-1].metadata.name}'
)"
kubectl --context "$context" --namespace "$namespace" \
  cp -c evidence "$collector_pod:/var/lib/otel/evidence.json" "$tmp/evidence.json"

UV_CACHE_DIR="${UV_CACHE_DIR:-/tmp/canfar-uv-cache}" \
  uv run python - "$tmp/evidence.json" scripts/workload-fixtures.yaml "$trace_id" <<'PY'
import json
import sys
from pathlib import Path

import yaml

evidence_path = Path(sys.argv[1])
fixture_path = Path(sys.argv[2])
trace_id = sys.argv[3]
raw = evidence_path.read_text(encoding="utf-8")
documents = [json.loads(line) for line in raw.splitlines() if line.strip()]
if not documents:
    raise SystemExit("Collector file exporter produced no JSON evidence")

fixtures = list(yaml.safe_load_all(fixture_path.read_text(encoding="utf-8")))
identities: set[str] = set()

def visit(value):
    if isinstance(value, dict):
        for key, child in value.items():
            if key in {"canfar.net/username", "canfar.net/community"} and isinstance(child, str):
                identities.add(child)
            visit(child)
    elif isinstance(value, list):
        for child in value:
            visit(child)

visit(fixtures)
for forbidden in sorted(identities):
    if forbidden and forbidden in raw:
        raise SystemExit(f"fixture identity leaked into Collector evidence: {forbidden!r}")

for forbidden in (
    "labelSelector",
    "fieldSelector",
    "canfar.net/username=",
    "canfar.net/community=",
    "/metrics/user/",
    "/metrics/community/",
    "metrics-cache-v1",
    "metrics:",
    "PromQL",
):
    if forbidden.lower() in raw.lower():
        raise SystemExit(f"forbidden telemetry content found: {forbidden!r}")

required = (
    "metrics.get",
    "cache.get_or_fill",
    "source.read",
    "application.startup",
    "canfar.metrics.cache.lookups",
    "canfar.metrics.cache.leases",
    "canfar.metrics.cache.fill.duration",
    "canfar.metrics.provider.duration",
    "canfar.metrics.redis.duration",
    "canfar.metrics.redis.health",
    "canfar.metrics.readiness",
    "canfar.metrics.lifecycle.duration",
    "Platform metrics request completed",
)
missing = [item for item in required if item not in raw]
if missing:
    raise SystemExit(f"missing Collector evidence: {', '.join(missing)}")
if trace_id not in raw:
    raise SystemExit("W3C trace context was not propagated into Collector evidence")
if "http.server.request.duration" not in raw and "http.server.duration" not in raw:
    raise SystemExit("missing HTTP server request duration metric")

print(
    f"OTel smoke passed: {len(documents)} JSON exports, "
    f"{len(identities)} fixture identities absent"
)
PY
