"""Provide the process entrypoint for the CANFAR Metrics API server."""

from __future__ import annotations

import logging.config
import os
import sys
from collections.abc import Mapping
from copy import deepcopy

import uvicorn
from pydantic import ValidationError

from metrics.core.factory import create_app
from metrics.core.settings import Settings, retired_environment

_CONFIGURATION_ERROR = 2
_REQUIRED_GROUPS = {
    "METRICS_CACHE": "METRICS_CACHE__KEY_SECRET",
    "METRICS_PROVIDERS": "METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES and __NAMESPACES",
    "METRICS_PROVIDERS__KUEUE": "METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES and __NAMESPACES",
}


def configuration_errors(environ: Mapping[str, str]) -> list[str]:
    """Return one line per configuration problem, naming variables but never values."""
    problems = retired_environment(environ)
    if problems:
        return problems
    try:
        Settings()
    except ValidationError as exc:
        for error in exc.errors(include_input=False, include_url=False):
            path = [str(part) for part in error["loc"] if not isinstance(part, int)]
            name = "METRICS_" + "__".join(part.upper() for part in path) if path else "METRICS_*"
            if error["type"] == "missing" and name in _REQUIRED_GROUPS:
                name = _REQUIRED_GROUPS[name]
            problems.append(f"{name}: {error['msg']}")
    return problems


def run() -> None:
    """Load environment settings and run one Uvicorn worker.

    The application owns cache single-flight state and lifecycle resources, so
    horizontal scaling is handled by separate pods rather than Uvicorn workers.
    Invalid configuration exits with status 2 and one line per problem.
    """
    problems = configuration_errors(os.environ)
    if problems:
        for problem in problems:
            print(f"metrics: invalid configuration: {problem}", file=sys.stderr)
        raise SystemExit(_CONFIGURATION_ERROR)
    settings = Settings()
    # "trace" is a uvicorn level; the stdlib logger tree maps it to DEBUG.
    stdlib_level = {"trace": "debug"}.get(settings.log_level, settings.log_level)
    log_config = deepcopy(uvicorn.config.LOGGING_CONFIG)
    log_config["loggers"]["metrics"] = {
        "handlers": ["default"],
        "level": stdlib_level.upper(),
        "propagate": False,
    }
    # Configure logging before the app is built so startup lines are kept.
    logging.config.dictConfig(log_config)
    app = create_app(settings=settings)
    uvicorn.run(
        app,
        host=settings.host,
        port=settings.port,
        log_level=settings.log_level,
        log_config=log_config,
        access_log=False,
        workers=1,
    )


if __name__ == "__main__":
    run()
