# Issue Tracker: Jira

Issues and PRDs for Metrics work live in Jira on `herzberg.atlassian.net`.
Use the **CADC** project.

Use the Atlassian/Jira connector when available. If it is not available, ask
for the ticket text instead of creating a parallel GitHub issue.

## Conventions

- Project: **CADC**
- Required label: **`CANFAR`**
- Site: `herzberg.atlassian.net`
- Ticket keys use the `CADC-` prefix (for example `CADC-15555`)
- Specs, PRDs, and implementation decisions are Jira-first
- Reference Jira keys in commit messages and PR descriptions (`#CADC-1234` or
  `CADC-1234` per team convention)

## Skill Rules

When a skill says "publish to the issue tracker", create or update a Jira
ticket in the CADC project with the **`CANFAR`** label.

When a skill says "fetch the relevant ticket", fetch the Jira issue by key.

Do not create GitHub Issues, local markdown tickets, or ADRs as the source of
truth unless the user explicitly overrides this file.
