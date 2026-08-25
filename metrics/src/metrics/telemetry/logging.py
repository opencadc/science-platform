"""Structured stdout logging with OpenTelemetry correlation."""

from __future__ import annotations

import json
import logging
from datetime import UTC, datetime

from opentelemetry import trace


class JsonFormatter(logging.Formatter):
    """Serialize a bounded log envelope without exception payloads."""

    def format(self, record: logging.LogRecord) -> str:
        """Return one compact JSON object."""
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
    """Install one JSON stdout handler on the application logger."""
    logger = logging.getLogger("metrics")
    logger.setLevel({"trace": "debug"}.get(level, level).upper())
    handler = logging.StreamHandler()
    handler.setFormatter(JsonFormatter())
    logger.handlers[:] = [handler]
    logger.propagate = False
    return handler
