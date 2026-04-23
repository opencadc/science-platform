"""CLI for harness validation."""

from __future__ import annotations

import argparse
from pathlib import Path

from .claims import add_claim_parser, handle_claim_command
from .checks import has_failures, validate_all


def main(argv: list[str] | None = None) -> int:
    """Run the harness command line interface."""
    parser = argparse.ArgumentParser(prog="harness")
    parser.add_argument(
        "--repo-root",
        default=Path.cwd(),
        type=Path,
        help="Repository root to validate.",
    )
    subparsers = parser.add_subparsers(dest="command", required=True)
    subparsers.add_parser("check", help="Run harness validation checks.")
    add_claim_parser(subparsers)
    args = parser.parse_args(argv)

    if args.command == "check":
        results = validate_all(args.repo_root.resolve())
        width = max(len(result.name) for result in results)
        for result in results:
            status = "PASS" if result.ok else "FAIL"
            print(f"{status} {result.name:<{width}} {result.detail}")
        return 1 if has_failures(results) else 0
    if args.command == "claim":
        return handle_claim_command(args)

    return 2
