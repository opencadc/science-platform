"""Pydantic contracts and validators for the portable harness."""

from __future__ import annotations

import json
import os
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Literal

import yaml
from pydantic import BaseModel, Field, model_validator

HookEvent = Literal[
    "session_start",
    "shell_command_start",
    "pre_file_write",
    "post_task_complete",
]

HookAdvisoryAction = Literal[
    "advisory_allow",
    "advisory_warn",
    "advisory_ask",
    "advisory_block",
]

HookAction = Literal[
    "advisory_allow",
    "advisory_warn",
    "advisory_ask",
    "advisory_block",
    "strict_allow",
    "strict_warn",
    "strict_block",
]

REQUIRED_MODULE_SECTIONS = [
    "purpose",
    "trigger",
    "inputs",
    "required outputs",
    "examples",
    "non-goals",
]

REVIEWER_SECTIONS = [
    "mission",
    "inputs",
    "output schema",
    "confidence rubric",
    "examples",
    "banned output patterns",
]


def load_yaml(path: Path) -> dict[str, Any]:
    """Load a YAML mapping from disk."""
    data = yaml.safe_load(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise ValueError(f"YAML file must contain a mapping: {path}")
    return data


class HookCheck(BaseModel):
    """Single hook check with a regex pattern and advisory action."""

    id: str
    event: HookEvent
    pattern: str
    action: HookAdvisoryAction
    message: str

    @model_validator(mode="after")
    def validate_regex(self) -> "HookCheck":
        """Ensure hook patterns compile before runtime evaluation."""
        re.compile(self.pattern)
        return self


class HookPolicy(BaseModel):
    """Hook policy shared by every adapter manifest (Codex, Cursor, Claude, ...)."""

    version: str
    mode: Literal["advisory", "strict"]
    source: str
    events: list[HookEvent]
    checks: list[HookCheck]
    session_start_message: str | None = None

    @model_validator(mode="after")
    def validate_unique_ids_and_events(self) -> "HookPolicy":
        """Ensure ids are unique and checks use declared events."""
        seen: set[str] = set()
        declared = set(self.events)
        for check in self.checks:
            if check.id in seen:
                raise ValueError(f"Duplicate hook check id: {check.id}")
            if check.event not in declared:
                raise ValueError(f"Check {check.id} uses undeclared event {check.event}")
            seen.add(check.id)
        if "session_start" in declared and not self.session_start_message:
            raise ValueError("session_start requires session_start_message")
        return self


class HookDecision(BaseModel):
    """Decision returned after evaluating an event payload."""

    check_id: str
    action: HookAction
    message: str


class RoutePolicy(BaseModel):
    """Capability-class route used by the agent harness."""

    model_class: str
    use_for: list[str] = Field(min_length=1)
    hot_path: bool | None = None
    effort: str | None = None
    effort_default: str | None = None
    effort_fallback: str | None = None
    precompression_model_class: str | None = None
    reserved_for: list[str] | None = None

    @model_validator(mode="after")
    def validate_effort_policy(self) -> "RoutePolicy":
        """Require each route to define an effort policy."""
        if not any([self.effort, self.effort_default, self.effort_fallback]):
            raise ValueError(f"Route {self.model_class} must define effort policy")
        return self


class EscalationPolicy(BaseModel):
    """Escalation triggers and baseline capability class."""

    start_class: str
    triggers: list[str] = Field(min_length=1)


class ContextAffordance(BaseModel):
    """Context-shaping guardrails before escalation."""

    forbid_raw_repo_to_expensive_models: bool
    required_summary_fields: list[str] = Field(min_length=1)


class HarnessAffordances(BaseModel):
    """Machine-readable affordances for routing."""

    context: ContextAffordance


class HarnessMetricsPolicy(BaseModel):
    """Metrics tracked by routing and operational checks."""

    optimize_for: str
    track: list[str] = Field(min_length=1)


class RouterPolicy(BaseModel):
    """Machine-readable capability routing policy."""

    version: int
    routing: dict[str, RoutePolicy]
    escalation: EscalationPolicy
    affordances: HarnessAffordances
    metrics: HarnessMetricsPolicy

    @model_validator(mode="after")
    def validate_required_routes(self) -> "RouterPolicy":
        """Require the standard route set from the harness contract."""
        required = {"plan", "implement", "verify", "research", "arbitrate"}
        missing = required.difference(self.routing)
        if missing:
            raise ValueError(f"Missing routing classes: {sorted(missing)}")
        return self


class ArtifactSchema(BaseModel):
    """Machine-readable handoff artifact schema."""

    version: int
    required_fields: list[str] = Field(min_length=1)
    stage_specific: dict[str, list[str]]

    @model_validator(mode="after")
    def validate_stages(self) -> "ArtifactSchema":
        """Require stage-specific fields for standard harness stages."""
        required = {"research", "plan", "implementation", "verification", "review", "arbitration"}
        missing = required.difference(self.stage_specific)
        if missing:
            raise ValueError(f"Missing artifact stages: {sorted(missing)}")
        return self


class ProjectGate(BaseModel):
    """Repository-specific validation gate."""

    id: str
    command: str
    evidence: list[str] = Field(min_length=1)
    required: bool = True
    failure_handling: str


class ProjectGates(BaseModel):
    """Collection of repository-owned validation gates."""

    version: int
    gates: list[ProjectGate] = Field(min_length=1)

    @model_validator(mode="after")
    def validate_unique_gate_ids(self) -> "ProjectGates":
        """Ensure gate ids are unique."""
        seen: set[str] = set()
        for gate in self.gates:
            if gate.id in seen:
                raise ValueError(f"Duplicate gate id: {gate.id}")
            seen.add(gate.id)
        return self


class MetricsSchema(BaseModel):
    """Operational metrics captured by the harness."""

    version: int
    metrics: dict[str, str]
    telemetry: dict[str, Any]


class ReviewFinding(BaseModel):
    """Reviewer finding payload required by arbitration."""

    id: str
    scope: str
    category: Literal["architecture", "reliability", "token", "scale"]
    severity: Literal["P0", "P1", "P2", "P3"]
    confidence: float = Field(ge=0.0, le=1.0)
    root_cause: str
    evidence: str
    risk: str
    action: str


def validate_review_findings(payload: list[dict[str, object]]) -> list[ReviewFinding]:
    """Validate raw reviewer payload against the shared schema."""
    return [ReviewFinding.model_validate(item) for item in payload]


def _risk_rank(action: str) -> int:
    """Return priority rank where lower values are higher risk."""
    order = {
        "advisory_block": 0,
        "advisory_ask": 1,
        "advisory_warn": 2,
        "advisory_allow": 3,
    }
    return order[action]


def _has_control_operators(payload: str) -> bool:
    """Detect compound or shell-control patterns in a payload string."""
    return bool(re.search(r"(&&|\|\||;|\||\$\(|`)", payload))


def _apply_mode(action: HookAdvisoryAction, mode: str) -> HookAction:
    """Map advisory actions to strict mode actions when required."""
    if mode == "advisory":
        return action
    mapping: dict[str, HookAction] = {
        "advisory_allow": "strict_allow",
        "advisory_warn": "strict_warn",
        "advisory_ask": "strict_block",
        "advisory_block": "strict_block",
    }
    return mapping[action]


def evaluate_hook_decision(policy: HookPolicy, event: str, payload: str) -> HookDecision:
    """Return the highest-risk matching hook decision for an event payload."""
    if event == "session_start":
        return HookDecision(
            check_id="SESSION-START-CONTEXT",
            action=_apply_mode("advisory_allow", policy.mode),
            message=policy.session_start_message or "Load the repository harness context.",
        )

    matches: list[HookCheck] = []
    for check in policy.checks:
        if check.event != event:
            continue
        if check.action == "advisory_allow" and _has_control_operators(payload):
            continue
        if re.search(check.pattern, payload):
            matches.append(check)

    if not matches:
        return HookDecision(
            check_id="DEFAULT-ALLOW",
            action=_apply_mode("advisory_allow", policy.mode),
            message="No matching checks. Event allowed.",
        )

    selected = sorted(matches, key=lambda item: _risk_rank(item.action))[0]
    return HookDecision(
        check_id=selected.id,
        action=_apply_mode(selected.action, policy.mode),
        message=selected.message,
    )


def markdown_sections(path: Path) -> set[str]:
    """Return lowercase Markdown heading names for a document."""
    sections: set[str] = set()
    for line in path.read_text(encoding="utf-8").splitlines():
        match = re.match(r"^#{2,6}\s+(.+?)\s*$", line)
        if match:
            sections.add(match.group(1).strip().lower())
    return sections


def validate_module_contract(path: Path) -> list[str]:
    """Return missing required module contract sections."""
    sections = markdown_sections(path)
    return [name for name in REQUIRED_MODULE_SECTIONS if name not in sections]


def validate_reviewer_persona(path: Path) -> list[str]:
    """Return missing required reviewer persona sections."""
    sections = markdown_sections(path)
    return [name for name in REVIEWER_SECTIONS if name not in sections]


def adapter_policy_from_json(path: Path) -> HookPolicy:
    """Load an adapter hook manifest that mirrors the canonical hook policy.

    Retained for compatibility with legacy mirror-style manifests. New adapters
    should use `AdapterManifest` with runtime-event bindings.
    """
    return HookPolicy.model_validate(json.loads(path.read_text(encoding="utf-8")))


class AdapterHookHandler(BaseModel):
    """Single tool-runtime handler bound to the canonical hook bridge."""

    command: str
    type: str | None = None
    matcher: str | None = None
    description: str | None = None
    statusMessage: str | None = None


class AdapterHookGroup(BaseModel):
    """Group of handlers for a single tool-runtime event."""

    matcher: str | None = None
    hooks: list[AdapterHookHandler] | None = None
    command: str | None = None
    description: str | None = None
    statusMessage: str | None = None

    @model_validator(mode="after")
    def validate_has_handler(self) -> "AdapterHookGroup":
        """Require either a direct command or a hooks list to be present."""
        if not self.hooks and not self.command:
            raise ValueError("Adapter hook group must define 'command' or 'hooks'")
        return self

    def commands(self) -> list[str]:
        """Return all command strings this group invokes."""
        if self.hooks:
            return [handler.command for handler in self.hooks]
        return [self.command] if self.command else []


class AdapterManifest(BaseModel):
    """Adapter hook manifest binding tool-runtime events to the bridge."""

    version: str
    source: str
    bridge: str
    tool: str
    hooks: dict[str, list[AdapterHookGroup]]

    @model_validator(mode="after")
    def validate_manifest(self) -> "AdapterManifest":
        """Require the bridge prefix to appear in every bound command."""
        bridge = self.bridge
        for runtime_event, groups in self.hooks.items():
            if not groups:
                raise ValueError(f"Adapter event {runtime_event} has no handlers")
            for group in groups:
                for command in group.commands():
                    if bridge not in command:
                        raise ValueError(
                            f"Adapter event {runtime_event} invokes {command!r} "
                            f"without the canonical bridge {bridge!r}"
                        )
                    if f"--tool {self.tool}" not in command:
                        raise ValueError(
                            f"Adapter event {runtime_event} must invoke the bridge with --tool {self.tool}"
                        )
        return self

    def canonical_events(self) -> set[str]:
        """Return the canonical harness events bound by this manifest."""
        events: set[str] = set()
        for groups in self.hooks.values():
            for group in groups:
                for command in group.commands():
                    for canonical in (
                        "session_start",
                        "shell_command_start",
                        "pre_file_write",
                        "post_task_complete",
                    ):
                        if f" {canonical} " in command or command.endswith(f" {canonical}"):
                            events.add(canonical)
        return events


def load_adapter_manifest(path: Path) -> AdapterManifest:
    """Load and validate an adapter hook manifest from JSON."""
    return AdapterManifest.model_validate(json.loads(path.read_text(encoding="utf-8")))


@dataclass(frozen=True)
class CheckResult:
    """Single validation result emitted by the harness checker."""

    name: str
    ok: bool
    detail: str


def maybe_write_telemetry(repo_root: Path, event: str, payload: dict[str, Any]) -> None:
    """Append optional JSONL telemetry when explicitly enabled."""
    if os.environ.get("HARNESS_TELEMETRY") != "1":
        return
    target = repo_root / ".harness" / "telemetry.jsonl"
    target.parent.mkdir(parents=True, exist_ok=True)
    with target.open("a", encoding="utf-8") as handle:
        handle.write(json.dumps({"event": event, **payload}, sort_keys=True) + "\n")
