# Domain Docs

How engineering skills should consume domain documentation in the
science-platform monorepo.

## Before exploring, read these

- **`CONTEXT-MAP.md`** at the repository root — routes to Skaha and Metrics
  glossaries and ADR locations.
- **`skaha/CONTEXT.md`** or **`metrics/CONTEXT.md`** — read the glossary for the
  context you are working in (both when the task crosses the Skaha–Metrics
  boundary).
- **`docs/adr/`** — system-wide decisions spanning Skaha and Metrics.
- **`skaha/docs/adr/`** or **`metrics/docs/adr/`** — context-specific decisions
  for the area you are changing.

Metrics implementation facts also live under `metrics/docs/` (`architecture.md`,
`design.md`, `specs.md`, `learnings.md`, `environment-contracts.md`,
`kueue-platform.md`). **Canonical decisions:** [`../adr/README.md`](../adr/README.md).

If any file does not exist, **proceed silently**. The producer skill
(`/grill-with-docs`) creates glossary terms and ADRs lazily when decisions
crystallize.

## File structure

Multi-context monorepo:

```
science-platform/
├── CONTEXT-MAP.md
├── CONTEXT.md                         ← pointer to CONTEXT-MAP.md
├── docs/adr/                          ← system-wide decisions
├── skaha/
│   ├── CONTEXT.md
│   └── docs/adr/
└── metrics/
    ├── CONTEXT.md
    ├── docs/
    │   ├── adr/
    │   ├── architecture.md
    │   ├── design.md
    │   ├── specs.md
    │   └── learnings.md
    └── src/metrics/
```

## Use the glossary's vocabulary

When your output names a domain concept, use the term as defined in the relevant
`CONTEXT.md`. Don't drift to synonyms the glossary explicitly avoids.

Cross-context work: read both glossaries and the system ADRs under `docs/adr/`.

## Flag ADR conflicts

If your output contradicts an existing ADR, surface it explicitly rather than
silently overriding:

> _Contradicts ADR-0003 (platform capacity/allocated unit parity) — but worth
> reopening because…_
