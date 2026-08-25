#!/usr/bin/env bash
# Manual pre-commit / CI stage: build the production image and prove non-root
# runtime, package metadata, /healthz, and the three v1alpha1 GET routes.
#
# Entrypoint smoke reaches the existing kind ``metrics`` cluster through a
# rewritten kubeconfig (host.docker.internal + insecure-skip-tls-verify) so
# ClusterQueue startup checks can succeed from a bridged container.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT}"

IMAGE_TAG="${METRICS_IMAGE_SMOKE_TAG:-metrics:toolchain-smoke}"
KIND_CLUSTER="${METRICS_KIND_CLUSTER:-metrics}"
KIND_CONTEXT="${METRICS_KIND_CONTEXT:-kind-metrics}"
HOST_PORT="${METRICS_IMAGE_SMOKE_PORT:-18000}"

echo "==> building ${IMAGE_TAG}"
docker build -t "${IMAGE_TAG}" .

echo "==> non-root identity"
uid="$(docker run --rm --entrypoint id "${IMAGE_TAG}" -u)"
if [[ "${uid}" == "0" ]]; then
  echo "expected non-root uid, got ${uid}" >&2
  exit 1
fi

echo "==> package import + metadata"
docker run --rm --entrypoint python "${IMAGE_TAG}" -c \
  'import importlib.metadata as m; import metrics; print(m.version("metrics"))'

echo "==> preparing kind kubeconfig for container access"
if ! kind get clusters | grep -qx "${KIND_CLUSTER}"; then
  echo "kind cluster ${KIND_CLUSTER} is required for entrypoint smoke" >&2
  exit 1
fi
if ! kubectl --context "${KIND_CONTEXT}" cluster-info >/dev/null; then
  echo "kubectl context ${KIND_CONTEXT} is unreachable" >&2
  exit 1
fi

tmp_dir="$(mktemp -d)"
cleanup_files() {
  rm -rf "${tmp_dir}"
}
trap cleanup_files EXIT

kubeconfig_path="${tmp_dir}/kubeconfig"
kind get kubeconfig --name "${KIND_CLUSTER}" >"${kubeconfig_path}"
# Rewrite API host for Docker Desktop/Linux bridge and skip TLS hostname checks.
# Prefer stdlib only so the smoke does not depend on host PyYAML.
python3 - "${kubeconfig_path}" <<'PY'
from pathlib import Path
import re
import sys

path = Path(sys.argv[1])
text = path.read_text(encoding="utf-8")
text = text.replace("https://127.0.0.1:", "https://host.docker.internal:")
text = text.replace("https://localhost:", "https://host.docker.internal:")
text = re.sub(
    r"(?m)^\s*certificate-authority-data:\s*.*\n",
    "",
    text,
)
text = re.sub(
    r"(?m)^\s*certificate-authority:\s*.*\n",
    "",
    text,
)
if "insecure-skip-tls-verify:" not in text:
    text = text.replace(
        "cluster:\n",
        "cluster:\n    insecure-skip-tls-verify: true\n",
        1,
    )
path.write_text(text, encoding="utf-8")
PY

echo "==> starting entrypoint smoke"
container_id="$(
  docker run -d \
    --add-host=host.docker.internal:host-gateway \
    -e KUBECONFIG=/kubeconfig/config \
    -e METRICS_CACHE__BACKEND=memory \
    -e METRICS_CLUSTER_NAME=kind-metrics \
    -e 'METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES=["cq-proton","cq-electron"]' \
    -e 'METRICS_PROVIDERS__KUBERNETES__WORKLOAD_NAMESPACES=["canfar-workloads"]' \
    -v "${kubeconfig_path}:/kubeconfig/config:ro" \
    -p "${HOST_PORT}:8000" \
    "${IMAGE_TAG}"
)"

cleanup() {
  status=$?
  if [[ "${status}" -ne 0 ]]; then
    docker logs "${container_id}" >&2 || true
  fi
  docker rm -f "${container_id}" >/dev/null 2>&1 || true
  cleanup_files
  exit "${status}"
}
trap cleanup EXIT

ready=0
for _ in $(seq 1 45); do
  if ! docker inspect -f '{{.State.Running}}' "${container_id}" 2>/dev/null | grep -qx true; then
    echo "container exited before becoming ready" >&2
    exit 1
  fi
  if curl -fsS "http://127.0.0.1:${HOST_PORT}/healthz" >/dev/null 2>&1; then
    ready=1
    break
  fi
  sleep 1
done

if [[ "${ready}" -ne 1 ]]; then
  echo "timed out waiting for /healthz" >&2
  exit 1
fi

curl -fsS "http://127.0.0.1:${HOST_PORT}/healthz" >/dev/null
for route in \
  /apis/canfar.net/v1alpha1/metrics/platform/canfar \
  /apis/canfar.net/v1alpha1/metrics/user/bob \
  /apis/canfar.net/v1alpha1/metrics/community/astronomy; do
  curl -fsS "http://127.0.0.1:${HOST_PORT}${route}" >/dev/null
done
legacy_status="$(curl -sS -o /dev/null -w '%{http_code}' \
  "http://127.0.0.1:${HOST_PORT}/api/v1/metrics/platform" || true)"
if [[ "${legacy_status}" != "404" ]]; then
  echo "expected legacy Platform route to be absent, got HTTP ${legacy_status}" >&2
  exit 1
fi
echo "image smoke ok (healthz=200, platform=200, user=200, community=200, legacy=404)"
