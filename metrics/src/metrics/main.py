"""Application entrypoint."""

from __future__ import annotations

import uvicorn

from metrics.app import app
from metrics.config import Settings


def run() -> None:
    """Run the API server with environment configuration."""
    settings = Settings()
    uvicorn.run(
        app,
        host=settings.host,
        port=settings.port,
        log_level="info",
    )


if __name__ == "__main__":
    run()
