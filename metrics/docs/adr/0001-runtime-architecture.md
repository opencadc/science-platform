# ADR-0001: Runtime architecture — Kubernetes-first, env-only config, kr8s, quantiphy

## Status

Accepted (last amended 2026-07-31). Consolidates former ADRs 0001, 0005
(itself 0005/0009/0011/0012/0022), 0010, 0023, and 0024; originals in git
history.

## Context

Metrics must run identically in dev (kind), CI, and deployed clusters, with
startup surfaces that match what operators enable. Earlier iterations carried
hand-rolled Kubernetes transport, a YAML config source, a provider registry,
and bespoke Decimal quantity parsing that nothing deployed needed.

## Decision

- **Kubernetes-first everywhere.** No Docker Compose. `dev` = kind + Helm +
  `kubectl` with Kueue fixtures (`scripts/kind-smoke.sh`); higher environments
  are Helm deploys onto operating clusters.
- **Environment-only configuration.** Defaults, then `METRICS_*` env vars
  (nested with `__`; list-like values are JSON array strings), then Kubernetes
  secret files. No file-based config source; secrets never live in ConfigMaps.
- **kr8s is the Kubernetes client** — for Kueue reads and every future
  Kubernetes-backed scope (including kube-metrics). Chosen over
  `kubernetes`/`kubernetes_asyncio`/`lightkube` for asyncio-native,
  zero-config in-cluster discovery (endpoint, token, self-signed CA — verified
  in-cluster via `scripts/validate-kr8s-kueue.py`). Endpoint/credential/CA
  settings do not exist; removed keys fail validation loudly.
  - Named reads use `api.call_api` GETs (`.../clusterqueues/{name}`) pinned to
    `kueue_api_version`. kr8s object helpers resolve names with LIST + field
    selector, which needs the `list` verb — RBAC is `get`-only, so helpers are
    not used for named reads.
  - kr8s is near single-maintainer: keep reads behind provider methods so the
    client stays swappable; revisit if the official client ships stable asyncio.
- **Quantities parse via quantiphy** (`Quantity(raw, binary=True)`), not a
  bespoke model. Floats; the contract is the 6-decimal API formatting, not
  bit-exactness. Units: CPU cores, storage Gi, base units otherwise. Guards
  stay fail-closed (non-string, whitespace, negative, non-finite, ≥2^63 base
  units). quantiphy accepts slightly more grammar than Kubernetes; inputs come
  from the API server, which enforces its own syntax.
- **Composition and lifecycle.** `MetricsRuntime.from_settings` constructs the
  Kueue provider directly (`sources.platform` is `Literal["kueue"]`; a
  registry earned nothing — a new provider extends the Literal and branches
  here). One complete provider per scope; the runtime never stitches partial
  results. Provider constructors are synchronous and network-free; the
  provider owns a lazily built kr8s handle (tests inject `FakeKueueApi`);
  the kr8s session is process-shared, so shutdown releases the handle only.
- **Fail-fast startup.** Lifespan `startup()` validates each configured
  ClusterQueue; misconfiguration or unreachable upstream refuses to serve —
  no fallback, no partial data. Cache fingerprints cover provider identity
  (name, API version, sorted queues) and exclude secrets and transport.

## Consequences

- Operators detect bad queue lists or RBAC at deploy time; GitOps values
  render env vars and nothing else.
- Adding a provider or scope means `sources.*` wiring, provider method,
  startup checks, schemas, and tests together — before routes expose it.
- Dependency footprint stays small (kr8s, quantiphy; no pyyaml, no official
  kubernetes client).

## References

- [`../environment-contracts.md`](../environment-contracts.md)
- [`../architecture.md`](../architecture.md)
- `scripts/validate-kr8s-kueue.py`
