"""Application entrypoint."""

from __future__ import annotations

import logging

import uvicorn

from metrics.core.factory import create_app
from metrics.core.settings import Settings


def run() -> None:
    """Run the API server with environment configuration."""
    settings = Settings()
    # "trace" is a uvicorn level; the stdlib logger tree maps it to DEBUG.
    stdlib_level = {"trace": "debug"}.get(settings.log_level, settings.log_level)
    logging.getLogger("metrics").setLevel(stdlib_level.upper())
    app = create_app(settings=settings)
    uvicorn.run(
        app,
        host=settings.host,
        port=settings.port,
        log_level=settings.log_level,
        access_log=False,
        workers=1,
    )


if __name__ == "__main__":
    run()
