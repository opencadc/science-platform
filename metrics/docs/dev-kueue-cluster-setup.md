# Dev setup: Kueue + Metrics API on a local cluster

Use this flow when you want **Kueue-backed** platform metrics (`METRICS_PROVIDER_MODE=kueue`) against a **local Kubernetes** cluster (Minikube is the default in this repo; any 1.29+ cluster with `kubectl` works).

This guide assumes you **reuse your existing Minikube** (the usual default profile/context name is `**minikube`**). It does **not** create a separate profile such as `metrics-local`; you install Kueue and the metrics chart into whatever cluster your `kubectl` context points at.

All commands assume the `**metrics/`** directory as the current working directory unless noted.

Optional: set `KUBE_CONTEXT` if your Minikube context is not the default (examples below use `minikube`):

```bash
export KUBE_CONTEXT="${KUBE_CONTEXT:-minikube}"
```

---

## 1. Preflight checks

Confirm tooling and cluster reachability:

```bash
bash scripts/check-prerequisites.sh docker helm kubectl minikube
```

Use the **running** Minikube control plane (do not create a new cluster for this flow unless you have none):

```bash
minikube status
kubectl config current-context    # expect "minikube" for a default Minikube install
kubectl cluster-info
kubectl version
```

If the cluster is stopped, start the **default** profile (no `-p` flag):

```bash
minikube start
kubectl config use-context minikube   # if needed
```

(Optional) Enable **metrics-server** on that profile (broader platform work; safe to enable):

```bash
minikube addons enable metrics-server
```

---

## 2. Install Kueue (Helm)

The helper script installs the **official Kueue Helm chart** from `registry.k8s.io` into `kueue-system` and waits for the controller to become ready:

```bash
bash scripts/install-kueue-minikube.sh
```

Override the chart version if needed (must match a published chart, e.g. `0.17.0`):

```bash
KUEUE_CHART_VERSION=0.17.0 bash scripts/install-kueue-minikube.sh
```

Equivalent manual command (what the script runs):

```bash
helm upgrade --install kueue oci://registry.k8s.io/kueue/charts/kueue \
  --version "${KUEUE_CHART_VERSION:-0.17.0}" \
  --namespace kueue-system \
  --create-namespace \
  --wait --timeout 300s

kubectl wait deploy/kueue-controller-manager -n kueue-system \
  --for=condition=available --timeout=5m
```

See also: [Kueue installation](https://kueue.sigs.k8s.io/docs/installation/).

---

## 3. Apply ClusterQueue / Cohort fixtures

M2 test fixtures live under `tests/fixtures/kueue/`. Apply them in order (filenames are prefixed `00–04`):

```bash
kubectl apply -f tests/fixtures/kueue/
```

This seeds **ResourceFlavor**, **Cohort** `cohort-atom`, and **ClusterQueues** `cq-proton`, `cq-neutron`, `cq-electron` with asymmetric CPU/memory quotas.

Confirm the objects exist in **the same** `kubectl` context you will use for Helm (cluster-scoped resources have no `-n` namespace):

```bash
kubectl get resourceflavors.kueue.x-k8s.io
kubectl get cohorts.kueue.x-k8s.io
kubectl get clusterqueues.kueue.x-k8s.io
```

You should see `default-flavor`, `cohort-atom`, and the three `cq-*` queues. If these are missing, the metrics pod will **crash-loop** in Kueue mode (startup validation runs before `/healthz` is available).

---

## 4. Build and deploy Metrics + Redis (Helm)

**Image:** build a local tag and load it into Minikube so the chart can run `imagePullPolicy: Never` / local image:

```bash
export KUBE_CONTEXT="${KUBE_CONTEXT:-minikube}"
export LOCAL_IMAGE="${LOCAL_IMAGE:-canfar-metrics-local:latest}"
export NAMESPACE="${NAMESPACE:-metrics}"
export RELEASE_NAME="${RELEASE_NAME:-metrics-api}"

docker build -t "${LOCAL_IMAGE}" .
minikube image load "${LOCAL_IMAGE}" -p "${KUBE_CONTEXT}"
```

**Helm:** deploy the API + sidecar Redis using the same values as CI integration (`scripts/minikube-values.yaml`):

```bash
helm upgrade --install "${RELEASE_NAME}" helm/metrics-api \
  --kube-context "${KUBE_CONTEXT}" \
  --namespace "${NAMESPACE}" \
  --create-namespace \
  -f scripts/minikube-values.yaml \
  --set "image.repository=${LOCAL_IMAGE%:*}" \
  --set "image.tag=${LOCAL_IMAGE##*:}"

kubectl --context "${KUBE_CONTEXT}" -n "${NAMESPACE}" \
  rollout status deploy/"${RELEASE_NAME}-metrics-api" --timeout=180s
```

`scripts/minikube-values.yaml` sets `METRICS_PROVIDER_MODE=kueue`, in-cluster `METRICS_KUBE_API_URL`, queue list, cohort name, Redis, and RBAC as required for Kueue mode.

**Stale image on the node:** Minikube’s image store is separate from your host Docker. After `docker build` + `minikube image load`, if you keep the tag `latest`, the running Deployment may not pick up the new bits until you change the tag (for example `tag: dev-$(date +%s)` in `--set`) or run `kubectl rollout restart -n metrics deploy/metrics-api-metrics-api`. The values file uses `pullPolicy: Never` so the cluster uses the image you loaded, not a registry pull.

**Verifying the latest API behavior:** After upgrading the app image, `GET .../platform` should not include `data.sources`, and `allocated` should list the same resource keys as `capacity` (often `"0"` / `"0Gi"` when nothing is admitted). Caching is expressed via HTTP headers (`Cache-Control`, `Expires`, etc.), not JSON. If you still see `sources` or `allocated: {}` while response freshness looks wrong, you were likely hitting an **old Redis entry** from a previous release; the service bumps an internal platform cache schema so new pods ignore those keys, but you must **deploy the new image** first. To clear the sidecar Redis manually: `kubectl exec -n metrics deploy/metrics-api-redis -- redis-cli FLUSHDB` (dev only).

---

## 5. Reach the Metrics API from your machine

The Service is **ClusterIP** inside the cluster. From your laptop, forward a **local port** to the chart Service (default container port **8000**):

```bash
export KUBE_CONTEXT="${KUBE_CONTEXT:-minikube}"
export PORT_FORWARD_PORT="${PORT_FORWARD_PORT:-18080}"

kubectl --context "${KUBE_CONTEXT}" -n "${NAMESPACE}" port-forward \
  svc/"${RELEASE_NAME}-metrics-api" "${PORT_FORWARD_PORT}":8000
```

Leave that terminal open. In another shell:

```bash
curl -sS "http://127.0.0.1:${PORT_FORWARD_PORT:-18080}/healthz"
curl -sS "http://127.0.0.1:${PORT_FORWARD_PORT:-18080}/api/v1/metrics/platform" | head
```

**Environment variables for this session:**


| Variable            | Default       | Role                                                                                |
| ------------------- | ------------- | ----------------------------------------------------------------------------------- |
| `KUBE_CONTEXT`      | `minikube`    | `kubectl` / `helm` / `minikube -p` context (match `kubectl config current-context`) |
| `NAMESPACE`         | `metrics`     | Helm release namespace                                                              |
| `RELEASE_NAME`      | `metrics-api` | Helm release name (Service is `${RELEASE_NAME}-metrics-api`)                        |
| `PORT_FORWARD_PORT` | `18080`       | Local TCP port for `kubectl port-forward`                                           |


---

## 6. Teardown

Reverse the install when you are done with this stack on your **default** cluster (sections 2–5). This does **not** delete the Minikube VM/profile, stop Minikube, or remove images from your host Docker daemon.

### Stop port-forward

If a terminal is running `kubectl port-forward` for the metrics Service, stop it with **Ctrl+C** in that window. To find and stop only that forward (adjust the pattern if your release name differs):

```bash
# optional: list matching processes first
pgrep -af 'kubectl.*port-forward.*metrics-api' || true
# stop them (macOS / Linux)
pkill -f 'kubectl.*port-forward.*metrics-api' || true
```

### Automated teardown script

From the `metrics/` directory, with the same context and names you used for Helm:

```bash
export KUBE_CONTEXT="${KUBE_CONTEXT:-minikube}"
bash scripts/teardown-dev-kube-setup.sh
```

The script:

1. Uninstalls the **metrics** Helm release (`RELEASE_NAME` / `NAMESPACE`, same defaults as [section 4](#4-build-and-deploy-metrics--redis-helm)).
2. Deletes the `**metrics` namespace** by default (removes the API Deployment, Redis, and any remaining objects in that namespace).
3. Deletes the **Kueue fixtures** under `tests/fixtures/kueue/` in reverse order (ClusterQueues, then cohort, then resource flavor).
4. Uninstalls the **Kueue** Helm release in `kueue-system` (same install as [section 2](#2-install-kueue-helm)).

**Flags:** `--skip-kueue` keeps the Kueue controller installed; `--skip-fixtures` leaves the ClusterQueue / Cohort / ResourceFlavor objects; `--keep-metrics-ns` leaves the `metrics` namespace after uninstalling the chart (empty or with leftover objects you manage manually). Run `bash scripts/teardown-dev-kube-setup.sh --help` for a short usage summary.

**Environment:** `KUBE_CONTEXT`, `NAMESPACE`, `RELEASE_NAME`, `KUEUE_RELEASE_NAME`, `KUEUE_NAMESPACE`, and toggles `DELETE_METRICS_NS`, `UNINSTALL_KUEUE`, `DELETE_FIXTURES` (see the script header). Match whatever you used when installing.

**Not removed:** Minikube profile, cluster nodes, host `docker` images, and **Kueue CRDs** (cluster-scoped API extensions often remain after `helm uninstall`; that is normal).

### Manual equivalents

If you prefer not to use the script:

```bash
helm uninstall "${RELEASE_NAME:-metrics-api}" -n "${NAMESPACE:-metrics}" --kube-context "${KUBE_CONTEXT:-minikube}"
kubectl --context "${KUBE_CONTEXT:-minikube}" delete namespace "${NAMESPACE:-metrics}"   # optional
# delete fixtures (example order: cluster queues first)
kubectl --context "${KUBE_CONTEXT:-minikube}" delete -f tests/fixtures/kueue/04-clusterqueue-electron.yaml --ignore-not-found
kubectl --context "${KUBE_CONTEXT:-minikube}" delete -f tests/fixtures/kueue/03-clusterqueue-neutron.yaml --ignore-not-found
kubectl --context "${KUBE_CONTEXT:-minikube}" delete -f tests/fixtures/kueue/02-clusterqueue-proton.yaml --ignore-not-found
kubectl --context "${KUBE_CONTEXT:-minikube}" delete -f tests/fixtures/kueue/01-cohort-atom.yaml --ignore-not-found
kubectl --context "${KUBE_CONTEXT:-minikube}" delete -f tests/fixtures/kueue/00-resource-flavor.yaml --ignore-not-found
helm uninstall "${KUEUE_RELEASE_NAME:-kueue}" -n "${KUEUE_NAMESPACE:-kueue-system}" --kube-context "${KUBE_CONTEXT:-minikube}"
```

---

## 7. One-shot automation (CI-style)

`scripts/run-minikube-integration.sh` is for **CI / smoke verification**. By default it creates a **separate** Minikube profile (`metrics-local`) so teardown can delete that profile **without** touching your everyday `**minikube`** cluster. It starts the profile, installs Kueue, applies fixtures, deploys the chart, runs integration tests, and deletes the profile on exit.

For normal development on your **existing** default cluster, follow sections 1–5 above instead of this script; use [section 6](#6-teardown) to tear that stack down.

```bash
bash scripts/run-minikube-integration.sh
```

---

## 8. Troubleshooting

### Pod `CrashLoopBackOff` (API container) with `METRICS_PROVIDER_MODE=kueue`

In Kueue mode the process **must** pass `validate_kueue_mode_startup` before Uvicorn binds port 8000 and serves `/healthz`. If that fails, the container exits and Kubernetes restarts it.

The Helm chart uses a `**startupProbe`** on `/healthz` so **liveness** does not probe the port while the lifespan is still running (otherwise you can see connection refused and extra restarts even when the cluster is fine). After pulling chart changes, `**helm upgrade --install` again** so the new probes apply.

**1. Read the API logs (source of truth)**

```bash
kubectl -n metrics logs deploy/metrics-api-metrics-api --tail=200
kubectl -n metrics logs deploy/metrics-api-metrics-api --previous --tail=200   # last crashed instance
```

You should see a `**KueueStartupError**` or `httpx` error describing the failure (for example missing ClusterQueue, 403 on the Kueue API, or TLS to the Kubernetes API).

**2. Confirm fixtures exist on this cluster**

```bash
kubectl config current-context
kubectl get clusterqueues.kueue.x-k8s.io cq-proton cq-neutron cq-electron
kubectl get cohorts.kueue.x-k8s.io cohort-atom
```

If any resource is **NotFound**, re-run [section 3](#3-apply-clusterqueue--cohort-fixtures) against the **same** context (`kubectl config current-context`, typically `minikube`).

**3. Confirm the Kueue API group is reachable**

```bash
kubectl get --raw /apis/kueue.x-k8s.io/v1beta2/clusterqueues | head -c 400
```

A JSON `kind` / `items` (possibly empty) response is expected. A **404** here usually means Kueue is not installed or the aggregated API is not registered.

**4. Confirm RBAC for the metrics `ServiceAccount`**

With `serviceAccount.create: true` and `rbac.create: true` (as in `scripts/minikube-values.yaml`), the workload identity is `system:serviceaccount:metrics:metrics-api` by default. Check read access to cluster-scoped Kueue objects:

```bash
kubectl auth can-i list clusterqueues --as=system:serviceaccount:metrics:metrics-api
kubectl auth can-i get clusterqueues --as=system:serviceaccount:metrics:metrics-api
kubectl auth can-i get cohorts --as=system:serviceaccount:metrics:metrics-api
```

All three should answer `yes`. If not, re-apply the chart or inspect `helm/metrics-api/templates/rbac.yaml`.

**5. Redis**

The sidecar Redis pod is separate; if Redis were unreachable you would typically see failures on **cached** routes after startup, not necessarily a crash loop. Still verify the API env points at the in-namespace Service:

```bash
kubectl -n metrics exec deploy/metrics-api-metrics-api -- printenv METRICS_REDIS_URL
# expect: redis://metrics-api-redis:6379/0 (hostname matches the chart Service)
```

**6. Service account token**

The API must read `/var/run/secrets/kubernetes.io/serviceaccount/token` (or `METRICS_KUBE_API_TOKEN`). If `serviceAccount.create` is false and you bind RBAC to the wrong identity, or tokens are not mounted, logs will show a `**KueueStartupError`** about a missing bearer token.

---

## Related docs

- `docs/kueue-platform.md` — Kueue mode behavior and module map  
- `docs/environment-contracts.md` — `METRICS_*` and `KUEUE_METRICS_*` aliases  
- `README.md` — Compose-based **static** dev stack vs cluster-backed Kueue

