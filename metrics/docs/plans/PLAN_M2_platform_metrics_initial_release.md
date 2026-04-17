# Milestone M2: platform metrics initial release

This plan defines the second milestone for the CANFAR Metrics API rollout. It
targets platform metrics as the first production slice, with source priority,
cache semantics, and observability controls.

## Summary

This milestone ships `GET /api/v1/metrics/platform` as a production-ready
endpoint. You focus on Kueue-first capacity, node fallback, usage sourcing,
cache-control headers, and day-1 telemetry required for operations.

## In scope

This section lists milestone deliverables you execute.

- Finalize platform API contract and examples.
- Keep provider source ordering and fallback behavior explicit.
- Add HTTP cache-control response semantics.
- Extend OTel metrics for request and provider observability.
- Run initial deployment and stabilization debug loop.

## Out of scope

This section lists deferred work.

- User and session attribution endpoint logic.
- Staging ArgoCD integration.
- Advanced analytics and dashboard-oriented metrics slices.
- Prometheus ownership model redesign.

## Dependencies

This milestone depends on the M1 foundation and existing provider stack.

- M1 quality and delivery scaffolding.
- `metrics/src/metrics/providers/kueue.py`.
- `metrics/src/metrics/providers/node.py`.
- `metrics/src/metrics/providers/prometheus.py`.
- `metrics/src/metrics/service.py` orchestration.

## Constraints

This milestone must preserve operational safety and efficiency.

- Maintain low impact on production infrastructure.
- Bound timeout and request behavior for provider calls.
- Preserve 12-factor runtime configuration model.
- Keep contract payload keys stable for platform scope.

## Implementation phases

This section sequences platform release work.

1. **Contract lock**
   - Confirm canonical route and response envelope.
   - Confirm metadata semantics and source provenance fields.
2. **Provider behavior and safeguards**
   - Keep Kueue-first and node-fallback behavior explicit.
   - Keep provider failure mapping deterministic.
3. **Cache-control delivery**
   - Add HTTP cache headers derived from TTL semantics.
   - Keep cache hit/miss state visible for diagnostics.
4. **Telemetry expansion**
   - Emit request, cache, compute, and provider metrics.
   - Validate OTel output during integration testing.
5. **Initial release stabilization**
   - Execute deployment smoke checks.
   - Run debug loop checklist and tune runtime values as needed.

## Validation plan

This section defines milestone verification.

- Run gate `harness-contracts`.
- Run gate `repository-coverage`.
- Run gate `harness-cli`.
- Validate platform endpoint payload and cache headers.
- Validate OTel metric emission in release smoke environment.
- Complete debug loop checks from the production runbook.

## Risks

This section lists milestone risks and mitigations.

- **Provider source instability:** Keep fallback and provenance explicit.
- **Cache misconfiguration:** Validate TTL and cache header behavior in tests.
- **Telemetry drift:** Keep request and provider metrics under automated tests.
- **Release regression risk:** Use staged smoke checks before full rollout.

## Operational controls

This section defines release controls for stable operation.

- Require smoke validation before promotion.
- Require cache and telemetry checks per rollout.
- Require controlled timeout and TTL updates with evidence.
- Require post-release debug checklist completion.

## Implementer handoff checklist

Use this checklist to close M2 execution.

- [ ] Platform route contract is validated.
- [ ] Kueue-first and node fallback behavior is verified.
- [ ] Cache-control headers are present and tested.
- [ ] OTel request/provider metrics are verified.
- [ ] Release debug loop is completed and recorded.
- [ ] Required gates pass.
