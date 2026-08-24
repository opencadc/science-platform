"""``metrics-dev`` entrypoint declared in ``pyproject.toml``.

Package 01 exposes the approved subcommands. Package 03 (CADC-16068) owns the
kind/Helm lifecycle implementations behind these names.
"""

from __future__ import annotations

import argparse
import sys

_COMMANDS = (
    "up",
    "run",
    "image",
    "fixtures",
    "smoke",
    "down",
    "reset",
    "destroy",
)


def build_parser() -> argparse.ArgumentParser:
    """Build the public ``metrics-dev`` argument parser."""
    parser = argparse.ArgumentParser(
        prog="metrics-dev",
        description="Local Metrics development and delivery commands.",
    )
    subparsers = parser.add_subparsers(dest="command", required=True)
    for name in _COMMANDS:
        subparsers.add_parser(name, help=f"Run the {name} lifecycle step.")
    return parser


def main(argv: list[str] | None = None) -> int:
    """Parse argv and dispatch a lifecycle command.

    Returns:
        Process exit status. Until the kind/Helm loop lands, unknown
        implementations exit non-zero so callers do not treat stubs as success.
    """
    parser = build_parser()
    args = parser.parse_args(argv)
    print(
        f"metrics-dev {args.command}: lifecycle implementation lands in CADC-16068",
        file=sys.stderr,
    )
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
