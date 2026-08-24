"""Safety contracts for the local kind lifecycle."""

from __future__ import annotations

import subprocess

import pytest

from metrics.dev import stack


def _safe_output(command: list[str]) -> str:
    """Return the approved identity for guard commands."""
    if command == ["kubectl", "config", "current-context"]:
        return stack.KUBE_CONTEXT
    if command == ["kind", "get", "clusters"]:
        return stack.KIND_CLUSTER
    if "config" in command and "view" in command:
        return stack.KUBE_CONTEXT
    if "get" in command and "nodes" in command:
        return stack.CONTROL_PLANE
    raise AssertionError(command)


def test_context_guard_accepts_only_the_metrics_kind_identity(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(stack, "_output", _safe_output)
    stack.assert_safe_context()


@pytest.mark.parametrize(
    ("command_key", "unsafe_value"),
    [
        ("current", "keel-prod"),
        ("cluster", "keel-prod"),
        ("node", "keel-prod-control-plane"),
    ],
)
def test_context_guard_fails_closed_for_unrelated_targets(
    monkeypatch: pytest.MonkeyPatch, command_key: str, unsafe_value: str
) -> None:
    def unsafe_output(command: list[str]) -> str:
        if command == ["kubectl", "config", "current-context"] and command_key == "current":
            return unsafe_value
        if "config" in command and "view" in command and command_key == "cluster":
            return unsafe_value
        if "get" in command and "nodes" in command and command_key == "node":
            return unsafe_value
        return _safe_output(command)

    monkeypatch.setattr(stack, "_output", unsafe_output)
    with pytest.raises(stack.DevStackError, match="refusing"):
        stack.assert_safe_context()


def test_destroy_requires_confirmation_before_exact_delete(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    calls: list[list[str]] = []
    monkeypatch.setattr(stack, "assert_safe_context", lambda: None)

    def record(command: list[str], **_kwargs: object) -> subprocess.CompletedProcess[str]:
        calls.append(list(command))
        return subprocess.CompletedProcess(command, 0, "", "")

    monkeypatch.setattr(stack, "_run", record)
    with pytest.raises(stack.DevStackError, match="--confirm kind-metrics"):
        stack.destroy(None)
    assert calls == []

    stack.destroy(stack.KUBE_CONTEXT)
    assert calls == [["kind", "delete", "cluster", "--name", stack.KIND_CLUSTER]]


def test_local_stack_versions_are_pinned() -> None:
    assert stack.KIND_VERSION == "0.32.0"
    assert stack.KIND_NODE_IMAGE.endswith(
        "sha256:3f5c8443c620245e4d355cfe09e96a91ead32ceaa569d3f1ca9edf0cb2fe2ff4"
    )
    assert stack.KUEUE_VERSION == "0.19.2"
