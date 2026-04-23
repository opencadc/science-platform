"""Repository-wide harness validation checks."""

from __future__ import annotations

import hashlib
import json
import re
import tempfile
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Iterable

from .claims import acquire_claim, reclaim_claim
from .contracts import (
    ArtifactSchema,
    CheckResult,
    HookPolicy,
    MetricsSchema,
    ProjectGates,
    RouterPolicy,
    evaluate_hook_decision,
    load_adapter_manifest,
    load_yaml,
    validate_module_contract,
    validate_reviewer_persona,
)

MODULES = [
    "index.md",
    "loop.md",
    "rules-core.md",
    "hooks.md",
    "routing.md",
    "artifacts.md",
    "verification.md",
    "subagents.md",
    "handoff-templates.md",
    "review-arbitration.md",
    "token-efficiency.md",
    "doc-gardening.md",
    "metrics.md",
    "learnings.md",
]

REVIEWERS = [
    "architecture-reviewer.md",
    "reliability-reviewer.md",
    "scale-reviewer.md",
    "token-efficiency-reviewer.md",
]

ADAPTER_DIRS = [".codex", ".cursor", ".claude"]
CANONICAL_PERSONA_DIR = Path("docs/harness/personas")

MODULE_SUBSTANCE: dict[str, dict[str, object]] = {
    "loop.md": {
        "min_lines": 50,
        "required_sections": ["loop steps", "operational controls", "stop/go gates"],
    },
    "rules-core.md": {
        "min_lines": 60,
        "required_sections": ["rules", "invariant policy"],
    },
    "hooks.md": {
        "min_lines": 60,
        "required_sections": [
            "default mode",
            "strict mode semantics",
            "risk-priority evaluation",
            "cross-tool event model",
            "escalation path from advisory to strict",
            "required evidence for any hook change",
        ],
    },
    "subagents.md": {
        "min_lines": 80,
        "required_sections": [
            "delegate when",
            "do not delegate when",
            "adaptive parallelism budget",
            "reserved scale-reviewer slot",
            "work-claim table",
            "mirror sets",
            "context-isolation policy",
            "token budget policy",
            "binary dispatch checklist",
        ],
    },
    "token-efficiency.md": {
        "min_lines": 50,
        "required_sections": [
            "retrieval hierarchy",
            "efficiency rules",
            "context budget",
        ],
    },
    "review-arbitration.md": {
        "min_lines": 60,
        "required_sections": [
            "finding schema",
            "required reviewer set",
            "review latency and quorum",
            "arbitration process",
            "publishing the decision log",
        ],
    },
    "handoff-templates.md": {
        "min_lines": 50,
        "required_sections": [
            "implementer template",
            "verifier template",
            "reviewer template",
            "scale-reviewer template",
            "subagent dispatch template",
            "arbitration template",
        ],
    },
    "index.md": {
        "min_lines": 25,
        "required_sections": ["precedence", "change control", "module map"],
    },
    "routing.md": {
        "min_lines": 25,
        "required_sections": ["escalation triggers", "retrieval hierarchy link"],
    },
    "artifacts.md": {
        "min_lines": 25,
        "required_sections": ["stage linkage", "required evidence"],
    },
    "verification.md": {
        "min_lines": 25,
        "required_sections": ["gate contract", "retrieval hierarchy link"],
    },
    "doc-gardening.md": {
        "min_lines": 25,
        "required_sections": ["when to run", "entropy signals"],
    },
    "metrics.md": {
        "min_lines": 25,
        "required_sections": ["schema linkage", "tuning actions"],
    },
    "learnings.md": {
        "min_lines": 25,
        "required_sections": ["current entries", "archive policy"],
    },
}

REQUIRED_POLICY_TOKENS = {
    "loop.md": ["WIP cap", "under 4 hours", "under 2 hours", "under 10%", "retrieval hierarchy"],
    "subagents.md": [
        "raw_cap = 1 + queue_size",
        "implementer_cap = max(0, cap - reserved_reviewer_slots)",
        "`claimed`",
        ".harness/claims.jsonl",
        "Mirror sets",
    ],
    "review-arbitration.md": [
        "30-minute",
        "3 of 4",
        "2 hours",
        "hard stop gate",
    ],
    "hooks.md": [
        "advisory_block",
        "advisory_ask",
        "advisory_warn",
        "advisory_allow",
    ],
    "rules-core.md": ["KISS", "DRY", "invariant"],
}


def _ok(name: str, detail: str = "ok") -> CheckResult:
    return CheckResult(name, True, detail)


def _fail(name: str, detail: str) -> CheckResult:
    return CheckResult(name, False, detail)


def _catch(name: str, fn) -> CheckResult:
    try:
        detail = fn()
        return _ok(name, str(detail) if detail else "ok")
    except Exception as exc:  # pragma: no cover - exercised through CLI paths
        return _fail(name, str(exc))


def validate_all(repo_root: Path) -> list[CheckResult]:
    """Run all harness contract checks and return structured results."""
    checks = [
        _catch("module contracts", lambda: check_module_contracts(repo_root)),
        _catch("module substance", lambda: check_module_substance(repo_root)),
        _catch("machine schemas", lambda: check_machine_schemas(repo_root)),
        _catch("adapter wiring", lambda: check_adapter_wiring(repo_root)),
        _catch("bridge runtime", lambda: check_bridge_runtime(repo_root)),
        _catch("claim runtime", lambda: check_claim_runtime()),
        _catch("reviewer personas", lambda: check_reviewer_personas(repo_root)),
        _catch("persona parity", lambda: check_persona_parity(repo_root)),
        _catch("hook scenarios", lambda: check_hook_scenarios(repo_root)),
        _catch("project gates", lambda: check_project_gates(repo_root)),
        _catch("docs ownership", lambda: check_docs_ownership(repo_root)),
        _catch("schema version", lambda: check_schema_version(repo_root)),
        _catch("metrics vocabulary", lambda: check_metrics_vocabulary(repo_root)),
        _catch("adapter map", lambda: check_adapter_map(repo_root)),
        _catch("agents routing index", lambda: check_agents_index(repo_root)),
        _catch("legacy harness layout", lambda: check_legacy_layout(repo_root)),
        _catch("absolute paths", lambda: check_no_absolute_paths(repo_root)),
    ]
    return checks


def check_module_contracts(repo_root: Path) -> str:
    """Validate canonical module existence, shape, and size."""
    missing: list[str] = []
    for name in MODULES:
        path = repo_root / "docs" / "harness" / name
        if not path.exists():
            missing.append(f"missing {name}")
            continue
        line_count = len(path.read_text(encoding="utf-8").splitlines())
        if line_count >= 500:
            missing.append(f"{name} has {line_count} lines")
        missing_sections = validate_module_contract(path)
        if missing_sections:
            missing.append(f"{name} missing {', '.join(missing_sections)}")
    if missing:
        raise ValueError("; ".join(missing))
    return f"{len(MODULES)} modules"


def check_module_substance(repo_root: Path) -> str:
    """Validate that canonical modules carry substantive policy content."""
    issues: list[str] = []
    for name, spec in MODULE_SUBSTANCE.items():
        path = repo_root / "docs" / "harness" / name
        content = path.read_text(encoding="utf-8")
        line_count = len(content.splitlines())
        if line_count < int(spec["min_lines"]):
            issues.append(f"{name} has {line_count} lines; floor {spec['min_lines']}")
        headings = {
            match.group(1).strip().lower()
            for match in re.finditer(r"^#{2,6}\s+(.+?)\s*$", content, re.MULTILINE)
        }
        for required in spec["required_sections"]:  # type: ignore[attr-defined]
            if required not in headings:
                issues.append(f"{name} missing section '{required}'")
        for token in REQUIRED_POLICY_TOKENS.get(name, []):
            if token not in content:
                issues.append(f"{name} missing policy token {token!r}")
    if issues:
        raise ValueError("; ".join(issues))
    return f"{len(MODULE_SUBSTANCE)} modules"


def check_machine_schemas(repo_root: Path) -> str:
    """Validate canonical YAML schemas."""
    RouterPolicy.model_validate(
        load_yaml(repo_root / "docs" / "harness" / "router-policy.yaml")
    )
    ArtifactSchema.model_validate(
        load_yaml(repo_root / "docs" / "harness" / "artifact-schema.yaml")
    )
    HookPolicy.model_validate(load_yaml(repo_root / "docs" / "harness" / "hook-policy.yaml"))
    MetricsSchema.model_validate(
        load_yaml(repo_root / "docs" / "harness" / "metrics-schema.yaml")
    )
    return "router, artifact, hook, metrics"


def _adapter_dirs(repo_root: Path) -> list[Path]:
    """Return existing adapter directories in canonical order."""
    return [repo_root / name for name in ADAPTER_DIRS if (repo_root / name).exists()]


def check_adapter_wiring(repo_root: Path) -> str:
    """Validate adapter hook manifests bind every canonical event to the bridge."""
    canonical_events = set(
        HookPolicy.model_validate(
            load_yaml(repo_root / "docs" / "harness" / "hook-policy.yaml")
        ).events
    )
    adapter_dirs = _adapter_dirs(repo_root)
    if not adapter_dirs:
        raise ValueError("No adapter directories discovered")
    tool_labels: list[str] = []
    for adapter in adapter_dirs:
        manifest_path = adapter / "hooks.json"
        if not manifest_path.exists():
            raise ValueError(f"Missing adapter manifest {manifest_path}")
        manifest = load_adapter_manifest(manifest_path)
        if manifest.tool != adapter.name.lstrip("."):
            raise ValueError(
                f"Adapter {manifest_path} declares tool {manifest.tool!r}; "
                f"expected {adapter.name.lstrip('.')!r}"
            )
        bound = manifest.canonical_events()
        missing = canonical_events.difference(bound)
        if missing:
            raise ValueError(
                f"Adapter {manifest_path} missing bindings for {sorted(missing)}"
            )
        tool_labels.append(manifest.tool)
    return f"{len(tool_labels)} adapters: {', '.join(tool_labels)}"


def check_hook_scenarios(repo_root: Path) -> str:
    """Validate hook fixture scenarios against canonical policy."""
    policy = HookPolicy.model_validate(
        load_yaml(repo_root / "docs" / "harness" / "hook-policy.yaml")
    )
    scenarios = json.loads(
        (repo_root / "docs" / "harness" / "fixtures" / "hooks" / "scenarios.json").read_text(
            encoding="utf-8"
        )
    )
    for scenario in scenarios:
        decision = evaluate_hook_decision(policy, scenario["event"], scenario["payload"])
        if decision.action != scenario["expected_action"]:
            raise ValueError(
                f"{scenario['name']}: expected {scenario['expected_action']}, got {decision.action}"
            )
    return f"{len(scenarios)} scenarios"


def check_reviewer_personas(repo_root: Path) -> str:
    """Validate canonical and adapter persona sections."""
    missing: list[str] = []
    canonical_dir = repo_root / CANONICAL_PERSONA_DIR
    for reviewer in REVIEWERS:
        path = canonical_dir / reviewer
        if not path.exists():
            missing.append(f"missing canonical persona {path}")
            continue
        missing_sections = validate_reviewer_persona(path)
        if missing_sections:
            missing.append(f"{path} missing {', '.join(missing_sections)}")
    adapter_dirs = _adapter_dirs(repo_root)
    if not adapter_dirs:
        raise ValueError("No adapter directories discovered")
    for adapter in adapter_dirs:
        agents_dir = adapter / "agents"
        if not agents_dir.exists():
            missing.append(f"missing agents dir {agents_dir}")
            continue
        for reviewer in REVIEWERS:
            path = agents_dir / reviewer
            if not path.exists():
                missing.append(f"missing {path}")
                continue
            missing_sections = validate_reviewer_persona(path)
            if missing_sections:
                missing.append(f"{path} missing {', '.join(missing_sections)}")
    if missing:
        raise ValueError("; ".join(missing))
    labels = [adapter.name.lstrip(".") for adapter in adapter_dirs]
    return ", ".join(labels)


def check_project_gates(repo_root: Path) -> str:
    """Validate repository-owned gate definitions."""
    raw = load_yaml(repo_root / "project-gates.yaml")
    gates = ProjectGates.model_validate(raw)
    commands = "\n".join(gate.command for gate in gates.gates)
    min_coverage = raw.get("min_coverage")
    if not isinstance(min_coverage, int) or min_coverage <= 0:
        raise ValueError("project-gates.yaml must declare integer min_coverage > 0")
    if f"--cov-fail-under={min_coverage}" not in commands:
        raise ValueError(
            f"coverage gate command must include --cov-fail-under={min_coverage}"
        )
    if "python -m harness check" not in commands:
        raise ValueError("harness CLI gate missing")
    return f"{len(gates.gates)} gates (min_coverage={min_coverage})"


def check_docs_ownership(repo_root: Path) -> str:
    """Validate project docs live in docs/ and harness docs live in docs/harness/."""
    required = [
        repo_root / "docs" / "architecture.md",
        repo_root / "docs" / "design.md",
        repo_root / "docs" / "specs.md",
        repo_root / "docs" / "learnings.md",
        repo_root / "docs" / "harness" / "learnings.md",
    ]
    missing = [str(path.relative_to(repo_root)) for path in required if not path.exists()]
    forbidden = [
        repo_root / "architecture.md",
        repo_root / "design.md",
        repo_root / "specs.md",
        repo_root / "learnings.md",
    ]
    present = [str(path.relative_to(repo_root)) for path in forbidden if path.exists()]
    if missing or present:
        details: list[str] = []
        if missing:
            details.append(f"missing docs: {missing}")
        if present:
            details.append(f"root project docs are not allowed: {present}")
        raise ValueError("; ".join(details))
    return "project docs in docs/, harness docs in docs/harness/"


def check_persona_parity(repo_root: Path) -> str:
    """Assert adapter persona cards hash-match the canonical harness set."""
    canonical_dir = repo_root / CANONICAL_PERSONA_DIR
    if not canonical_dir.exists():
        raise ValueError(f"canonical persona dir missing: {canonical_dir}")
    canonical = {
        reviewer: (canonical_dir / reviewer).read_bytes() for reviewer in REVIEWERS
    }
    mismatches: list[str] = []
    for adapter_name in ADAPTER_DIRS:
        adapter_dir = repo_root / adapter_name
        if not adapter_dir.exists():
            continue
        agents_dir = adapter_dir / "agents"
        for reviewer, expected in canonical.items():
            path = agents_dir / reviewer
            if not path.exists():
                mismatches.append(f"{path} missing")
                continue
            if path.read_bytes() != expected:
                mismatches.append(f"{path} drifts from {CANONICAL_PERSONA_DIR}")
    if mismatches:
        raise ValueError("; ".join(mismatches))
    return f"{len(REVIEWERS)} canonical reviewers x {len(ADAPTER_DIRS)} adapters"


def check_bridge_runtime(repo_root: Path) -> str:
    """Invoke the hook bridge per adapter so argparse rejection fails the gate."""
    import subprocess
    import sys as _sys

    adapters = _adapter_dirs(repo_root)
    if not adapters:
        raise ValueError("No adapter directories discovered")
    for adapter in adapters:
        manifest = load_adapter_manifest(adapter / "hooks.json")
        result = subprocess.run(
            [_sys.executable, "-m", "harness.hooks.bridge", "session_start", "--tool", manifest.tool],
            cwd=str(repo_root),
            input="{}",
            capture_output=True,
            text=True,
            check=False,
        )
        if result.returncode != 0:
            raise ValueError(
                f"bridge session_start --tool {manifest.tool} failed: "
                f"exit {result.returncode}; stderr: {result.stderr.strip()}"
            )
        try:
            payload = json.loads(result.stdout)
        except json.JSONDecodeError as exc:
            raise ValueError(
                f"bridge --tool {manifest.tool} emitted non-JSON: {result.stdout!r}"
            ) from exc
        if not isinstance(payload, dict) or not payload:
            raise ValueError(f"bridge --tool {manifest.tool} emitted empty decision")
    return f"{len(adapters)} tools invoked"


def check_claim_runtime() -> str:
    """Exercise claim overlap and stale reclaim behavior."""
    now = datetime(2026, 4, 17, 12, 0, tzinfo=UTC)
    with tempfile.TemporaryDirectory() as tmpdir:
        claims_file = Path(tmpdir) / "claims.jsonl"
        first = acquire_claim(
            claims_file,
            "agent-a",
            [".codex/hooks.json"],
            [],
            now,
        )
        try:
            acquire_claim(
                claims_file,
                "agent-b",
                [".cursor/hooks.json"],
                [],
                now,
            )
        except ValueError as exc:
            if "overlaps active claim" not in str(exc):
                raise
        else:
            raise ValueError("mirror-set overlap was not blocked")
        reclaim_claim(claims_file, first.claim_id, "agent-a", now + timedelta(minutes=31))
        acquire_claim(
            claims_file,
            "agent-b",
            [".cursor/hooks.json"],
            [],
            now + timedelta(minutes=32),
        )
    return "overlap and reclaim"


def _hash_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()[:16]


_SCHEMA_VERSION_SOURCES = {
    "hook-policy": Path("docs/harness/hook-policy.yaml"),
    "router-policy": Path("docs/harness/router-policy.yaml"),
    "artifact-schema": Path("docs/harness/artifact-schema.yaml"),
    "metrics-schema": Path("docs/harness/metrics-schema.yaml"),
    "project-gates": Path("project-gates.yaml"),
}


def check_schema_version(repo_root: Path) -> str:
    """Validate each machine schema declares a version tracked in CHANGELOG.md."""
    changelog = (repo_root / "docs" / "harness" / "CHANGELOG.md").read_text(encoding="utf-8")
    summary: list[str] = []
    missing: list[str] = []
    for label, rel_path in _SCHEMA_VERSION_SOURCES.items():
        data = load_yaml(repo_root / rel_path)
        version = str(data.get("version", ""))
        if not version:
            missing.append(f"{label} missing version")
            continue
        marker = f"{label} {version}"
        if marker not in changelog:
            missing.append(f"CHANGELOG.md missing entry for {marker!r}")
            continue
        summary.append(marker)
    if missing:
        raise ValueError("; ".join(missing))
    return ", ".join(summary)


def check_metrics_vocabulary(repo_root: Path) -> str:
    """Assert `router-policy.yaml` only tracks metrics declared in `metrics-schema.yaml`."""
    router = load_yaml(repo_root / "docs" / "harness" / "router-policy.yaml")
    schema = load_yaml(repo_root / "docs" / "harness" / "metrics-schema.yaml")
    tracked = router.get("metrics", {}).get("track") or []
    declared = set((schema.get("metrics") or {}).keys())
    if not isinstance(tracked, list) or not tracked:
        raise ValueError("router-policy.yaml metrics.track must be a non-empty list")
    unknown = [name for name in tracked if name not in declared]
    if unknown:
        raise ValueError(
            "router-policy.yaml metrics.track references undeclared metrics: "
            + ", ".join(sorted(set(unknown)))
        )
    return f"{len(tracked)} tracked metrics"


def check_adapter_map(repo_root: Path) -> str:
    """Assert every top-level adapter directory on disk is registered in ADAPTER_DIRS."""
    discovered: list[str] = []
    for candidate in sorted(repo_root.iterdir()):
        if not candidate.is_dir():
            continue
        if not candidate.name.startswith("."):
            continue
        if candidate.name in {".git", ".github", ".gitlab", ".venv", ".cache",
                              ".pytest_cache", ".ruff_cache", ".harness", ".idea",
                              ".vscode", ".ropeproject", ".mypy_cache", ".tox"}:
            continue
        if (candidate / "hooks.json").exists() or (candidate / "agents").exists():
            discovered.append(candidate.name)
    missing = [name for name in discovered if name not in ADAPTER_DIRS]
    if missing:
        raise ValueError(
            f"adapter directories not registered in ADAPTER_DIRS: {missing}"
        )
    return f"{len(discovered)} adapters on disk"


def check_agents_index(repo_root: Path) -> str:
    """Assert `AGENTS.md` links every canonical module in MODULES."""
    agents = (repo_root / "AGENTS.md").read_text(encoding="utf-8")
    missing = [name for name in MODULES if f"docs/harness/{name}" not in agents]
    if missing:
        raise ValueError(f"AGENTS.md missing canonical module links: {missing}")
    return f"{len(MODULES)} modules linked"


def check_legacy_layout(repo_root: Path) -> str:
    """Ensure old hidden Python/test entrypoints were retired."""
    retired = [
        repo_root / ".harness" / "contracts.py",
        repo_root / ".harness" / "tests" / "contracts.py",
        repo_root / ".harness" / "harness" / "hooks" / "codex.py",
    ]
    existing = [str(path) for path in retired if path.exists()]
    if existing:
        raise ValueError(f"retired files still exist: {existing}")
    return "retired files absent"


_ABSOLUTE_PATH_PATTERN = re.compile(r"(?:^|[\s(`'\"])(/Users/|/home/|C:\\\\)")


def check_no_absolute_paths(repo_root: Path) -> str:
    """Forbid absolute machine-local paths in harness docs and adapters."""
    offenders: list[str] = []
    scan_roots = [
        repo_root / "docs" / "harness",
        repo_root / ".codex",
        repo_root / ".cursor",
        repo_root / ".claude",
        repo_root / "AGENTS.md",
    ]
    for scan_root in scan_roots:
        if not scan_root.exists():
            continue
        if scan_root.is_file():
            files = [scan_root]
        else:
            files = [path for path in scan_root.rglob("*") if path.is_file()]
        for path in files:
            if path.suffix not in {".md", ".mdc", ".json", ".yaml", ".yml", ".py"}:
                continue
            if ".pytest_cache" in path.parts or "__pycache__" in path.parts:
                continue
            if "state" in path.parts and path.suffix == ".json":
                continue
            text = path.read_text(encoding="utf-8", errors="replace")
            if _ABSOLUTE_PATH_PATTERN.search(text):
                offenders.append(str(path.relative_to(repo_root)))
    if offenders:
        raise ValueError(f"absolute paths in {offenders}")
    return "none"


def has_failures(results: Iterable[CheckResult]) -> bool:
    """Return true when any validation check failed."""
    return any(not result.ok for result in results)
