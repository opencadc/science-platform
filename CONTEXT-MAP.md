# Context map

The science-platform monorepo is **multi-context**. Read the glossary and ADRs
for the context you are working in before exploring code.

| Context | Glossary | Context ADRs | Implementation docs |
| --- | --- | --- | --- |
| **Skaha** | [`skaha/CONTEXT.md`](skaha/CONTEXT.md) | [`skaha/docs/adr/`](skaha/docs/adr/) | [`skaha/README.md`](skaha/README.md) |
| **Metrics** | [`metrics/CONTEXT.md`](metrics/CONTEXT.md) | [`metrics/docs/adr/`](metrics/docs/adr/) | [`metrics/docs/`](metrics/docs/) |

**System-wide** decisions that bind Skaha and Metrics:
[`docs/adr/`](docs/adr/).

Distilled decisions live in [`metrics/docs/adr/README.md`](metrics/docs/adr/README.md).

Cross-context terms such as **platform stats**, **platform capacity**, and
**platform allocation** appear in both glossaries where that context consumes
them. When Skaha and Metrics disagree in prose, system ADRs win.
