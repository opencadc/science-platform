# ADR-0023: kr8s is the Kubernetes client of choice

## Status

Accepted (2026-07-31)

## Context

The Kueue provider hand-rolled its Kubernetes access on `httpx`: bearer-token
resolution, service-account CA trust, endpoint configuration, resource URL
building, and JSON handling. Each of those was configuration surface
(`kube_api_url`, `kube_api_token`, `token_file`, `ca_file`, `kube_verify_tls`,
`kube_clusterqueue_path`, `http.*`) and a source of past incidents — notably
self-signed cluster CA handling. Upcoming scopes (interactive quota, session
and user metrics, kube-metrics reads) would each grow the same plumbing.

kr8s was validated in-cluster on 2026-07-31 (`scripts/validate-kr8s-kueue.py`,
run as `canfar-skaha-staging` in `canfar-system-staging`): zero-config
construction, TLS against the cluster CA from the mounted service account,
typed CRD LIST/GET via `new_class`, and streaming WATCH all passed. The wider
client survey lives in `docs/research/async-python-kubernetes-clients.md`
(repo root).

## Decision

- kr8s (`kr8s.asyncio`) is the standard Kubernetes client for this service,
  for the current Kueue reads and for future Kubernetes-backed scopes,
  including kube-metrics fetching.
- Endpoint, credential, and CA discovery belong to kr8s (in-cluster service
  account or kubeconfig). They are not application settings; the removed keys
  above fail validation loudly.
- Named custom-resource reads go through `api.call_api` GETs
  (``.../clusterqueues/{name}``) pinned to an explicit `apiVersion` from
  settings (`kueue_api_version`). kr8s's object helpers (`new_class` /
  `api.get`) resolve names with a LIST plus field selector, which would demand
  the `list` verb; the RBAC contract is `get`-only, so the provider must not
  use them for named reads.
- Providers own a lazily built kr8s API handle and accept an injected fake in
  tests (`tests/fakes.py::FakeKueueApi`). The kr8s HTTP session is
  process-shared, so provider shutdown releases the handle without closing
  transport. This supersedes the injected-`httpx.AsyncClient` ownership model
  recorded in ADR-0005; its network-free construction, active-provider-only,
  and fail-fast decisions remain in force.
- Error sanitization (ADR-0013) is unchanged: kr8s/transport failures map to
  the same sanitized provider errors, and fingerprints stay secret-free
  (provider name, `kueue_api_version`, sorted queues).

## Consequences

- `metrics.providers.kueue` carries no token/TLS/URL code; new scopes reuse
  the same client pattern instead of new transport settings.
- kr8s is effectively a single-maintainer project: keep provider seams thin
  (typed reads behind provider methods) so the client stays swappable, and
  revisit if the official `kubernetes` client ships stable asyncio support.
- Deployment charts no longer set Kubernetes endpoint or TLS values; RBAC and
  the mounted service account are the whole contract.

## References

- [`0005-runtime-composition-and-provider-lifecycle.md`](0005-runtime-composition-and-provider-lifecycle.md)
- `scripts/validate-kr8s-kueue.py` (in-cluster validation workflow)
- `docs/research/async-python-kubernetes-clients.md` (repo root)
