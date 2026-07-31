# Async Python Kubernetes clients for CRD-centric services

**Status:** Research report, for decision
**Date:** 2026-07-31
**Scope:** cross-cutting — `skaha`, future session operators/CRDs
**Hard requirements evaluated:** (1) asyncio-native or first-class async support, (2) good CRD support — CRUD *and* watch on custom resources, (3) actively maintained with a healthy ecosystem.
**Evidence note:** every health figure (stars, downloads, release dates, commit counts, contributor counts) was pulled live on **2026-07-31** from the GitHub REST API, the PyPI JSON API (`pypi.org/pypi/<pkg>/json`), and [pypistats.org](https://pypistats.org). API-surface claims were verified against the projects' own docs/repos — including unpacking the released `kubernetes==36.0.3` wheel to check what actually ships. No third-party comparison articles were used. All of this goes stale; re-check before acting on it in 2027.

---

## 1. TL;DR and recommendation

**For an operator/controller that owns a session CRD: use [kopf](https://github.com/nolar/kopf) as the reconcile framework, with [kr8s](https://github.com/kr8s-org/kr8s) for imperative API calls inside handlers.** Both are asyncio-native, both are actively maintained, and the combination is production-precedented: `dask-kubernetes` (the Dask operator) depends on exactly `kopf` + `kr8s` [per its `pyproject.toml`](https://github.com/dask/dask-kubernetes/blob/main/pyproject.toml). kopf takes the hard parts of long-running CR watches off your plate (watch reconnects, resourceVersion bookkeeping, retries, multi-instance peering) — which is precisely the part of a hand-rolled watch loop that rots in production.

**For a plain async service that CRUDs and watches CRs without being an operator** (e.g. Skaha itself creating session CRs and tailing their status):

- **Primary: [kr8s](https://docs.kr8s.org)** — cleanest CRD ergonomics of the field (`new_class` one-liner, then normal `.create()/.patch()/.delete()` and `api.async_watch(...)`), asyncio *and* trio, sync twin API for scripts/tests. Main caveats: still 0.x, effectively one lead maintainer, and no tagged release since Jan 2026 (though commits are current to this week).
- **Safest fallback: [kubernetes_asyncio](https://github.com/tomplus/kubernetes_asyncio)** — the asyncio port of the official generated client. Least pretty (generated code, dict-shaped custom objects), but at full parity with upstream K8s 1.36, battle-tested watch/exec/dynamic-client, and 20.7M downloads/month. Pick it when you want the lowest-level, most spec-complete surface or when you distrust 0.x dependencies.
- **[lightkube](https://lightkube.readthedocs.io)** is a legitimate third option if you value typed models and mypy strictness; it is the backbone of Canonical's charm ecosystem. Smaller community, single maintainer.

**Do not build new async code on the official `kubernetes` client yet.** Its asyncio support (`kubernetes.aio`) only appeared in v36 as *experimental/alpha*, and the released 36.0.3 wheel ships **no async watch, stream, or dynamic client** — those exist only on master (verified by unpacking the wheel; the [changelog](https://github.com/kubernetes-client/python/blob/master/CHANGELOG.md) says so explicitly). Its legacy `async_req=True` is a thread pool, not asyncio. **Revisit in 1–2 releases**: once `kubernetes.aio.watch` ships and stabilizes, the official client's unmatched institutional health (CNCF org, 249 contributors, 110M downloads/month) will likely make it the default answer, and a later migration from `kubernetes_asyncio` to it would be nearly mechanical (same generated API shape).

**Ruled out:** `pykube-ng` (sync-only, dormant since June 2023, moved off GitHub) and `aiokubernetes` (abandoned 2018; its author now contributes to `kubernetes_asyncio`).

---

## 2. Comparison table

Health figures as of **2026-07-31**.

| | [kubernetes](https://github.com/kubernetes-client/python) (official) | [kubernetes_asyncio](https://github.com/tomplus/kubernetes_asyncio) | [kr8s](https://github.com/kr8s-org/kr8s) | [lightkube](https://github.com/gtsystem/lightkube) | [kopf](https://github.com/nolar/kopf) | [pykube-ng](https://codeberg.org/hjacobs/pykube-ng) | [aiokubernetes](https://github.com/olitheolix/aiokubernetes) |
|---|---|---|---|---|---|---|---|
| Kind | generated client | generated client | hand-written client | hand-written client + generated models | operator framework | hand-written client | generated client |
| Async model | thread-pool `async_req`; asyncio (`kubernetes.aio`) **experimental, no watch in release** | asyncio-native (aiohttp) | asyncio + **trio**; sync twin API | sync `Client` + asyncio `AsyncClient` (httpx) | asyncio-native framework; sync handlers run in executor | sync only | asyncio (dead) |
| CR CRUD | `CustomObjectsApi` / dynamic client (sync) | `CustomObjectsApi` + `DynamicClient` | `new_class()` → full `APIObject` methods | `create_namespaced_resource()` → typed-ish generic class | declarative handlers on any group/kind | `object_factory` (sync) | — |
| CR watch (async) | **not in any release** | `watch.Watch().stream(...)` | `api.async_watch(kind)` | `async for op, obj in client.watch(R)` | framework-managed (reconnects, resourceVersion, retries) | no | — |
| Latest release | [36.0.3](https://pypi.org/project/kubernetes/), 2026-07-13 | [36.1.0](https://pypi.org/project/kubernetes-asyncio/), 2026-06-04 | [0.20.15](https://pypi.org/project/kr8s/), 2026-01-16 | [0.22.0](https://pypi.org/project/lightkube/), 2026-07-09 | [1.44.6](https://pypi.org/project/kopf/), 2026-06-03 | [23.6.0](https://pypi.org/project/pykube-ng/), **2023-06-16** | 0.6, **2018-07-22** |
| Commits, last 12 mo | 337 | 36 | 74 (pushed 2026-07-31) | 85 | 301 | ~0 (last activity 2023) | 0 (last push 2018) |
| Contributors | 249 | 51 (tomplus: 299 of ~450 commits) | 28 (jacobtomlinson: 358) | 25 (gtsystem: 222) | 74 (nolar: 1,576) | — | — |
| Backing | kubernetes-client org / SIG API Machinery | single maintainer | kr8s-org; Dask ecosystem dependent | single maintainer; heavy Canonical usage (1,102 code hits in `org:canonical`) | single maintainer | dormant | abandoned |
| GitHub stars | 7,632 | 436 | 961 | 138 | 2,623 | (moved to Codeberg) | 24 |
| PyPI downloads/mo | 110.4M | 20.7M | 875k | 392k | 1.66M | 241k | 67 |
| Open issues | 95 | 33 | 43 | 8 | 181 | 10 | — |
| Python | ≥3.10 | ≥3.10 | ≥3.9 (3.9–3.14 badge) | ≥3.8 | ≥3.10 | ≥3.8,<4 | unmaintained |
| Meets all 3 requirements? | **No** (async watch unreleased) | **Yes** | **Yes** (0.x caveat) | **Yes** (bus-factor caveat) | **Yes** (operator use case only) | No | No |

Download counts are [pypistats.org](https://pypistats.org/api/) "last_month" figures; note the official client's number is inflated by CI/transitive installs everywhere, and `kubernetes_asyncio`'s by its use inside large platforms — treat them as order-of-magnitude adoption signals, not head-to-head popularity.

---

## 3. Candidates in detail

### 3.1 `kubernetes` (official, kubernetes-client/python)

- **Repo:** <https://github.com/kubernetes-client/python> — owned by the kubernetes-client GitHub org under Kubernetes SIG API Machinery. Apache-2.0.
- **Async model — the load-bearing finding of this report.** Two mechanisms exist:
  1. The long-standing `async_req=True` parameter on every generated method. This is **not asyncio** — it dispatches to a `multiprocessing.pool` thread and returns a thread handle (still present in the released 36.0.3 `CustomObjectsApi`, verified in the wheel).
  2. New in the v36 line: an experimental `kubernetes.aio` subpackage (aiohttp transport, modern OpenAPI generator). The [changelog](https://github.com/kubernetes-client/python/blob/master/CHANGELOG.md) is explicit: *"Asyncio package is experimental currently, breaking changes may be introduced in future releases"* and *"Dynamic client, watch, stream, shared informer, leader election are not yet supported in asyncio."* I unpacked the released `kubernetes-36.0.3` wheel: `kubernetes/aio/` ships **only** `client` and `config` (855 files of generated models/APIs) — **no `watch`, `stream`, `dynamic`, `informer`, or `leaderelection`**. Those directories exist on master (unreleased). The [`setup-asyncio.py`](https://github.com/kubernetes-client/python/blob/master/setup-asyncio.py) on master carries `DEVELOPMENT_STATUS = "3 - Alpha"`.
- **CRD support:** first-class in the *sync* client: `CustomObjectsApi` (typed-ish, dict bodies) and `kubernetes.dynamic.DynamicClient`; sync watch via `kubernetes.watch.Watch`. The canonical sync CR path:

  ```python
  from kubernetes import client, config, watch
  config.load_kube_config()
  api = client.CustomObjectsApi()
  api.create_namespaced_custom_object(
      group="canfar.net", version="v1alpha1",
      namespace="skaha-workload", plural="sessions", body=manifest)
  w = watch.Watch()
  for event in w.stream(api.list_namespaced_custom_object,
                        "canfar.net", "v1alpha1", "skaha-workload", "sessions"):
      print(event["type"], event["object"]["metadata"]["name"])
  ```

  But no equivalent async watch exists **in any released version**.
- **Health:** the best of the field. v36.0.3 released 2026-07-13; 337 commits in the last 12 months; 249 contributors; 7,632 stars; 110.4M downloads/month; 95 open issues (low for the size). [Compatibility policy](https://github.com/kubernetes-client/python#compatibility): client 36.y.z ↔ Kubernetes 1.36, three GA minor versions supported concurrently. Python ≥3.10.
- **Gaps/risks:** fails requirement (1) today for watch-driven services. Additionally, v36 was a disruptive release — the OpenAPI generator jumped from `python-legacy` to modern (pydantic v2 models, changed `ApiClient.call_api` internals), so downstream code that touched client internals broke. Expect some churn while the aio surface stabilizes.
- **Verdict:** disqualified *for now* by the missing released async watch — but this is the client to re-evaluate every release, and choosing `kubernetes_asyncio` today keeps a cheap migration path to it.

### 3.2 `kubernetes_asyncio` (tomplus/kubernetes_asyncio)

- **Repo:** <https://github.com/tomplus/kubernetes_asyncio> · [PyPI `kubernetes-asyncio`](https://pypi.org/project/kubernetes-asyncio/) · [docs](https://kubernetes-asyncio.readthedocs.io). Apache-2.0.
- **Async model:** asyncio-native from the ground up — the official client's code generator run with the asyncio/aiohttp templates. Same API/model names as the official client. The project exists because upstream [rejected/deferred asyncio](https://github.com/kubernetes-client/python/pull/324) years ago. Python ≥3.10.
- **CRD support:** identical surface to the official client, but awaitable: generated `CustomObjectsApi`, a full async `DynamicClient`, and `watch.Watch` with `async for`. The repo ships CR examples ([`examples/dynamic-client/namespaced_custom_resource.py`](https://github.com/tomplus/kubernetes_asyncio/blob/master/examples/dynamic-client/namespaced_custom_resource.py) covers CRD creation, CR CRUD via dynamic client). Watch, condensed from [`examples/watch_namespaces.py`](https://github.com/tomplus/kubernetes_asyncio/blob/master/examples/watch_namespaces.py) and the dynamic-client pattern:

  ```python
  from kubernetes_asyncio import client, config, watch
  await config.load_kube_config()
  api = client.CustomObjectsApi()
  w = watch.Watch()
  async for event in w.stream(
          api.list_namespaced_custom_object,
          "canfar.net", "v1alpha1", "skaha-workload", "sessions"):
      print(event["type"], event["object"]["metadata"]["name"])
  await w.close()
  ```

  Websocket exec (`pod_exec.py`) and leader election examples also ship. Exec credential plugins are supported (the README's one caveat is Windows event-loop selection for subprocess-based credential plugins — irrelevant for us).
- **Health:** 36.1.0 released 2026-06-04 — **at parity with upstream K8s 1.36**, so the historical "async fork lags upstream" concern does not currently apply (versioning mirrors the official client's scheme, [per the README](https://github.com/tomplus/kubernetes_asyncio#versions)). 20.7M downloads/month (2nd only to the official client), 51 contributors, 436 stars, 33 open issues, 74 PyPI releases. 36 commits in 12 months — low, but this is a generated client: most "commits" are regeneration + dependency bumps, and cadence has tracked upstream releases for 8+ years.
- **Gaps/risks:** effectively a **single-maintainer project** — tomplus has 299 commits vs. 49 for dependabot and 20 for the next human. Generated-code ergonomics: custom objects are plain dicts, six-argument method signatures, no typing on CR payloads. Watch restart logic (410 Gone handling, resourceVersion bookkeeping) is on you.
- **Verdict:** meets all three requirements. The conservative choice; the one to pick when API-surface completeness and install-base trump ergonomics.

### 3.3 `kr8s` (kr8s-org/kr8s)

- **Repo:** <https://github.com/kr8s-org/kr8s> · [docs](https://docs.kr8s.org) · [PyPI](https://pypi.org/project/kr8s/). BSD-3-Clause.
- **Async model:** dual API — `kr8s` (sync) and `kr8s.asyncio` (async) with identical method names; the async API works with **asyncio and trio** ([README](https://github.com/kr8s-org/kr8s)). httpx transport. Python 3.9–3.14.
- **CRD support:** the best ergonomics in the field. A custom resource class is [one factory call](https://docs.kr8s.org/en/stable/object.html), after which it behaves exactly like a built-in kind — and the client will even auto-generate classes for unknown kinds it encounters:

  ```python
  from kr8s.asyncio.objects import new_class

  Session = new_class(kind="Session", version="canfar.net/v1alpha1", namespaced=True)

  sess = Session({"metadata": {"name": "notebook-abc"},
                  "spec": {"image": "...", "type": "notebook"}})
  await sess.create()
  await sess.patch({"spec": {"expiry": "4h"}})
  await sess.refresh(); await sess.delete()
  ```

  Watching is a single generator ([`Api.async_watch`](https://docs.kr8s.org/en/stable/autoapi/kr8s/index.html) — `kind`, `namespace`, `label_selector`, `field_selector`, `since`):

  ```python
  import kr8s.asyncio
  api = await kr8s.asyncio.api()
  async for op, obj in api.async_watch("sessions", namespace="skaha-workload"):
      print(op, obj.name)   # op ∈ ADDED / MODIFIED / DELETED
  ```

  Subclassing `new_class` output to add domain methods (e.g. `scalable=True`) is supported and is exactly the shape a Skaha session abstraction wants.
- **Health:** 961 stars, 875k downloads/month, 28 contributors, 43 open issues; repo pushed the day of this survey (2026-07-31), 74 commits in 12 months. Governance is a dedicated org (`kr8s-org`) rather than a personal repo; lead is [jacobtomlinson](https://github.com/jacobtomlinson) (Dask core, dask-kubernetes author). Strongest adoption proof: **`dask-kubernetes` runs its production operator on `kopf` + `kr8s`** ([`pyproject.toml`](https://github.com/dask/dask-kubernetes/blob/main/pyproject.toml): `"kopf>=1.38.0"`, `"kr8s==0.20.*"`), and the kr8s docs include a [guide for building kopf-based operators](https://docs.kr8s.org/en/stable/).
- **Gaps/risks:** still **0.x** — dask-kubernetes pins `kr8s==0.20.*`, which is both an endorsement and a warning that minor versions can break. Last *tagged release* was v0.20.15 on 2026-01-16, a 6-month release gap despite active commits. Contribution is dominated by one person (358 commits; next human contributor: 4). No typed models — objects are dict-backed with attribute conveniences.
- **Verdict:** meets all three requirements; the recommended client for CR-centric application code, with the 0.x pin discipline dask-kubernetes demonstrates.

### 3.4 `lightkube` (gtsystem/lightkube)

- **Repo:** <https://github.com/gtsystem/lightkube> · [docs](https://lightkube.readthedocs.io) · [PyPI](https://pypi.org/project/lightkube/). MIT.
- **Async model:** sync `Client` and asyncio `AsyncClient` with identical method surfaces (httpx underneath); async watch is a native async generator ([docs front page](https://lightkube.readthedocs.io/en/stable/)). Python ≥3.8.
- **CRD support:** via [generic resources](https://lightkube.readthedocs.io/en/stable/generic-resources/) — factory functions produce classes usable with every client verb, including Status/Scale subresources, and a helper can auto-register every CRD present in the cluster:

  ```python
  from lightkube import AsyncClient
  from lightkube.generic_resource import create_namespaced_resource

  Session = create_namespaced_resource(
      group="canfar.net", version="v1alpha1", kind="Session", plural="sessions")

  client = AsyncClient()
  await client.create(Session(metadata={"name": "notebook-abc"}, spec={...}))
  obj = await client.get(Session, name="notebook-abc", namespace="skaha-workload")

  async for op, sess in client.watch(Session, namespace="skaha-workload"):
      print(op, sess.metadata.name)
  ```

  Built-in kinds use generated dataclass models (`lightkube-models`, published per K8s version, currently spanning 1.20–1.35 [per the docs](https://lightkube.readthedocs.io/en/stable/)) with full type hints — the only candidate offering real static typing. Server-side apply is supported.
- **Health:** v0.22.0 released 2026-07-09; 85 commits in 12 months; 392k downloads/month; 25 contributors; only 8 open issues (responsive). Its ecosystem niche is **Canonical's Juju charm world**: a GitHub code search for `lightkube` within `org:canonical` returns **1,102 hits** — it is the de facto K8s client for charm operators, which guarantees a large indirect user base and steady bugfix pressure.
- **Gaps/risks:** single maintainer (gtsystem: 222 commits; next: 32) with no org umbrella; 138 stars understates real usage but reflects a small direct community. Models package trails upstream by a minor version (1.35 vs 1.36) — rarely matters for CRD work, which bypasses generated models entirely. No trio support. No exec-into-pod websocket support in the same league as the generated clients (check before depending on exec).
- **Verdict:** meets all three requirements. Choose it over kr8s when typed models and mypy-strict code matter more than CR ergonomics and kubectl-style conveniences.

### 3.5 `kopf` (nolar/kopf) — operator framework, not a general client

- **Repo:** <https://github.com/nolar/kopf> · [docs](https://docs.kopf.dev) · [PyPI](https://pypi.org/project/kopf/). MIT. Python ≥3.10.
- **What it is:** a declarative **operator framework**: you register handlers on resource lifecycle events and kopf owns the watch machinery. It is asyncio-based internally (aiohttp; handlers may be `async def`, or sync functions executed in a thread pool). It is *not* a general-purpose client — for imperative calls inside handlers you bring a client (kr8s, lightkube, kubernetes_asyncio all work; kopf itself performs its own API calls for the machinery).

  ```python
  import kopf

  @kopf.on.create("canfar.net", "v1alpha1", "sessions")
  async def on_session_create(spec, name, namespace, patch, **_):
      # launch the actual workload for this Session CR
      patch.status["phase"] = "Provisioning"

  @kopf.timer("canfar.net", "v1alpha1", "sessions", interval=60)
  async def expire_check(spec, status, **_):
      ...
  ```

- **Why it matters for the CRD requirement:** the hardest production problem with CRs is not CRUD, it is *correct long-running watches* — reconnects, 410 Gone, resourceVersion tracking, retries with backoff, idempotent re-delivery, running two replicas without double-handling. kopf ships all of it ([docs](https://docs.kopf.dev/en/stable/)): timers, per-resource daemons, in-memory indexing, admission webhooks (validating + mutating), and peering for multi-instance coordination. Hand-rolling this on any raw client is the alternative, and it is the part teams get wrong.
- **Health:** 1.44.6 released 2026-06-03; 301 commits in 12 months; 2,623 stars; 1.66M downloads/month; 74 contributors. Origin: built at Zalando, forked to `nolar/kopf` when the author left; the original author remains the overwhelmingly dominant committer (1,576 vs 34 for the next contributor).
- **Gaps/risks:** the clearest **bus-factor-1** project in this list, and 181 open issues indicates triage lag. Mitigation: dask-kubernetes and a large operator ecosystem depend on it, so a community fork on abandonment is plausible; and kopf code is decorator-declarative, so the coupling surface of our code to the framework is narrow.
- **Verdict:** the right tool *iff* we build a session operator — which is the stated direction. Pair with kr8s (the combination dask-kubernetes validates in production).

### 3.6 `pykube-ng` — ruled out

[Codeberg repo](https://codeberg.org/hjacobs/pykube-ng) (moved off GitHub in 2020; the original `kelproject/pykube` is archived) · [PyPI](https://pypi.org/project/pykube-ng/). Last release **23.6.0 on 2023-06-16**; no meaningful activity since. Sync-only (requests-based); CRs via an `object_factory` helper; no async story at all. The 241k downloads/month are legacy install-base (it remains a dependency of the *classic*, pre-operator dask-kubernetes path, per the same `pyproject.toml`). Fails requirements (1) and (3).

### 3.7 `aiokubernetes` — ruled out

[GitHub](https://github.com/olitheolix/aiokubernetes) · [PyPI](https://pypi.org/project/aiokubernetes/). Last release 0.6 on **2018-07-22**; last repo push **2018-10-22**; 67 downloads/month; 24 stars. Historically interesting only: its author (olitheolix) is the #3 contributor to `kubernetes_asyncio`, which absorbed this niche. Dead.

### 3.8 Also considered, not shortlisted

`pykorm` (ORM-style CR wrapper, tiny/inactive), `hikaru` (model/manipulation library, not a watch-capable client), and the various per-project vendored clients were checked and dropped — none clears both the async-watch bar and the maintenance bar. No other credible general-purpose candidate surfaced.

---

## 4. Decision framing for CANFAR

1. **If the session-CRD operator goes ahead** (the modernization direction): **kopf + kr8s**. kopf owns reconcile/watch/peering; kr8s's `new_class("Session", ...)` gives Skaha and the operator a shared, pleasant CR type. Pin `kr8s` to a minor series exactly as dask-kubernetes does (`kr8s==0.20.*`) and treat kr8s minor bumps as reviewed upgrades.
2. **If we only need a client** (Skaha creates/watches CRs, no operator): kr8s primary; `kubernetes_asyncio` if the team prefers zero-surprise, spec-complete generated APIs or wants a friction-free future migration to the official client.
3. **Standing item:** watch the official client's `kubernetes.aio` — when async watch/dynamic-client ship in a stable release (master already has the code), re-run this evaluation; institutional backing there beats every alternative.
4. **Risk register:** three of the four viable projects (`kubernetes_asyncio`, `lightkube`, `kopf`) are bus-factor-1, and kr8s is close. This is the structural reality of the Python K8s ecosystem outside the official client. It argues for (a) thin adapter layers around whichever client we pick, and (b) preferring dict-shaped CR access (portable across all four) over deep coupling to any client's object model.
