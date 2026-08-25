"""Installed ``metrics-dev`` kind and Helm lifecycle entrypoint."""

from __future__ import annotations

import argparse

from metrics.dev import stack

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
        command = subparsers.add_parser(name, help=f"Run the {name} lifecycle step.")
        if name in {"up", "smoke"}:
            command.add_argument(
                "--profile",
                choices=("core", "accounting"),
                default="core",
                help="optional dependency profile (default: core)",
            )
        if name == "destroy":
            command.add_argument(
                "--confirm",
                metavar="CONTEXT",
                help=f"required exact confirmation: {stack.KUBE_CONTEXT}",
            )
    return parser


def main(argv: list[str] | None = None) -> int:
    """Parse argv and dispatch one finite lifecycle command."""
    parser = build_parser()
    args = parser.parse_args(argv)
    actions = {
        "up": lambda: stack.up(args.profile),
        "run": stack.run_host,
        "image": stack.image,
        "fixtures": stack.fixtures,
        "smoke": lambda: stack.smoke(args.profile),
        "down": stack.down,
        "reset": stack.reset,
        "destroy": lambda: stack.destroy(args.confirm),
    }
    try:
        actions[args.command]()
    except stack.DevStackError as error:
        parser.error(str(error))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
