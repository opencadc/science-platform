"""Emit bounded structured logs with optional OpenTelemetry correlation.

Exception payloads and arbitrary record attributes are intentionally excluded
to keep logs predictable and avoid leaking upstream request details.
"""

from __future__ import annotations

import json
import logging
from datetime import UTC, datetime

from opentelemetry import trace


class JsonFormatter(logging.Formatter):
    """Serialize the approved log fields without exception payloads."""

    def format(self, record: logging.LogRecord) -> str:
        """Serialize one standard log record with active trace identifiers.

        Args:
            record: Standard-library log record.

        Returns:
            One compact JSON object.
        """
        context = trace.get_current_span().get_span_context()
        return json.dumps(
            {
                "timestamp": datetime.fromtimestamp(record.created, UTC).isoformat(),
                "severity": record.levelname,
                "logger": record.name,
                "message": record.getMessage(),
                "trace_id": f"{context.trace_id:032x}" if context.is_valid else "",
                "span_id": f"{context.span_id:016x}" if context.is_valid else "",
            },
            separators=(",", ":"),
        )


def configure_logging(level: str) -> logging.Handler:
    """Replace application handlers with one JSON stdout handler.

    Args:
        level: Configured standard or Uvicorn ``trace`` log level.

    Returns:
        The installed handler, allowing telemetry setup to retain ownership.
    """
    logger = logging.getLogger("metrics")
    logger.setLevel({"trace": "debug"}.get(level, level).upper())
    handler = logging.StreamHandler()
    handler.setFormatter(JsonFormatter())
    logger.handlers[:] = [handler]
    logger.propagate = False
    return handler
