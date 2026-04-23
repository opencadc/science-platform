# Minikube: Metrics API development and testing

Single entry for the full stack: **`bash scripts/minikube-smoke.sh`**. It starts Minikube (local only) when the profile is not already running, installs Kueue (upstream Helm chart), applies **`scripts/test-setup.yaml`** (Kueue smoke objects + sample workload), deploys with **`skaffold build`** and **`helm upgrade`** (same one-shot flow as CI), waits for admission, runs integration tests.

**Prerequisites:** Docker, Minikube, kubectl, Helm, Skaffold (≥ 2.16 locally; CI pins 2.18.x). Working directory: **`metrics/`**.

**Environment:** `KUBE_CONTEXT` / `MINIKUBE_PROFILE` default to `minikube`. `METRICS_CLUSTER_NAME` in `scripts/minikube-values.yaml` matches. For **CI**, the workflow sets `MINIKUBE_SMOKE_CI=1` and runs the same script after the cluster and image preloads.

**Debug-only manual steps:** with a running cluster, you can mirror the script (Helm for Kueue, `kubectl apply -f scripts/test-setup.yaml`, then `skaffold build -p minikube` and `helm upgrade` as in `minikube-smoke.sh`). The supported path is the single script.

**Contract names** in `scripts/test-setup.yaml` and `scripts/minikube-values.yaml`: `default-flavor`, `cohort-atom`, `cq-proton`, `cq-neutron`, `cq-electron`, `lq-smoke`, `integration-idle`. Sample workload requests: `100m` CPU, `100Mi` memory.

**Teardown:** **`bash scripts/minikube-smoke-teardown.sh`** ends the port-forward (default, non-destructive). Add **`--all`** to run **`scripts/teardown-dev-kube-setup.sh`**, and **`--minikube`** to delete the Minikube profile. Kubernetes-only cleanup: **`bash scripts/teardown-dev-kube-setup.sh`**

**Troubleshooting**

- **`ErrImageNeverPull` / image missing on node:** the chart uses `imagePullPolicy: Never`. The image must be built in Minikube’s Docker (see `minikube-smoke.sh`, which runs Skaffold after `eval $(minikube docker-env)`). If you built only on the host, load the same tag:
  `docker build -t canfar-metrics-local:TAG .` then
  `minikube image load canfar-metrics-local:TAG -p minikube` then
  `kubectl -n metrics rollout restart deploy/metrics-api-metrics-api`.
- API fails startup: `kubectl -n metrics logs deploy/metrics-api-metrics-api --tail=200`
- Empty ClusterQueues: re-run `bash scripts/minikube-smoke.sh` or re-apply `scripts/test-setup.yaml` after Kueue is healthy
- Stale image with `pullPolicy: Never`: use Skaffold builds (unique tags) or unique tags + `minikube image rm`
- Cache: short TTL in minikube values; `kubectl exec -n metrics deploy/metrics-api-redis -- redis-cli FLUSHDB`

**See also:** `docs/kueue-platform.md`, `docs/environment-contracts.md`, `README.md`
