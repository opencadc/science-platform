# Plans index

This file lists active plan documents and keeps process guidance separate from
milestone content.

## Process

This section points to the shared milestone authoring guidance for this
repository.

- `docs/plans/milestone-process.md`
- Roadmap reviews should update milestone docs when they find drift between
  plans, gates, and repository facts, using the snapshot and naming alignment
  guidance in `docs/plans/milestone-process.md`.

## Active milestones

This section lists the milestone sequence that is currently active for the
metrics roadmap.

Primary delivery milestones use unique monotonic ids (`M1`..`M11`). Checklist
plans such as `PLAN_M2_post_review_feedback.md` are closure satellites for their
parent milestone and are not separate sequence ids.

- `docs/plans/PLAN_M1_project_setup_and_delivery_foundation.md` — outcomes: `docs/plans/PLAN_M1_outcomes.md`
- `docs/plans/PLAN_M2_platform_metrics_initial_release.md` — outcomes: `docs/plans/PLAN_M2_outcomes.md`
- `docs/plans/PLAN_M2_post_review_feedback.md` — M2 closure checklist
  (post-review operator and contract hardening)
- `docs/plans/PLAN_M3_app_structure_and_platform_sources.md` — outcomes: `docs/plans/PLAN_M3_outcomes.md`
- `docs/plans/PLAN_M4_provider_runtime_architecture.md`
- `docs/plans/PLAN_M5_interactive_quota_release.md`
- `docs/plans/PLAN_M6_user_metrics_release.md`
- `docs/plans/PLAN_M7_session_metrics_release.md`

## Supporting rollout plans

This section lists milestone plans that support rollout and stabilization after
core feature milestones. They are still part of the numbered roadmap sequence.

- `docs/plans/PLAN_M8_rollout_baseline_matrix.md`
- `docs/plans/PLAN_M9_initial_prod_debug_loop.md`
- `docs/plans/PLAN_M10_post_initial_argocd_staging.md`
- `docs/plans/PLAN_M11_local_k8s_workflow_simplification.md` — outcomes: `docs/plans/PLAN_M11_outcomes.md`
