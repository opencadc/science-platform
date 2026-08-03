# Skaha

Skaha is the CANFAR session API. It exposes session lifecycle operations and,
for clients such as Science Portal, a legacy **platform stats** view at
`GET /v1/session?view=stats` (no session ID).

Shared cross-context vocabulary: [`../CONTEXT-MAP.md`](../CONTEXT-MAP.md).

## Language

**Platform stats**: JSON from `GET /v1/session?view=stats` (no session ID).
Cluster-wide CPU/RAM figures plus per-session resource ceilings. _Avoid_: "stats
view", "system stats" (unless referring to the schema name).

**Session resource ceiling**: Maximum CPU and memory a single user session pod
may request (per-pod limit). Not cluster totals. _Avoid_: "max available",
"node max".

**Resource context file**: Static `k8s-resources.json` mounted at `/config`;
defines launch-form options and default limits when LimitRange is disabled.
_Avoid_: "k8s config" (ambiguous).

**Metrics backend**: The co-deployed Metrics API pod Skaha calls in-cluster
(`SKAHA_METRICS_BACKEND_URL`). _Avoid_: "metrics pod" in specs (implementation
detail).

**MetricsDAO**: The single Skaha seam for all metrics access — **platform
stats** capacity/allocation and session-list pod usage. Callers use
`MetricsDAO` only; platform HTTP and pod-usage source selection are internal.
_Avoid_: `SkahaMetricsDAO`, `PlatformMetricsDAO`, `PodMetricsDAO` in
session-layer specs (implementation details).

**Platform capacity** (consumer view): Cluster CPU/memory totals Skaha reads
from the Metrics backend (`data.capacity`). _Avoid_: "available" alone (legacy
field names overload this term).

**Platform allocation** (consumer view): Cluster CPU/memory already allocated,
read from Metrics `data.allocated`. _Avoid_: "requested" alone when meaning
cluster totals (legacy Skaha used "requested" for pod sums).

**Interactive workload labels**: Pods counted for Metrics interactive quota must
carry user, allocation class (`fixed` / `flexible`), and exactly one configured
session-type label. Label keys are configured in Metrics; values are set at
session launch. See
[system ADR-0004](../docs/adr/0004-interactive-workload-pod-label-contract.md).

## Relationships

- **Platform stats** combines **platform capacity** and **platform allocation**
  (from **Metrics backend** via **MetricsDAO**) with **session resource
  ceiling** (from LimitRange or **resource context file**).
- Session-list pod usage (`cpuCoresInUse`, `memoryInUse`) is fetched through
  **MetricsDAO**; today from the Kubernetes metrics API, future from the
  **Metrics backend**.
- **Session resource ceiling** is independent of **platform capacity**.

## Example dialogue

> **Dev:** "What does `cpuCoresAvailable` mean after the Metrics change?"
> **Domain expert:** "That's **platform capacity** for CPU — the cluster total
> from Metrics, not free cores and not the per-session max."

> **Dev:** "Where do `maxCPUCores` and `maxRAM` come from when LimitRange is
> off?"
> **Domain expert:** "From the **resource context file** `defaultLimit` values
> (8 cores, 32 GiB in the shipped chart), not from node size."

## Flagged ambiguities

- Legacy Skaha fields `requestedCPUCores` / `requestedRAM` in platform stats
  carry **platform allocation** semantics; names stay for API compatibility.
- If Skaha cannot reach the **Metrics backend**, only **platform stats** fails
  (HTTP 503); other session endpoints continue.
- Skaha does not cache Metrics responses; each **platform stats** request calls
  the Metrics backend (Metrics owns caching).
- If **session resource ceiling** cannot be resolved, **platform stats** returns
  HTTP 503 with a short stable client message; detailed diagnostics belong in
  server logs only.
- On successful **platform stats**, `lastUpdate` reflects the Metrics snapshot
  time (`metadata.created`), not when Skaha assembled the response.
- Session-list pod usage is temporarily sourced from the Kubernetes metrics API
  inside **MetricsDAO**; when the Metrics backend exposes pod usage,
  **MetricsDAO** will switch via configuration without changing session handler
  call sites.
