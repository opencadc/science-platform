# ADR-0013: Sanitized client-facing error responses

## Status

Accepted (M4)

## Context

Raw upstream exception text and request URLs in JSON bodies leak infrastructure
details to API clients and shared caches.

## Decision

- User-facing error envelopes must **not** expose raw httpx/Kubernetes exception
  strings or upstream URLs.
- Log descriptive failures server-side; return stable HTTP status codes and short
  messages to clients.

## Consequences

- Integration tests assert status and envelope shape, not upstream error text in
  response bodies.
