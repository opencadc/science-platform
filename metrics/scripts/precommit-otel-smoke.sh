#!/usr/bin/env bash
# Manual local OTLP metrics export and privacy proof.
set -euo pipefail

context="kind-metrics"
namespace="metrics"
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
  rollout restart deployment/metrics-test-otel-collector
kubectl --context "$context" --namespace "$namespace" \
  rollout status deployment/metrics-test-otel-collector --timeout=90s
kubectl --context "$context" --namespace "$namespace" \
  exec deployment/metrics-test-redis -- redis-cli FLUSHDB >/dev/null
kubectl --context "$context" --namespace "$namespace" \
  rollout restart deployment/metrics-api-metrics-api
kubectl --context "$context" --namespace "$namespace" \
  rollout status deployment/metrics-api-metrics-api --timeout=90s

kubectl --context "$context" --namespace "$namespace" \
  port-forward service/metrics-api-metrics-api 18086:8000 \
  >"$tmp/port-forward.log" 2>&1 &
forward_pid="$!"

UV_CACHE_DIR="${UV_CACHE_DIR:-/tmp/canfar-uv-cache}" \
  uv run python - "$tmp/api-payloads.json" "$tmp/port-forward.log" <<'PY'
import json
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

payload_path = Path(sys.argv[1])
forward_log = Path(sys.argv[2])
base_url = "http://127.0.0.1:18086"


def _error_body(error: urllib.error.HTTPError) -> str:
    """Return a bounded response body for a useful smoke failure."""
    try:
        body = error.read(512).decode("utf-8", errors="replace").strip()
    except OSError:
        body = ""
    return f": {body}" if body else ""


def _request_json(route: str) -> object:
    """Make one successful application request and decode its JSON response."""
    request = urllib.request.Request(
        f"{base_url}{route}",
        headers={"Accept": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=5) as response:
            if response.status != 200:
                raise SystemExit(f"{route} returned HTTP {response.status}")
            try:
                return json.load(response)
            except json.JSONDecodeError as error:
                raise SystemExit(f"{route} returned invalid JSON: {error}") from error
    except urllib.error.HTTPError:
        raise
    except (OSError, urllib.error.URLError) as error:
        raise RuntimeError(str(error)) from error


def _wait_for_ready() -> None:
    """Wait for the forwarded service to report application readiness."""
    deadline = time.monotonic() + 30
    last_error = "the port-forward is not ready"
    while True:
        try:
            payload = _request_json("/readyz")
            if isinstance(payload, dict) and payload.get("status") == "ready":
                return
            raise SystemExit("metrics API readiness endpoint returned an unexpected payload")
        except urllib.error.HTTPError as error:
            detail = _error_body(error)
            if error.code != 503:
                raise SystemExit(f"/readyz returned HTTP {error.code}{detail}") from error
            last_error = f"/readyz returned HTTP 503{detail}"
        except RuntimeError as error:
            last_error = str(error)
        if time.monotonic() >= deadline:
            raise SystemExit(
                "metrics API did not become ready within 30s: "
                f"{last_error}; see {forward_log}"
            )
        time.sleep(0.25)


_wait_for_ready()
routes = [
    "/apis/canfar.net/v1alpha1/metrics/platform/canfar",
    "/apis/canfar.net/v1alpha1/metrics/user/bob",
    "/apis/canfar.net/v1alpha1/metrics/community/astronomy",
]
payloads = []
for route in routes:
    try:
        payloads.append(_request_json(route))
    except urllib.error.HTTPError as error:
        raise SystemExit(f"{route} returned HTTP {error.code}{_error_body(error)}") from error
    except RuntimeError as error:
        raise SystemExit(f"{route} could not be reached: {error}; see {forward_log}") from error

payload_path.write_text(json.dumps(payloads, sort_keys=True), encoding="utf-8")
time.sleep(4)
PY

collector_pod="$(
  kubectl --context "$context" --namespace "$namespace" get pods \
    -l app=metrics-test-otel-collector \
    --sort-by=.metadata.creationTimestamp \
    -o jsonpath='{.items[-1].metadata.name}'
)"
if [[ -z "$collector_pod" ]]; then
  echo "OTLP metrics smoke failed: no disposable Collector pod was found" >&2
  exit 1
fi

if ! kubectl --context "$context" --namespace "$namespace" \
  cp -c collector "$collector_pod:/var/lib/otel/evidence.json" "$tmp/evidence.json"; then
  echo "OTLP metrics smoke failed: could not copy Collector evidence from $collector_pod" >&2
  exit 1
fi
if [[ ! -s "$tmp/evidence.json" ]]; then
  echo "OTLP metrics smoke failed: Collector file exporter produced an empty evidence file" >&2
  exit 1
fi

UV_CACHE_DIR="${UV_CACHE_DIR:-/tmp/canfar-uv-cache}" \
  uv run python - "$tmp/evidence.json" scripts/workload-fixtures.yaml "$tmp/api-payloads.json" <<'PY'
import json
import sys
from pathlib import Path

import yaml

evidence_path = Path(sys.argv[1])
fixture_path = Path(sys.argv[2])
payload_path = Path(sys.argv[3])
raw = evidence_path.read_text(encoding="utf-8")
documents = []
for line_number, line in enumerate(raw.splitlines(), start=1):
    if not line.strip():
        continue
    try:
        documents.append(json.loads(line))
    except json.JSONDecodeError as error:
        raise SystemExit(
            f"Collector evidence line {line_number} is not valid JSON: {error.msg}"
        ) from error
if not documents:
    raise SystemExit("Collector file exporter produced no JSON evidence")
if not any(isinstance(document, dict) and "resourceMetrics" in document for document in documents):
    raise SystemExit("Collector file exporter produced no resourceMetrics evidence")

try:
    fixtures = list(yaml.safe_load_all(fixture_path.read_text(encoding="utf-8")))
    payloads = json.loads(payload_path.read_text(encoding="utf-8"))
except (OSError, json.JSONDecodeError) as error:
    raise SystemExit(f"could not read smoke privacy inputs: {error}") from error
if not isinstance(payloads, list) or len(payloads) != 3:
    raise SystemExit("API smoke did not capture all three metrics response payloads")

fixture_users: set[str] = set()
fixture_communities: set[str] = set()


def visit_fixture(value: object) -> None:
    """Collect only user and community identity values from workload fixtures."""
    if isinstance(value, dict):
        for key, child in value.items():
            if key == "canfar.net/username" and isinstance(child, str):
                fixture_users.add(child)
            elif key == "canfar.net/community" and isinstance(child, str):
                fixture_communities.add(child)
            visit_fixture(child)
    elif isinstance(value, list):
        for child in value:
            visit_fixture(child)


visit_fixture(fixtures)
identities = fixture_users | fixture_communities
if not identities:
    raise SystemExit("workload fixtures contained no user/community identities to check")

for forbidden in sorted(identities):
    if forbidden and forbidden in raw:
        raise SystemExit(f"fixture identity leaked into Collector metric evidence: {forbidden!r}")

for label, values in (
    ("canfar.net/username", fixture_users),
    ("canfar.net/community", fixture_communities),
):
    for value in values:
        for forbidden in (
            f"{label}={value}",
            f"{label.replace('/', '%2F')}%3D{value}",
        ):
            if forbidden.lower() in raw.lower():
                raise SystemExit(f"Kubernetes selector leaked into Collector evidence: {forbidden!r}")

for forbidden in (
    "labelSelector",
    "fieldSelector",
    "canfar.net/username=",
    "canfar.net/community=",
    "metrics:",
    "metrics-cache-v1",
    "metrics-cache-not-found-v1",
    "canfar_active_workload_",
    '"observedAt"',
    '"reservingWorkloads"',
    '"resources"',
    '"conditions"',
    '"capacity"',
    '"allocated"',
    '"requests"',
    '"efficiency"',
):
    if forbidden.lower() in raw.lower():
        raise SystemExit(f"forbidden identity, cache, or payload content found: {forbidden!r}")

payload_markers: set[str] = set()
for payload in payloads:
    if not isinstance(payload, dict):
        raise SystemExit("API smoke returned a non-object metrics payload")
    spec = payload.get("spec")
    if isinstance(spec, dict):
        for key in ("user", "community"):
            value = spec.get(key)
            if isinstance(value, str):
                payload_markers.add(value)
    status = payload.get("status")
    if not isinstance(status, dict):
        raise SystemExit("API smoke returned a metrics payload without status")
    resources = status.get("resources")
    if not isinstance(resources, list):
        raise SystemExit("API smoke returned a metrics payload without resources")
    for resource in resources:
        if not isinstance(resource, dict):
            raise SystemExit("API smoke returned an invalid resource payload")
        for key in ("name", "capacity", "allocated", "requests", "efficiency"):
            value = resource.get(key)
            if isinstance(value, str) and len(value) > 1:
                payload_markers.add(value)
    conditions = status.get("conditions")
    if not isinstance(conditions, list):
        raise SystemExit("API smoke returned a metrics payload without conditions")
    for condition in conditions:
        if not isinstance(condition, dict):
            raise SystemExit("API smoke returned an invalid condition payload")
        for key in ("type", "reason"):
            value = condition.get(key)
            if isinstance(value, str):
                payload_markers.add(value)

for forbidden in sorted(payload_markers):
    if forbidden and forbidden in raw:
        raise SystemExit(f"API payload value leaked into Collector evidence: {forbidden!r}")

# These are the application-state instruments recorded by the healthy startup,
# Redis-backed cold fills, and the three API requests above. Compute duration and
# provider errors are declared by the recorder but are not recorded on this path.
required_metrics = (
    "canfar.metrics.cache.lookups",
    "canfar.metrics.cache.age",
    "canfar.metrics.cache.leases",
    "canfar.metrics.cache.fill.duration",
    "canfar.metrics.provider.duration",
    "canfar.metrics.redis.duration",
    "canfar.metrics.redis.health",
    "canfar.metrics.readiness",
    "canfar.metrics.lifecycle.duration",
)
missing = [item for item in required_metrics if item not in raw]
if missing:
    raise SystemExit(f"missing required application metric evidence: {', '.join(missing)}")

print(
    f"OTLP metrics smoke passed: {len(documents)} JSON exports, "
    f"{len(identities)} fixture identities absent"
)
PY
