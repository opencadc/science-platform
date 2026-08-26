# ADR-0003: Proposed future scopes

## Status

Superseded by [ADR-0004](0004-canfar-metrics-resource.md) and
[ADR-0010](0010-simple-kueue-metrics-service.md).

The former proposal considered separate quota, User, and session resources.
Those routes, provider choices, Pod-source assumptions, and independent cache
rules are not current Metrics behavior. The service exposes only the three
unified `Metrics` read routes documented by ADR-0004 and uses the simple Kueue
source boundary in ADR-0010. Any future scope requires a new contract and ADR;
it must not be inferred from this historical proposal.
