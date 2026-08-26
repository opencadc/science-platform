# ADR-0004: Unified Metrics resource in the CANFAR API group

## Status

Accepted for the API envelope. Source and optional-efficiency details are
superseded by [ADR-0010](0010-simple-kueue-metrics-service.md).

## Decision

Use one `Metrics` resource under `canfar.net/v1alpha1` for the three bounded
read surfaces:

```text
GET /apis/canfar.net/v1alpha1/metrics/user/{username}
GET /apis/canfar.net/v1alpha1/metrics/community/{community}
GET /apis/canfar.net/v1alpha1/metrics/platform/{platform}
```

The path selects the subject and the response echoes exactly one of
`spec.user`, `spec.community`, or `spec.platform`. All responses carry
`status.observedAt`, `status.reservingWorkloads`, open-ended resource names,
and one `Ready` plus one `Cached` condition. User and Community resources
contain `requests` and optional `efficiency`; Platform resources contain
`capacity`, `allocated`, and optional `efficiency`.

The API is a read-only HTTP surface. It does not expose collection writes,
Pod inventories, raw PromQL, or a public time-series API. Unknown subjects use
the documented 404/503 behavior; optional efficiency failure is represented by
`Ready=False`/`PartialData` in an HTTP 200 response.

## Consequences

The API kind stays stable while source implementations evolve. The former
Pod-based current-request and lifetime-accounting phases are historical and do
not define the current response. The single resource shape prevents separate
Platform, User, and Community envelopes from drifting apart.
