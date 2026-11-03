# Skaha Community-aware session routing

Accepted design, 2026-09-10. Implementation is tracked by
[CADC-15529](https://herzberg.atlassian.net/browse/CADC-15529). This document
specifies the target behavior; it does not claim that the current code or a
deployment implements it. The latest confirmations supersede earlier drafts.

## Problem Statement

Skaha currently allows Jobs without Kueue and selects static LocalQueues by
session type. Users cannot reliably select an authorized Community, and the
platform cannot retain a separate queue ledger for each User and Community.
Default attribution on a Job is not evidence that its actual queue belongs to
that Community. Metrics needs exact identities and a validated queue relationship
to attribute reservations and optional efficiency correctly.

GMS membership is currently fetched per ordinary request and reused only on that
request's Subject. Community authorization must share that information with
general access, priority checks, and POSIX groups, while bounding revocation
delay across replicas. Anonymous desktop callbacks cannot refresh GMS as the
original User and need an explicit, honest authorization model.

## Solution

Require Kueue for every new Skaha-managed user Job. Add an optional `community`
parameter to session POST; omission resolves the operator-selected Default
Community. Every Community, including Default, requires general Skaha access
and membership in at least one configured Project. A Project is an exact GMS
group identity, not a separate scheduler or accounting boundary.

Resolve the Community's single operator-provisioned ClusterQueue, authorize the
User using a five-minute shared membership snapshot, and lazily ensure a retained
LocalQueue for that exact User and Community in the workload namespace. Submit
a suspended Job selecting that queue, with protected attribution. Desktop-app
callbacks inherit the active parent Session's authorization and queue assignment.
Expose the resolved Community and matched Projects in session GET/list responses.

## User Stories

1. As an existing user, I want to omit `community`, so that clients need no new required argument.
2. As a user, I want omission to select the configured Default Community, so that operators can choose the default without changing clients.
3. As a Community member, I want to select that Community, so that my work uses its quota and fair share.
4. As a Community administrator, I want any configured Project membership to grant access, so that multiple research groups can share resources.
5. As a Community administrator, I want the same membership gate on Default, so that omission cannot bypass authorization.
6. As a user, I want exact case-sensitive identifiers, so that authorization, queues, and Metrics refer to the same identities.
7. As a user, I want malformed input distinguished from denied or unavailable access, so that I know whether to change my request or retry.
8. As an operator, I want one ClusterQueue per Community, so that routing and Community reports have an unambiguous boundary.
9. As an operator, I want to name ClusterQueues explicitly, so that existing operator-selected queue names remain usable.
10. As a queue administrator, I want Skaha to validate but never mutate ClusterQueues, so that quota and queue identity stay operator-owned.
11. As a user, I want missing queue associations rejected before Community authorization and provisioning, so that no work escapes its intended queue.
12. As an administrator, I want one atomic, validated Community catalog, so that malformed configuration cannot open access accidentally.
13. As an administrator, I want membership grants and revocations refreshed within five minutes, so that new-session access changes predictably.
14. As a GMS operator, I want one coordinated fill per missing membership snapshot, so that simultaneous requests across replicas do not create a burst.
15. As a security operator, I want dependency failures to fail closed, so that outages never become empty successful membership results or stale grants.
16. As a filesystem user, I want every effective GMS membership preserved for POSIX mapping, so that selecting a Community does not remove unrelated filesystem access.
17. As a filesystem operator, I want unmapped memberships to block launch, so that sessions do not start with incomplete supplemental groups.
18. As a Permissions API operator, I want that access mode retained, so that Community routing does not replace existing general authorization.
19. As a user listing or deleting sessions, I want membership-independent operations to remain available, so that a Redis outage does not unnecessarily strand my work.
20. As a fair-share operator, I want one retained LocalQueue per User and Community in each workload namespace, so that session churn does not reset its ledger.
21. As an operator, I want concurrent first launches to converge on the same LocalQueue, so that races cannot split or overwrite identity.
22. As an operator, I want safe missing-label repair only on Skaha-owned LocalQueues, so that repair does not adopt or rewrite another producer's objects.
23. As an operator, I want conflicting identity or ClusterQueue references rejected, so that a queue cannot be silently reassigned.
24. As a user with mixed-case or long identifiers, I want deterministic valid queue names, so that exact identity is preserved without lossy normalization.
25. As a Kueue operator, I want every new Job suspended and explicitly queued, so that admission degradation never starts an unmanaged Job.
26. As a session operator, I want session-type priority policy preserved independently of routing, so that this change does not redefine scheduling priority.
27. As a desktop user, I want applications to inherit my active Session's assignment, so that anonymous callbacks need neither a membership-cache hit nor unavailable delegated credentials.
28. As a security operator, I want callback tokens bound to the exact active parent, so that a token cannot launch work under a different Session or Community.
29. As a user, I want GET/list to show stored Community and Projects, so that I can see the attribution actually used at launch.
30. As an auditor, I want all matched Projects recorded as a stable snapshot, so that membership evidence is not lost or confused with exclusive billing.
31. As a Metrics operator, I want exact queue and workload metadata, so that reservation and efficiency reports agree about User and Community identity.
32. As a Metrics maintainer, I want Project annotations excluded from metric dimensions, so that many-valued membership does not duplicate work or create set-valued labels.
33. As a Metrics maintainer, I want read-only ownership and other producers' valid LocalQueues preserved, so that Skaha provisioning does not narrow User aggregation incorrectly.
34. As a Helm operator, I want a dedicated non-default service account with minimal queue permissions, so that new provisioning authority is explicit.
35. As an operator, I want live failures isolated to the affected Community, so that an unrelated queue outage does not block valid launches.
36. As an upgrade operator, I want existing Jobs and queues left untouched, so that rollout does not migrate work or erase fair-share history.
37. As a support engineer, I want bounded diagnostics without credentials or membership lists in client errors, so that failures can be investigated safely.
38. As a maintainer, I want behavior-level request, cache, manifest, and chart tests, so that implementations can evolve without weakening the contract.

## Implementation Decisions

### 1. Domain and configuration

The Helm chart owns a ConfigMap containing a top-level `communities` mapping.
Each Community entry has the following contract:

| Field | Contract |
| --- | --- |
| Mapping key | Exact nonempty Community name, valid as a Kubernetes label value |
| `default` | Boolean; absent means false; exactly one Community must set true |
| `clusterQueue` | Required exact name of an existing ClusterQueue; not inferred from the Community name |
| `projects` | Required nonempty list of unique exact canonical GMS group names in the configured GMS service |

There is no additional default-name override: choosing the sole `default: true`
entry chooses its name. Duplicate mapping keys, duplicate Project entries, missing
required values, unsupported fields, invalid names, and zero or multiple defaults
are invalid configuration. Two Communities cannot reference the same ClusterQueue.
Chart validation and runtime validation enforce the same schema. The implementation
must document bounded catalog and membership payload sizes and reject oversized
inputs; these limits must not silently truncate an authorization set.

Community and User label values are exact, case-sensitive, nonempty Kubernetes
label values of at most 63 characters. Do not trim, lowercase, normalize, or
interpret aliases. Project names follow GMS identity syntax, not the narrower
queue-name syntax: they are annotation values, not Kubernetes label values.
Each Project denotes the exact pair of configured GMS service identity and group
name. There is no separate Project-to-group mapping or global group catalog.
Effective/nested memberships count; no direct-membership path is required.

The configured Project roster is operator-authoritative. GMS search cannot prove
that an unreturned name exists rather than that the caller is a non-member.
Consequently a nonexistent or differently cased configured name grants nothing;
Skaha does not make an administrative group-inventory request to validate it.

### 2. Request boundary and launch assignment

Session-create POST accepts optional `community` using the existing query-parameter
convention. The internal default is absent/None, not the literal string `None`.
An omitted value resolves Default; an explicit value must be nonempty and valid.
Blank or whitespace-padded values are not omission. Requests cannot supply an
authoritative username, LocalQueue, or matched-Project list.

Keep one launch-preparation boundary used by every authenticated Job creation
path. It consumes authenticated identity, the requested Community, and existing
launch policy inputs, and returns an immutable assignment containing namespace,
User, Community, ClusterQueue, LocalQueue, matched Projects, and the membership
observation/catalog identity needed for consistent diagnostics. Builders consume
this assignment; they must not independently choose queues or reauthorize.

The sequence is authentication and existing general access authorization; resolve
the current catalog and exact Community route; validate the named live ClusterQueue;
intersect the full membership snapshot with configured Projects; resolve all POSIX
groups and validate other launch inputs; ensure the LocalQueue; create the suspended
Job. General group-mode authorization may already need GMS before route resolution.
An absent route does not trigger an additional Community-specific GMS call. All
authorization and routing rejection paths precede LocalQueue and workload creation.

General Skaha access and Community membership are both required, including for
Default. Matching any configured Project grants the Community. Store all matching
Projects, sorted deterministically by exact name and deduplicated. A no-match
snapshot is a genuine denial, not a dependency error. General authorization alone
does not grant a Community, and selecting one Community does not restrict POSIX
groups to that Community's Projects.

### 3. GMS snapshots, catalog freshness, and POSIX behavior

Use the existing GMS client under the authenticated request Subject to retrieve
the complete canonical membership set. Intersect locally using exact service and
name equality, not GMS candidate-query matching. Introduce a typed request-level
membership snapshot in place of treating any Subject credential list as cached
groups. General group authorization, priority-group checks, Community authorization,
and POSIX mapping consume the same snapshot for a request.

Share successful membership snapshots in the existing external Redis deployment.
Keys isolate deployment, canonical User, and GMS service identity; do not key only
by username, access token, or Community. Store complete canonical group identities,
observation time, expiry, and a versioned payload, never delegated credentials or
callback tokens. A successful empty snapshot is cacheable and distinct from failure.

The membership lifetime is exactly 300 seconds from the conservative observation
time, taken at the start of the successful GMS fetch. Reads do not extend it; publish
only the remaining lifetime, and do not publish or use a result already expired.
Validate freshness when consuming the snapshot for launch authorization. There is
no stale authorization window. Changes to the Community catalog do not restart
the membership clock. Do not add a derived User-Community grant cache: derive the
intersection from the current immutable catalog and fresh membership snapshot.

Coordinate cache misses across replicas with distributed single-flight. One lease
holder performs the bounded GMS fetch; bounded waiters reuse its successful result.
Ownership-checked publication/release prevents an expired or displaced holder
overwriting a newer fill. Timeout, lock loss, malformed payload, failed fill, or
Redis unavailability returns 503; it is not a cached empty membership. No direct-GMS
bypass is allowed during Redis outage. Lease duration, remote deadline, waiter
deadline, and capacity limits must be explicit, bounded, and covered by tests.

Read the complete ConfigMap through the Kubernetes API, not an indefinitely cached
environment value or mounted-file assumption. Each replica may hold an immutable
validated catalog for at most 300 seconds. Observe its resourceVersion for atomic
replacement, not numeric ordering. A successfully read unchanged version renews
the catalog observation; a failed read does not. A newly observed malformed catalog
invalidates that replica's launch catalog immediately, and an expired catalog cannot
be served on refresh failure. Configuration changes are effective within the
five-minute read window; no partially updated Community maps are visible.

These are two separate lifetimes: a catalog cache and a shared User membership
cache. They are not the existing POSIX group-name/GID materialization caches or
Metrics report caches. Preserve existing POSIX materialization, map every effective
GMS membership to its supplemental GID, and fail closed with 503 if any mapping is
missing. Do not silently drop unrelated groups, substitute Project names for GIDs,
or make an empty successful membership result indistinguishable from a failed fetch.

Permissions API authorization remains supported. New authenticated launches still
need GMS snapshots for Projects and POSIX groups. GET/DELETE operations whose
authorization does not require memberships must not acquire a new Redis/GMS
dependency; group-mode operations continue to require their membership source.
Membership revocation affects new authenticated parent launches within the snapshot
lifetime. It does not cancel, reauthorize, or relabel existing queued/running Jobs.

### 4. ClusterQueue and LocalQueue authority

Within the platform's configured queue boundary, one Community maps to exactly one
ClusterQueue and each ClusterQueue represents one Community. Operators provision
the queues and the exact `canfar.net/community` labels. Skaha performs read-only
named lookups and validates catalog/label equality and usable object state. It never
creates, patches, relabels, adopts, or deletes a ClusterQueue. Quota exhaustion is
normal pending admission, not missing authorization or an alternate-routing signal.

Catalog-wide structural ambiguity, including repeated ClusterQueue references,
invalidates the catalog. A missing, inaccessible, terminating, or mislabeled live
ClusterQueue blocks only requests targeting that Community. Operators enforce the
global one-to-one invariant across the platform-owned set; Metrics validates its
configured set as described below. Skaha does not discover routes from arbitrary
cluster-visible queues or switch to an alternative if the configured queue fails.

Lazily ensure one LocalQueue per exact User-Community pair in the Job's namespace.
Create it only after authorization and required identity checks. A new LocalQueue
carries `canfar.net/username`, `canfar.net/community`, and
`app.kubernetes.io/managed-by=skaha`; its `spec.clusterQueue` is the configured
ClusterQueue name. It has no owner reference to a Job, Pod, or other short-lived
object. Skaha never deletes these queues, including on session deletion, rollback,
Community removal, or a later launch failure. Helm must not own them as uninstallable
release resources. Retention preserves the queue ledger; it does not freeze Kueue's
normal usage decay or imply recovery from an operator deleting a queue.

Every reuse validates the exact namespace/name, User, Community, ClusterQueue
reference, and non-terminating state. The LocalQueue Community label must equal the
referenced ClusterQueue label, which must equal the selected Community. A compliant
queue managed by another producer may be reused without modification or adoption.

Only `managed-by=skaha` permits repair of a missing identity label, and only when
exactly one identity label is missing and the other already matches the request.
If both identity labels are missing, fail closed. The LocalQueue name alone does
not prove identity when components contain hyphens. Patch only the missing keys,
with resourceVersion or equivalent optimistic preconditions, and preserve all
other metadata and spec fields. A conflicting identity value, wrong ClusterQueue
reference, or ownership conflict is 503, even on a Skaha-owned queue. Never
overwrite, force field ownership, retarget, or delete and recreate the queue.

A create conflict triggers a bounded reread and complete validation of the winner.
A patch conflict triggers bounded reread/revalidation, not unconditional retry of a
stale patch. A queue created before a later unrelated launch failure remains; the
failed request creates no Job and does not erase the retained queue.

### 5. Deterministic LocalQueue naming

The LocalQueue name is `<username>-<community>-lq`. For `bob` and `cadc` that is
`bob-cadc-lq`. The name must be a lowercase DNS-1123 label of at most 63
characters. If it is longer, or if it contains uppercase letters, underscores,
periods, or other non-DNS characters, reject the launch. Do not sanitize the
identity into another user's queue name. Do not substitute a digest name.

Hyphens inside the user or community can make two pairs share one name. In that
case, refuse with 503. Do not adopt the existing queue. Do not create a second
queue for the same name. Do not repair a queue that is missing both identity
labels.

### 6. Workload metadata and priority

| Object | Required routing/identity metadata | Additional attribution |
| --- | --- | --- |
| ClusterQueue | `canfar.net/community` | No Project or Skaha ownership metadata added by Skaha |
| LocalQueue | `canfar.net/username`, `canfar.net/community`; `spec.clusterQueue` references the matching ClusterQueue | `app.kubernetes.io/managed-by=skaha` on Skaha-created queues; no Project annotation |
| Submitting Job metadata | `kueue.x-k8s.io/queue-name` selects the exact LocalQueue | Skaha always emits exact User/Community labels and `canfar.net/projects` annotation |
| Job Pod template / resulting Pod | Exact User/Community labels | Same `canfar.net/projects` annotation as the Job |

The Project annotation is a JSON array of every matched canonical Project name,
sorted and deduplicated. It is stored only on Job and Pod metadata, not on queues,
Services, or Ingresses. It records launch authorization evidence; it is neither an
exclusive billing assignment nor permission to count one Job once per Project.
Remove scalar `canfar.net/project` emission, including its `default` placeholder.
Do not introduce `canfar.net/group`. Clients cannot override protected attribution.

For the general platform contract, workload User/Community labels are required for
optional efficiency attribution rather than core queue-only reporting. Skaha emits
them unconditionally so its workloads satisfy both contracts. Existing Session ID,
kind, allocation flavor, accelerator, app ID, and managed-by labels remain. Kueue
queue selection belongs on submitting Job metadata; do not copy it into Service
or Ingress selectors or create a second Kueue integration through the Pod template.

All new user Jobs, including desktop-app Jobs, are submitted with `spec.suspend=true`
and the queue-name label. Kueue alone admits/resumes them. No missing-queue,
session-type override, LocalQueue defaulting, or plain-scheduler fallback remains.
Preflight checks do not eliminate later control-plane races: a Job submitted during
a subsequent Kueue outage must remain suspended, never be manually resumed by Skaha.

Replace static per-session-type LocalQueue names. Retain per-session-type Kueue
workload priority configuration and its existing default behavior independently;
preserve Pod PriorityClass and headless priority-group behavior. Remove the static
LocalQueue startup existence check. Old static queue-name configuration must produce
an actionable migration error, not silently restore optional routing.

### 7. Desktop callbacks and session reads

The signed desktop callback is a capability of an active parent Session, not a
fresh membership assertion. Validate the existing write grant, require its Session
ID to equal the route Session ID exactly, and require the authoritative parent to
be an active desktop Session, neither terminating nor terminal. A different route
or invalid capability is denied before child creation.

Read User, Community, matched Projects, selected LocalQueue, and POSIX identity
including stored supplemental GIDs from the authoritative parent Job/Session.
Inherit them unchanged; do not take them from callback parameters, current Default,
current membership, or queue-name parsing. No GMS call or membership-cache entry is
required. Catalog changes do not reauthorize or silently reroute an active parent.
Still validate the inherited LocalQueue and its referenced live ClusterQueue before
submitting the suspended child Job. Do not repair by selecting a different queue.

The desktop-app route does not gain an independent Community selector. If a caller
supplies a Community through shared request parsing, it cannot change the inherited
value; a conflicting value is 400. Missing/inconsistent authoritative parent routing
metadata is 503. A legacy desktop lacking it must be recreated before attaching new
apps; do not invent Default or infer Projects from a scalar legacy label.

Membership revocation consequently blocks future authenticated parent launches
after cache expiry but does not revoke app-launch capability during the active
parent's lifetime. Parent termination ends that capability. Recheck active parent
state at the child launch boundary; the usual concurrent termination race does not
grant a new independent or reparented capability.

Session GET/list gains additive `community` string and `projects` array fields
read from stored launch metadata, not recomputed from current memberships. LocalQueue
remains internal. Preserve the successful POST session-ID response and existing
renew/delete/list behavior. For legacy Jobs with unknown authoritative attribution,
omit the unknown new fields rather than invent Default or an empty authorization
snapshot. An empty array is not a substitute for missing evidence.

### 8. Failure categories and availability

| HTTP | Launch outcome |
| --- | --- |
| 400 | Blank/malformed explicit Community, or attempted callback Community change |
| 403 | Generally authorized caller lacks every configured Project membership; general access/capability denials retain their existing authentication distinction |
| 404 | Syntactically valid Community is not configured or its exact named ClusterQueue does not exist |
| 503 | Invalid/expired catalog, ambiguous mapping, wrong/missing queue metadata, GMS/Redis/Kubernetes failure, missing Kueue API, unmapped GID, bounded coordination timeout, or unsafe LocalQueue/parent state |

Existing unauthenticated 401 behavior and unrelated session validation/status codes
remain. Missing Kueue APIs must not be mistaken for a missing individual queue (404).
Client messages use stable categories without membership lists, credentials, or
private infrastructure diagnostics. Logs may identify the affected configured route
under existing access controls, but must not dump Subjects, tokens, or full GMS
responses. Telemetry uses bounded stage/outcome dimensions, not User, Project,
LocalQueue names, or raw errors as high-cardinality labels.

Failures never create an unqueued Job. Missing Kueue blocks new launches, not all
Skaha lifecycle traffic. Do not wire transient launch-only dependencies into a global
probe that removes all replicas from GET/DELETE service. Invalid catalog structure
blocks all new authenticated launches; live route failures are Community-local.
Active-parent callbacks retain the explicitly narrower inherited-authority path.

### 9. Helm, RBAC, and Metrics integration

Kueue and its Job integration, the named labeled ClusterQueues, and external Redis
are deployment prerequisites. Use the supported Kueue API version consistently
across Skaha, charts, and tests; the current repository baseline is v1beta2. The
chart does not install Kueue, create ClusterQueues, or eagerly create User queues.

Require an explicit dedicated Skaha service account; never bind these permissions
to `default`. Grant namespaced LocalQueue get/create/patch and ConfigMap get, plus
read-only ClusterQueue get scoped to the configured names where practical. No
LocalQueue delete/update or ClusterQueue mutation is needed. Label-level patch
restrictions are an application contract, not something plain RBAC can enforce;
external admission controls can add defense in depth. Retain an externally managed
RBAC mode with the same documented runtime requirements. Dynamic catalog changes
that add named queues must also update any resourceNames restriction.

Metrics retains its separate read-only identity, explicit configured ClusterQueue
allowlist, and namespace list. Those lists must include every routed Community queue
and workload namespace being reported. Metrics is not an authorization or routing
dependency of Skaha and never provisions or repairs queues.

Tighten Metrics' Community model from aggregation of multiple matching ClusterQueues
to exactly one matching configured ClusterQueue. Duplicate Community labels on
distinct configured ClusterQueues are invalid primary-source data, not a sum;
absence remains 404. Platform aggregation must not silently accept an ambiguous
configured set. Continue validating each LocalQueue's Community against its
referenced configured ClusterQueue. User reports still aggregate all matching valid
LocalQueues across configured namespaces, including multiple same-identity queues
from other producers; Skaha's one-queue provisioning rule is not a Metrics dedup key.

Keep existing Metrics response schemas, reservation formulas, Session child-Job
aggregation, efficiency calculations, read-only scope, and report-cache failure
policy. In particular Metrics serviceable stale reports are not authorization
grants; their independent cache windows do not extend Skaha membership lifetime.
Remove scalar Project filters/panels and `canfar.net/project` dependencies in shipped
dashboards, metric-label allowlists, examples, and fixtures. Do not export the JSON
annotation as a Prometheus dimension or synthesize a one-Project compatibility label.

### 10. Rollout and lifecycle

Ship this as an explicit chart/runtime contract migration. First operators install
and configure Kueue, provision/label the Community ClusterQueues, configure the
catalog, external Redis, priorities, and dedicated RBAC, and align Metrics queue
scope. Then roll out the new Skaha path and corresponding Metrics/dashboard changes,
verify default and explicit launches, and confirm old queue-name settings are gone.
All launch-serving old replicas must be drained or upgraded before claiming that
every new Job is required to use Kueue; a mixed deployment cannot make that guarantee.

Do not migrate, relabel, recreate, or delete existing Jobs or LocalQueues. Existing
queued/running work continues, and legacy desktop-app limitations are documented.
Removing a Community stops new authenticated launches once catalogs refresh but
does not delete its ledger. Renaming/repointing a ClusterQueue is an operator-led
migration outside this feature; never retarget an existing User queue to reset
history. Rolling back to an optional-queue release withdraws the mandatory-Kueue
guarantee and requires an explicit operator decision; it is not an automatic recovery
from a failed launch. No cluster mutation is part of this specification task.

## Testing Decisions

Use the highest existing useful boundary: drive session POST and desktop-app
callbacks through action-style request/response tests and inspect observable Jobs,
LocalQueues, and responses. Substitute controlled GMS, Redis, Kubernetes, catalog,
and clock boundaries there. Add focused shared-cache/concurrency tests and chart
render tests where one request cannot expose the behavior. Avoid parallel test-only
authorization logic, private helper assertions, or asserting incidental client call
order; ordering assertions should prove only authorization and side-effect safety.

Reuse the existing platform-stats action test's SyncInput/SyncOutput approach,
Session Job-builder manifest assertions, initialization failure injection, session
authorizer tests, Helm render tests, and Metrics malformed/cross-linked queue and
Session-child fixtures. Do not run lifecycle acceptance against a live shared cluster.

| Boundary | Required acceptance cases |
| --- | --- |
| POST/default | Omission selects the sole configured default; explicit same default passes the identical Project gate; blank/whitespace/case differences are not normalized; unknown route 404; correct success response unchanged |
| Catalog | No/multiple defaults; empty/duplicate Projects; duplicate keys/references; invalid names/types/unknown fields; bounded sizes; atomic replacement; same and changed resourceVersions; failed/invalid refresh; expiry without stale fallback |
| GMS/auth | First/later Project grants; all matches sorted/deduplicated; successful empty snapshot denies; exact service/name identity; nested membership; nonexistent/nonmember names both grant nothing; general denial cannot be bypassed; Permissions API coexistence |
| Shared cache | Concurrent replicas miss once; successful empty/nonempty reuse; isolation by User/service/deployment; fresh just before 300s and expired at 300s; non-sliding reads; slow fill does not extend freshness; lock expiry/loss, late holder, failed publish, bounded wait, corrupted payload, remote error, Redis outage; no credentials stored |
| Consistency/POSIX | One membership observation across general auth, headless priority, Project intersection, and all supplemental GIDs; catalog edits do not renew membership; unrelated groups preserved; unmapped group 503; no derived cache extending grants |
| ClusterQueue | Correct exact named queue; missing object versus missing API; wrong/missing label; terminating/inaccessible object; no mutation; target-local failure isolation; no quota-based reroute |
| LocalQueue | Create, reuse, concurrent 409 winner; safe owned missing-label repair; unowned compliant reuse without adoption; unowned missing labels denied; present conflicts, wrong reference, terminating state, ambiguous name, stale patch and UID replacement denied; no delete/update/retarget; retained queue after later failure |
| Naming | Readable example; reject uppercase, underscores, periods; 63/64 boundary; hyphen-composition collision; refuse repair when both identity labels are missing |
| Emitted objects | Every session kind and app Job initially suspended with exact queue label; matching Job/Pod User and Community; JSON Project annotation on Job/Pod only; no scalar project or group label; existing session metadata/selectors and priority policy preserved |
| Callback | Valid active parent inheritance after membership expiry and Redis outage; no GMS; invalid grant/route mismatch rejected; terminating/terminal/missing/legacy parent rejected; conflicting Community rejected; catalog/default edits cannot switch assignment; missing/mismatched inherited live queues fail closed |
| Reads/lifecycle | Additive stored attribution; later membership changes do not rewrite it; unknown legacy fields omitted; LocalQueue not exposed; Permissions API GET/DELETE remain membership-independent; existing renew/delete/list behavior remains |
| Helm/security | Valid catalog rendering; schema parity with runtime; explicit non-default identity; namespaced get/create/patch and exact read scope; no queue deletion/CQ mutation; external RBAC mode; static queue-name migration errors; independent priority config; no launch-only outage disabling all lifecycle traffic |
| Metrics | One CQ per Community; duplicate labels invalid, not summed; missing Community 404; exact LQ/CQ equality; all valid other-producer LQs still aggregate; existing Session child aggregation and report-cache semantics unchanged; no scalar Project metric/dashboard dependency |

Finish with a disposable, explicitly selected kind/Kueue environment using controlled
GMS identities and external test Redis: two Users, two Communities, default and
explicit launches, simultaneous first use, and a desktop child. Inspect retained
LocalQueues, initially suspended Jobs, Kueue Workload selection/admission, and Metrics
reports. Inject missing Kueue, queue metadata corruption, denied membership, and
Redis failure. No failure may create an unqueued/running Job. Separate controller
admission evidence from local manifest tests, and distinguish required future tests
from tests actually run while writing this spec.

## Out of Scope

- Implementing runtime changes, deploying this design, or mutating live Kubernetes during specification work.
- Creating, labeling, sizing, deleting, or managing ClusterQueues, Cohorts, ResourceFlavors, quotas, borrowing, or preemption policy in Skaha.
- A standalone Community service, a Project-to-group alias layer, global GMS group inventory, or a Project POST selector.
- Project-level scheduling, quotas, accounting, or many-valued Prometheus attribution.
- New Kueue workload integrations beyond the Skaha-managed Job paths already supported.
- Retroactive reauthorization or migration of queued/running work, membership-revocation cancellation, or automatic ledger cleanup.
- Delegated/on-behalf-of GMS for anonymous callbacks; the active-parent capability is the selected model.
- Fair-share policy redesign, guaranteed-session floors, pending-position UI, resize Workloads, session TTL, or idle-culling changes.
- Changes to Metrics schemas, report-cache windows, aggregation quantities, or monitoring-backend ownership beyond the explicit one-ClusterQueue invariant and Project-dimension removal.
- Operator deletion protection or an admission policy for arbitrary non-Skaha submitters.

## Further Notes

This supersedes the earlier draft's open Default route, process-local membership
cache, derived grant cache, callback membership recheck, prohibition of all LocalQueue
patches, and retained scalar Project label. Projects remain exact GMS group-based
authorization/audit identities, not the earlier separate Project metadata model.
The final ClusterQueue decision is operator-provided labels and read-only Skaha
validation; the earlier proposed ClusterQueue repair is not authorized.

The repository investigation confirmed that ordinary Skaha request initialization
fetches full GMS membership, POSIX mapping consumes all memberships, and the callback
currently reconstructs only parent POSIX state. GMS client/server source confirms
newline-delimited group names rather than Project objects. These observations explain
the shared snapshot and inherited-capability decisions; they are not proof that the
new design is implemented. Earlier staging probes are historical evidence, not a
current deployment certification, and no new live GMS probe is needed to settle this
now-confirmed contract.

The system decision and context glossaries must stay aligned with this spec. The
older product architecture equating Project with LocalQueue must be reconciled in
Confluence before release; it must not be silently treated as compatible. Jira tracks
execution, this accepted spec defines the requested implementation, and current
implementation documentation must label any pending transition explicitly.

Kueue's Job queue selector is documented in the
[official Job integration](https://kueue.sigs.k8s.io/docs/tasks/run/jobs/), and the
namespaced queue-to-ClusterQueue relationship in
[LocalQueue concepts](https://kueue.sigs.k8s.io/docs/concepts/localqueue/).
Explicit initial suspension is Skaha's stricter fail-closed policy, not a claim that
every Kueue version requires clients to set it. The distinction between label syntax
and resource names follows the
[Kubernetes label contract](https://kubernetes.io/docs/concepts/overview/working-with-objects/labels/).
