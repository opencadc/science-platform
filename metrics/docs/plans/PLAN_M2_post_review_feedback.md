# M2 closure: post-review operator and contract hardening

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close consensus gaps from multi-persona review before declaring M2 done: upgrade ergonomics (`live` alias), operator documentation (env precedence, Helm identity), release communication (changelog), observability for silent quantity parse failures, and least-privilege RBAC without expanding M2 scope into performance refactors or Prometheus redesign.

**Architecture:** Keep behavioral changes minimal and localized: `Settings` validators for legacy env tokens, structured logging at the quantity parse boundary, Markdown contract updates alongside code, Helm verb narrowing for unused `watch`, and changelog entries that mirror `docs/plans/PLAN_M2_outcomes.md`.

**Tech Stack:** Python 3.13+, Pydantic v2 / pydantic-settings, FastAPI, pytest, Helm 3.

**Plan location note:** This repository keeps milestone plans under `docs/plans/`; this file is the canonical M2 closure checklist.

---

## File structure (this effort)

| File | Responsibility |
|------|----------------|
| `metrics/src/metrics/config.py` | Add `provider_mode` `before` validator mapping legacy `live` → `kueue`. |
| `metrics/tests/test_config.py` | Regression tests for `live` / `LIVE` normalization. |
| `metrics/src/metrics/quantity.py` | Log warning when generic `float()` parse fails in `parse_resource_amount`. |
| `metrics/tests/test_quantity.py` | New module: caplog assertion for parse failure logging (create if absent; else extend). |
| `metrics/CHANGELOG.md` | Document breaking env and platform JSON changes + `live` alias policy. |
| `metrics/docs/environment-contracts.md` | Document `KUEUE_METRICS_*` vs `METRICS_*` merge precedence. |
| `metrics/docs/plans/PLAN_M2_platform_metrics_initial_release.md` | Verify snapshot accuracy and closure checklist pointers. |
| `metrics/helm/metrics-api/templates/rbac.yaml` | Remove `watch` verb (app uses GET only). |
| `metrics/helm/metrics-api/values.yaml` | Comment: enable dedicated SA when `rbac.create` is true. |
| `metrics/README.md` | One paragraph: Kueue RBAC + SA recommendation and link to environment-contracts. |

---

### Task 1: Legacy `METRICS_PROVIDER_MODE=live` maps to `kueue`

**Files:**

- Modify: `metrics/src/metrics/config.py` (after `provider_mode` field definition, before or after `_coerce_environment`; group validators logically)
- Modify: `metrics/tests/test_config.py`
- Test: `metrics/tests/test_config.py`

- [ ] **Step 1: Write the failing tests**

Append to `metrics/tests/test_config.py`:

```python
def test_provider_mode_legacy_live_maps_to_kueue() -> None:
    assert Settings(provider_mode="live").provider_mode == "kueue"


def test_provider_mode_legacy_live_case_insensitive() -> None:
    assert Settings(provider_mode="LIVE").provider_mode == "kueue"
```

- [ ] **Step 2: Run tests to verify failure**

Run:

```bash
cd /Users/brars/Workspace/opencadc/science-platform/metrics && uv run pytest tests/test_config.py::test_provider_mode_legacy_live_maps_to_kueue tests/test_config.py::test_provider_mode_legacy_live_case_insensitive -v
```

Expected: **FAIL** with `ValidationError` (invalid literal for `provider_mode`).

- [ ] **Step 3: Minimal implementation**

In `metrics/src/metrics/config.py`, add after the `environment` validator block (or immediately before `host:`) a new validator:

```python
    @field_validator("provider_mode", mode="before")
    @classmethod
    def _coerce_legacy_provider_mode(cls, value: object) -> str:
        """Map removed ``live`` mode to ``kueue`` for chart upgrades (M2 closure)."""
        if value is None:
            return "static"
        key = str(value).strip().lower()
        if key == "live":
            return "kueue"
        return str(value).strip().lower()
```

Ensure `provider_mode: Literal["static", "kueue"]` remains unchanged on the field line.

- [ ] **Step 4: Run tests to verify pass**

Run the same `pytest` command as Step 2.

Expected: **PASS**

- [ ] **Step 5: Commit**

```bash
cd /Users/brars/Workspace/opencadc/science-platform && git add metrics/src/metrics/config.py metrics/tests/test_config.py && git commit -m "fix(metrics): accept legacy METRICS_PROVIDER_MODE=live as kueue"
```

---

### Task 2: Changelog: M2 breaking and platform contract

**Files:**

- Modify: `metrics/CHANGELOG.md`

- [ ] **Step 1: Add an `[Unreleased]` section** with concrete bullets

Replace or prepend before `## 0.1.0` so the file begins with:

```markdown
# Changelog

## [Unreleased]

### Breaking changes

- `METRICS_PROVIDER_MODE` accepts only `static` and `kueue`. The former `live`
  value is normalized to `kueue` at settings load for one upgrade-friendly
  release cycle; remove `live` from charts when convenient.
- `GET /api/v1/metrics/platform` JSON uses open string maps `capacity` and
  `allocated` (Kubernetes quantity strings). Clients that expected the older
  nested `usage` / typed snapshot shape must update.

### Documentation

- Operator env precedence for `KUEUE_METRICS_*` vs `METRICS_*` is documented in
  `docs/environment-contracts.md`.

## 0.1.0 (2026-04-17)
```

(Adjust date text if release tagging policy differs.)

- [ ] **Step 2: Commit**

```bash
git add metrics/CHANGELOG.md && git commit -m "docs(metrics): record M2 breaking changes in changelog"
```

---

### Task 3: Environment contracts: alias precedence

**Files:**

- Modify: `metrics/docs/environment-contracts.md`

- [ ] **Step 1: Insert a new section** after the canonical environment table (after line ~26, before `## What this repository provides`)

Use this exact Markdown:

```markdown
## Operator alias precedence (`KUEUE_METRICS_*` versus `METRICS_*`)

The application loads `METRICS_*` fields first, then applies **fill-only** aliases
from the process environment inside
`Settings._apply_operator_kueue_env_aliases` in
`metrics/src/metrics/config.py`:

| If this field is empty | And this env var is set | Then |
| --- | --- | --- |
| `kube_api_url` | `KUEUE_METRICS_URL` | `kube_api_url` is set from `KUEUE_METRICS_URL`. |
| `kueue_cluster_queues` | `KUEUE_METRICS_CLUSTER_QUEUES` | Queue list parsed from comma-separated value. |
| `kueue_cohort` | `KUEUE_METRICS_COHORT` | Cohort name copied. |

**Important:** Aliases do **not** override non-empty primary fields. Setting a
wrong `METRICS_KUBE_API_URL` cannot be corrected later by adding
`KUEUE_METRICS_URL` in the same process. Fix the primary variable or clear it.
Token, TLS, and timeout settings remain `METRICS_*` only unless separately
documented.
```

- [ ] **Step 2: Commit**

```bash
git add metrics/docs/environment-contracts.md && git commit -m "docs(metrics): document Kueue env alias precedence"
```

---

### Task 4: Log generic quantity parse failures

**Files:**

- Create: `metrics/tests/test_quantity.py` (if the file does not exist)
- Modify: `metrics/src/metrics/quantity.py`

- [ ] **Step 1: Write the failing test**

Create `metrics/tests/test_quantity.py`:

```python
from __future__ import annotations

import logging

import pytest

from metrics.quantity import parse_resource_amount


def test_parse_resource_amount_logs_warning_for_bad_generic_quantity(
    caplog: pytest.LogCaptureFixture,
) -> None:
    caplog.set_level(logging.WARNING, logger="metrics.quantity")
    assert parse_resource_amount("nvidia.com/gpu", "not-a-number") == 0.0
    messages = [r.getMessage() for r in caplog.records]
    assert any("not-a-number" in m and "nvidia.com/gpu" in m for m in messages)
```

- [ ] **Step 2: Run test: expect FAIL**

```bash
cd /Users/brars/Workspace/opencadc/science-platform/metrics && uv run pytest tests/test_quantity.py::test_parse_resource_amount_logs_warning_for_bad_generic_quantity -v
```

Expected: **FAIL** (no log records, or assertion on message fails).

- [ ] **Step 3: Implementation**

At top of `metrics/src/metrics/quantity.py` after `from __future__` imports:

```python
import logging

logger = logging.getLogger(__name__)
```

In `parse_resource_amount`, replace the bare `except ValueError` block:

```python
    try:
        return float(value)
    except ValueError:
        logger.warning(
            "failed to parse quantity for resource %r raw=%r; treating as 0",
            resource_name,
            raw,
        )
        return 0.0
```

- [ ] **Step 4: Run test: expect PASS**

Same pytest command as Step 2.

- [ ] **Step 5: Commit**

```bash
git add metrics/src/metrics/quantity.py metrics/tests/test_quantity.py && git commit -m "fix(metrics): log warnings on generic quantity parse failure"
```

---

### Task 5: Helm RBAC: drop unused `watch`

**Files:**

- Modify: `metrics/helm/metrics-api/templates/rbac.yaml`
- Modify: `metrics/helm/metrics-api/values.yaml`

- [ ] **Step 1: Edit `rbac.yaml`**

Change line 11 from:

```yaml
    verbs: ["get", "list", "watch"]
```

to:

```yaml
    verbs: ["get", "list"]
```

- [ ] **Step 2: Add operator comment in `values.yaml`**

After the `rbac:` block (after `rbac:\n  create: false`), append:

```yaml
# When enabling rbac.create, set serviceAccount.create: true (and optionally
# serviceAccount.name) so the ClusterRoleBinding does not attach read access to
# the namespace default ServiceAccount by accident.
```

- [ ] **Step 3: Verify Helm template renders**

```bash
cd /Users/brars/Workspace/opencadc/science-platform/metrics && helm template metrics-api helm/metrics-api -f scripts/minikube-values.yaml >/dev/null && echo OK
```

Expected: `OK` on stdout, exit code 0.

- [ ] **Step 4: Commit**

```bash
git add metrics/helm/metrics-api/templates/rbac.yaml metrics/helm/metrics-api/values.yaml && git commit -m "chore(helm): narrow Kueue RBAC verbs and document SA pairing"
```

---

### Task 6: README pointer for operators

**Files:**

- Modify: `metrics/README.md`

- [ ] **Step 1: Add a short subsection** (after the first overview paragraph or in a `Kubernetes / Kueue` section if one exists; match surrounding style)

Add:

```markdown
## Kueue mode and RBAC

When running with `METRICS_PROVIDER_MODE=kueue`, the workload needs read access
to `clusterqueues` and `cohorts` in the `kueue.x-k8s.io` API group. Prefer
creating a dedicated Kubernetes `ServiceAccount` via the chart
(`serviceAccount.create: true`) whenever `rbac.create` is enabled, so cluster
permissions are not bound to the namespace `default` ServiceAccount. See
`docs/environment-contracts.md` for env var contracts and alias precedence.
```

- [ ] **Step 2: Commit**

```bash
git add metrics/README.md && git commit -m "docs(metrics): point operators to Kueue RBAC and env contracts"
```

---

## Self-review (author checklist)

1. **Spec coverage:** Every consensus M2 item except performance and deferred
   M2b items maps to a task above (`live`, changelog, env precedence, quantity
   logging, RBAC, README).
2. **Placeholder scan:** No TBD steps; code blocks are complete.
3. **Type consistency:** `Settings(provider_mode=...)` matches Pydantic field
   name `provider_mode`.

---

## Execution handoff

**Plan complete and saved to** `metrics/docs/plans/PLAN_M2_post_review_feedback.md`.

**Two execution options:**

1. **Subagent-Driven (recommended)**: dispatch a fresh subagent per task; review between tasks.
2. **Inline Execution**: run tasks in this session with checkpoints between tasks.

**Which approach?**
