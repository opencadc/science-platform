"""Application entrypoint."""

from __future__ import annotations

import uvicorn

from metrics.core.factory import app
from metrics.core.settings import Settings, apply_metrics_package_log_level


def run() -> None:
    """Run the API server with environment configuration."""
    settings = Settings()
    apply_metrics_package_log_level(settings)
    raw = str(settings.log_level).strip().lower()
    uvicorn.run(
        app,
        host=settings.host,
        port=settings.port,
        log_level=raw,
    )


if __name__ == "__main__":
    run()
