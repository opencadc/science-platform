# Science Platform (Skaha & Metrics)

Skaha is the session API for CANFAR; the Metrics service exposes cluster-wide capacity and allocation derived from Kueue. Skaha can expose a legacy platform-stats view for clients such as Science Portal.

## Language

**Platform stats**: The JSON returned by `GET /v1/session?view=stats` (no session ID). Describes cluster-wide CPU/RAM figures and per-session resource ceilings. _Avoid_: "stats view", "system stats" (unless referring to the schema name).

**Platform capacity**: Total CPU and memory available across the cluster for scheduling (Kueue-backed). Sourced from Metrics `data.capacity`. _Avoid_: "available" alone (legacy Skaha field names overload this term).

**Platform allocation**: CPU and memory already allocated on the cluster (Kueue-backed). Sourced from Metrics `data.allocated`. _Avoid_: "requested" alone when meaning cluster totals (legacy Skaha used "requested" for pod sums).

**Session resource ceiling**: Maximum CPU and memory a single user session pod may request (per-pod limit). Not cluster totals. _Avoid_: "max available", "node max".

**Metrics backend**: The co-deployed Metrics API pod Skaha calls in-cluster (`SKAHA_METRICS_BACKEND_URL`). _Avoid_: "metrics pod" in specs (implementation detail).

**Resource context file**: Static `k8s-resources.json` mounted at `/config`; defines launch-form options and default limits when LimitRange is disabled. _Avoid_: "k8s config" (ambiguous).

## Relationships

- **Platform stats** combines **platform capacity** and **platform allocation** (from **Metrics backend**) with **session resource ceiling** (from LimitRange or **resource context file**).
- **Session resource ceiling** is independent of **platform capacity**; a session may request up to the ceiling while the cluster may have more or less capacity remaining.

## Example dialogue

> **Dev:** "What does `cpuCoresAvailable` mean after the Metrics change?"
> **Domain expert:** "That's **platform capacity** for CPU — the cluster total from Metrics, not free cores and not the per-session max."

> **Dev:** "Where do `maxCPUCores` and `maxRAM` come from when LimitRange is off?"
> **Domain expert:** "From the **resource context file** `defaultLimit` values (8 cores, 32 GiB in the shipped chart), not from node size."

## Flagged ambiguities

- Legacy Skaha fields `requestedCPUCores` / `requestedRAM` in platform stats will carry **platform allocation** semantics after migration; names stay for API compatibility.

- **Metrics backend** is always enabled in deployment; if Skaha cannot reach it, only **platform stats** fails (HTTP 503); other Skaha endpoints continue.

- Skaha does not cache Metrics responses; each **platform stats** request calls the Metrics backend (Metrics owns caching).

- If **session resource ceiling** cannot be resolved (Metrics unreachable, or LimitRange enabled but missing), **platform stats** returns HTTP 503 with a short stable client message; detailed diagnostics belong in server logs only.

- On successful **platform stats**, `lastUpdate` reflects the Metrics snapshot time (`metadata.created`), not when Skaha assembled the response.

- **Platform stats** tests: unit coverage for mapping and 503 paths; dedicated integration test with stub Metrics; session lifecycle tests must not depend on Metrics for unrelated session flows.
