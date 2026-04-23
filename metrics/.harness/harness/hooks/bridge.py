"""Tool-agnostic hook bridge backed by the canonical hook policy.

Adapter manifests (Codex `.codex/hooks.json`, Cursor `.cursor/hooks.json`,
Claude `.claude/hooks.json`, and any future adapter) bind tool-runtime
events to this bridge. The bridge loads `docs/harness/hook-policy.yaml`,
evaluates the event payload, writes optional telemetry, and emits a
tool-shaped decision including the matched check id for auditability.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path
from typing import Any

from harness.contracts import (
    HookDecision,
    HookPolicy,
    evaluate_hook_decision,
    load_yaml,
    maybe_write_telemetry,
)

HIGH_RISK_EMPTY_PAYLOAD_EVENTS = {"shell_command_start", "pre_file_write"}

SUPPORTED_EVENTS = [
    "session_start",
    "shell_command_start",
    "pre_file_write",
    "post_task_complete",
]


def _load_payload() -> dict[str, Any]:
    """Load a hook payload from stdin, tolerating empty or malformed input."""
    try:
        data = json.load(sys.stdin)
    except Exception:
        return {}
    return data if isinstance(data, dict) else {}


def _payload_text(payload: dict[str, Any]) -> str:
    """Extract command or path text from a normalized hook payload."""
    tool_input = payload.get("tool_input", {})
    if isinstance(tool_input, dict):
        for key in ["command", "path", "file_path", "text"]:
            value = tool_input.get(key)
            if value:
                return str(value)
    for key in ["command", "path", "file_path", "text"]:
        value = payload.get(key)
        if value:
            return str(value)
    return ""


def _audit_message(check_id: str, message: str) -> str:
    """Attach the matched check id to the human-facing message for auditability."""
    return f"[{check_id}] {message}"


def _codex_output(event: str, action: str, message: str, check_id: str) -> dict[str, Any]:
    """Format a decision in the Codex hook-output shape."""
    audit = _audit_message(check_id, message)
    if event == "session_start":
        return {
            "hookSpecificOutput": {
                "hookEventName": "SessionStart",
                "additionalContext": audit,
            }
        }
    if action in {"advisory_block", "strict_block"}:
        return {
            "hookSpecificOutput": {
                "hookEventName": "PreToolUse",
                "permissionDecision": "deny",
                "permissionDecisionReason": audit,
            },
            "systemMessage": audit,
        }
    return {"systemMessage": audit}


def _cursor_output(event: str, action: str, message: str, check_id: str) -> dict[str, Any]:
    """Format a decision in the Cursor hook-output shape."""
    permission = "allow"
    if action in {"advisory_block", "strict_block"}:
        permission = "deny"
    elif action in {"advisory_ask"}:
        permission = "ask"
    return {
        "event": event,
        "permission": permission,
        "action": action,
        "check_id": check_id,
        "message": _audit_message(check_id, message),
    }


def _claude_output(event: str, action: str, message: str, check_id: str) -> dict[str, Any]:
    """Format a decision in the Claude Code hook-output shape."""
    audit = _audit_message(check_id, message)
    if event == "session_start":
        return {"continue": True, "systemMessage": audit}
    if action in {"advisory_block", "strict_block"}:
        return {"decision": "block", "reason": audit}
    if action in {"advisory_ask"}:
        return {"decision": "ask", "reason": audit}
    return {"decision": "approve", "reason": audit}


def _generic_output(event: str, action: str, message: str, check_id: str) -> dict[str, Any]:
    """Format a decision in a neutral shape for new adapters."""
    return {
        "event": event,
        "action": action,
        "check_id": check_id,
        "message": _audit_message(check_id, message),
    }


SHAPERS: dict[str, Any] = {
    "codex": _codex_output,
    "cursor": _cursor_output,
    "claude": _claude_output,
    "generic": _generic_output,
}


def format_decision(
    tool: str, event: str, action: str, message: str, check_id: str
) -> dict[str, Any]:
    """Return a tool-appropriate decision payload using the shaper registry."""
    shaper = SHAPERS.get(tool, _generic_output)
    return shaper(event, action, message, check_id)


def _empty_payload_decision(event: str, policy: HookPolicy) -> HookDecision:
    """Return a fail-safe decision when the bridge cannot extract payload text."""
    if event in HIGH_RISK_EMPTY_PAYLOAD_EVENTS:
        mapping = {
            "advisory": "advisory_warn",
            "strict": "strict_warn",
        }
        return HookDecision(
            check_id="EMPTY-PAYLOAD-WARN",
            action=mapping[policy.mode],
            message=(
                "Hook payload was empty or unreadable for a high-risk event. "
                "Confirm the tool runtime is wired correctly."
            ),
        )
    return HookDecision(
        check_id="EMPTY-PAYLOAD-ALLOW",
        action="advisory_allow" if policy.mode == "advisory" else "strict_allow",
        message="Empty payload on a non-high-risk event allowed.",
    )


def _apply_mode_override(policy: HookPolicy, mode: str | None) -> HookPolicy:
    """Return policy with a runtime mode override when requested."""
    override = mode or os.environ.get("HARNESS_HOOK_MODE")
    if not override:
        return policy
    if override not in {"advisory", "strict"}:
        raise ValueError("HARNESS_HOOK_MODE must be 'advisory' or 'strict'")
    return HookPolicy.model_validate({**policy.model_dump(), "mode": override})


def main(argv: list[str] | None = None) -> int:
    """Evaluate a hook event from any supported adapter using canonical policy."""
    parser = argparse.ArgumentParser(prog="python -m harness.hooks.bridge")
    parser.add_argument("event", choices=SUPPORTED_EVENTS)
    parser.add_argument(
        "--tool",
        default="generic",
        help="Adapter tool invoking the bridge. Any string is accepted; unknown tools fall back to the generic output shape.",
    )
    parser.add_argument("--repo-root", default=Path.cwd(), type=Path)
    parser.add_argument(
        "--mode",
        choices=["advisory", "strict"],
        help="Runtime hook mode override for CI or unattended runs.",
    )
    args = parser.parse_args(argv)

    repo_root = args.repo_root.resolve()
    policy = _apply_mode_override(
        HookPolicy.model_validate(
            load_yaml(repo_root / "docs" / "harness" / "hook-policy.yaml")
        ),
        args.mode,
    )
    raw_payload = _load_payload()
    payload_text = _payload_text(raw_payload)
    if args.event == "session_start" or payload_text:
        decision = evaluate_hook_decision(policy, args.event, payload_text)
    else:
        decision = _empty_payload_decision(args.event, policy)
    maybe_write_telemetry(
        repo_root,
        args.event,
        {
            "tool": args.tool,
            "check_id": decision.check_id,
            "action": decision.action,
            "message": decision.message,
        },
    )
    print(
        json.dumps(
            format_decision(
                args.tool,
                args.event,
                decision.action,
                decision.message,
                decision.check_id,
            )
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
