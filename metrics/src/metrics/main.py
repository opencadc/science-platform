"""Application entrypoint."""

from __future__ import annotations

import uvicorn

from metrics.core.factory import app
from metrics.core.settings import Settings


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
