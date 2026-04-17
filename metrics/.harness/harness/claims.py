"""Append-only work-claim registry for parallel harness dispatch."""

from __future__ import annotations

import argparse
import fnmatch
import json
import sys
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Literal
from uuid import uuid4

from pydantic import BaseModel, Field, model_validator

ClaimStatus = Literal["claimed", "in-progress", "released", "reclaimed"]

CLAIM_TTL = timedelta(minutes=30)
HEARTBEAT_TTL = timedelta(minutes=10)

MIRROR_SETS: dict[str, tuple[str, ...]] = {
    "adapter-hooks": (
        ".codex/hooks.json",
        ".cursor/hooks.json",
        ".claude/hooks.json",
    ),
    "adapter-agents": (
        ".codex/agents/*.md",
        ".cursor/agents/*.md",
        ".claude/agents/*.md",
    ),
    "routing-index": (
        "AGENTS.md",
        "docs/harness/index.md",
    ),
}


class ClaimRecord(BaseModel):
    """Single append-only claim record."""

    claim_id: str = Field(min_length=1)
    owner: str = Field(min_length=1)
    path_globs: list[str] = Field(min_length=1)
    mirror_sets: list[str] = Field(default_factory=list)
    status: ClaimStatus
    created_at: datetime
    expires_at: datetime
    heartbeat_at: datetime

    @model_validator(mode="after")
    def validate_claim(self) -> "ClaimRecord":
        """Reject recursive globs and unknown mirror-set ids."""
        recursive = [glob for glob in self.path_globs if "**" in glob]
        if recursive:
            raise ValueError(f"path_globs must not use implicit recursion: {recursive}")
        unknown = [item for item in self.mirror_sets if item not in MIRROR_SETS]
        if unknown:
            raise ValueError(f"unknown mirror_sets: {unknown}")
        return self

    def is_terminal(self) -> bool:
        """Return true when a claim no longer owns write scope."""
        return self.status in {"released", "reclaimed"}

    def is_stale(self, now: datetime) -> bool:
        """Return true when a claim missed heartbeat or expiry."""
        return self.expires_at <= now or self.heartbeat_at + HEARTBEAT_TTL < now


def _parse_now(raw: str | None) -> datetime:
    """Parse an optional ISO timestamp for deterministic tests."""
    if not raw:
        return datetime.now(UTC)
    value = raw.replace("Z", "+00:00")
    parsed = datetime.fromisoformat(value)
    if parsed.tzinfo is None:
        return parsed.replace(tzinfo=UTC)
    return parsed.astimezone(UTC)


def read_claims(path: Path) -> list[ClaimRecord]:
    """Load append-only claim records from disk."""
    if not path.exists():
        return []
    records: list[ClaimRecord] = []
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        if not line.strip():
            continue
        try:
            records.append(ClaimRecord.model_validate_json(line))
        except Exception as exc:
            raise ValueError(f"invalid claim record on line {line_number}: {exc}") from exc
    return records


def append_claim(path: Path, record: ClaimRecord) -> None:
    """Append a validated claim record to the registry."""
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as handle:
        handle.write(record.model_dump_json() + "\n")


def latest_claims(records: list[ClaimRecord]) -> dict[str, ClaimRecord]:
    """Return the latest append-only state by claim id."""
    latest: dict[str, ClaimRecord] = {}
    for record in records:
        latest[record.claim_id] = record
    return latest


def infer_mirror_sets(path_globs: list[str], explicit: list[str]) -> list[str]:
    """Infer mirror-set ownership from touched paths and explicit claims."""
    mirror_sets = set(explicit)
    for mirror_id, patterns in MIRROR_SETS.items():
        for claim_glob in path_globs:
            if any(_glob_overlap(claim_glob, pattern) for pattern in patterns):
                mirror_sets.add(mirror_id)
    return sorted(mirror_sets)


def active_claims(records: list[ClaimRecord]) -> list[ClaimRecord]:
    """Return the currently active claim states."""
    return [record for record in latest_claims(records).values() if not record.is_terminal()]


def _static_prefix(pattern: str) -> str:
    """Return the non-wildcard prefix of a glob pattern."""
    wildcard_positions = [pos for char in "*?[" if (pos := pattern.find(char)) >= 0]
    if not wildcard_positions:
        return pattern
    return pattern[: min(wildcard_positions)]


def _glob_overlap(left: str, right: str) -> bool:
    """Return true when two simple repository-relative globs can overlap."""
    if left == right:
        return True
    if not any(char in left for char in "*?[") and fnmatch.fnmatch(left, right):
        return True
    if not any(char in right for char in "*?[") and fnmatch.fnmatch(right, left):
        return True
    left_prefix = _static_prefix(left)
    right_prefix = _static_prefix(right)
    return bool(left_prefix and right_prefix) and (
        left_prefix.startswith(right_prefix) or right_prefix.startswith(left_prefix)
    )


def claims_overlap(left: ClaimRecord, right: ClaimRecord) -> bool:
    """Return true when two claims touch overlapping paths or mirror sets."""
    if set(left.mirror_sets).intersection(right.mirror_sets):
        return True
    for left_glob in left.path_globs:
        if any(_glob_overlap(left_glob, right_glob) for right_glob in right.path_globs):
            return True
    return False


def acquire_claim(
    claims_file: Path,
    owner: str,
    path_globs: list[str],
    mirror_sets: list[str],
    now: datetime,
) -> ClaimRecord:
    """Create a claim after verifying non-overlap against active records."""
    record = ClaimRecord(
        claim_id=uuid4().hex,
        owner=owner,
        path_globs=path_globs,
        mirror_sets=infer_mirror_sets(path_globs, mirror_sets),
        status="claimed",
        created_at=now,
        expires_at=now + CLAIM_TTL,
        heartbeat_at=now,
    )
    for active in active_claims(read_claims(claims_file)):
        if claims_overlap(record, active):
            state = "stale" if active.is_stale(now) else "active"
            raise ValueError(
                f"claim overlaps {state} claim {active.claim_id}; "
                "release or reclaim it before dispatch"
            )
    append_claim(claims_file, record)
    return record


def _transition_claim(
    claims_file: Path,
    claim_id: str,
    owner: str,
    status: ClaimStatus,
    now: datetime,
) -> ClaimRecord:
    """Append a lifecycle transition for an existing claim."""
    records = read_claims(claims_file)
    current = latest_claims(records).get(claim_id)
    if current is None:
        raise ValueError(f"unknown claim_id: {claim_id}")
    if status != "reclaimed" and current.owner != owner:
        raise ValueError(f"claim {claim_id} is owned by {current.owner}, not {owner}")
    if current.is_terminal():
        raise ValueError(f"claim {claim_id} is already {current.status}")
    if status == "reclaimed" and not current.is_stale(now):
        raise ValueError(f"claim {claim_id} is not stale or expired")
    record = ClaimRecord(
        claim_id=current.claim_id,
        owner=current.owner,
        path_globs=current.path_globs,
        mirror_sets=current.mirror_sets,
        status=status,
        created_at=current.created_at,
        expires_at=now + CLAIM_TTL if status == "in-progress" else current.expires_at,
        heartbeat_at=now,
    )
    append_claim(claims_file, record)
    return record


def heartbeat_claim(claims_file: Path, claim_id: str, owner: str, now: datetime) -> ClaimRecord:
    """Renew a claim heartbeat."""
    return _transition_claim(claims_file, claim_id, owner, "in-progress", now)


def release_claim(claims_file: Path, claim_id: str, owner: str, now: datetime) -> ClaimRecord:
    """Release a claim after completion or failure."""
    return _transition_claim(claims_file, claim_id, owner, "released", now)


def reclaim_claim(claims_file: Path, claim_id: str, owner: str, now: datetime) -> ClaimRecord:
    """Reclaim a stale or expired claim."""
    return _transition_claim(claims_file, claim_id, owner, "reclaimed", now)


def _emit(record: ClaimRecord) -> None:
    """Print a machine-readable claim record."""
    print(record.model_dump_json())


def add_claim_parser(subparsers: argparse._SubParsersAction[argparse.ArgumentParser]) -> None:
    """Register claim subcommands on the harness CLI parser."""
    parser = subparsers.add_parser("claim", help="Manage append-only work claims.")
    parser.add_argument(
        "--claims-file",
        default=Path(".harness/claims.jsonl"),
        type=Path,
        help="Path to the append-only claim registry.",
    )
    parser.add_argument("--now", help="ISO timestamp override for deterministic checks.")
    claim_subparsers = parser.add_subparsers(dest="claim_command", required=True)

    acquire = claim_subparsers.add_parser("acquire", help="Acquire a non-overlapping claim.")
    acquire.add_argument("--owner", required=True)
    acquire.add_argument("--path-glob", action="append", required=True)
    acquire.add_argument("--mirror-set", action="append", default=[])

    for name in ["heartbeat", "release", "reclaim"]:
        command = claim_subparsers.add_parser(name, help=f"{name.title()} an existing claim.")
        command.add_argument("--owner", required=True)
        command.add_argument("--claim-id", required=True)


def handle_claim_command(args: argparse.Namespace) -> int:
    """Execute a claim subcommand from parsed CLI arguments."""
    claims_file = args.claims_file.resolve()
    now = _parse_now(args.now)
    try:
        if args.claim_command == "acquire":
            record = acquire_claim(
                claims_file,
                args.owner,
                args.path_glob,
                args.mirror_set,
                now,
            )
        elif args.claim_command == "heartbeat":
            record = heartbeat_claim(claims_file, args.claim_id, args.owner, now)
        elif args.claim_command == "release":
            record = release_claim(claims_file, args.claim_id, args.owner, now)
        elif args.claim_command == "reclaim":
            record = reclaim_claim(claims_file, args.claim_id, args.owner, now)
        else:
            raise ValueError(f"unknown claim command: {args.claim_command}")
    except ValueError as exc:
        print(str(exc), file=sys.stderr)
        return 1
    _emit(record)
    return 0
