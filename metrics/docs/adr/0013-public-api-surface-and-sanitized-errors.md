# ADR-0013: Public API surface — progressive routes, sanitized errors

## Status

Accepted (M4+).
Consolidates ADR-0013 (sanitized client-facing error responses) and ADR-0014
(progressive public route surface).

## Context

Partial user/session routes existed before providers could return complete
scope models; shipping stubs misleads clients and freezes bad contracts. Raw
upstream exception text and request URLs in JSON bodies leak infrastructure
details to clients and shared caches.

## Decision

- **Progressive routes.** Public routes ship only when a provider returns a
  complete contract for that scope. M4 exposes only
  `GET /api/v1/metrics/platform` (`PlatformMetrics`) and `GET /healthz`.
  Later milestones add distinct contracts when complete: **InteractiveQuota**
  (M5, ADR-0015), **UserMetrics** and **SessionMetrics** (M6/M7, ADR-0020).
  No partial or hidden alias endpoints.
- **Sanitized errors.** User-facing error envelopes never expose raw
  transport/Kubernetes exception strings or upstream URLs. Descriptive
  failures are logged server-side; clients get stable HTTP status codes and
  short messages.

## Consequences

- Science Portal and Skaha must not depend on routes before their milestone
  lands; removing deprecated routes is expected during architecture resets.
- Integration tests assert status codes and envelope shape, not upstream
  error text.
