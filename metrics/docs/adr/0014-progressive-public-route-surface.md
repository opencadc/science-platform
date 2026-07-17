# ADR-0014: Progressive public route surface

## Status

Accepted (M4+)

## Context

Partial user/session routes existed before providers could return complete scope
models. Shipping stubs misleads clients and freezes bad contracts.

## Decision

- Public routes ship only when a provider returns a **complete** contract for
  that scope.
- M4 intentionally exposes only `GET /api/v1/metrics/platform` (`PlatformMetrics`)
  and `GET /healthz`.
- Later milestones add distinct contracts and routes when complete:
  - **InteractiveQuota** — `GET .../quotas/interactive` (M5, proposed)
  - **UserMetrics** — `GET .../users/{user}` (M6, proposed)
  - **SessionMetrics** — `GET .../users/{user}/sessions/{uuid}` (M7, proposed)
- No partial or hidden alias endpoints.

## Consequences

- Science Portal and Skaha must not depend on routes until their milestone lands.
- Removing deprecated routes is expected during architecture resets (M4).
