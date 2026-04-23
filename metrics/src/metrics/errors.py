"""Domain errors used across providers and API handlers."""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(slots=True)
class AppError(Exception):
    """Base application error that maps to API error responses."""

    code: str
    message: str
    status_code: int
    details: dict[str, str] | None = None


class ProviderUnavailableError(Exception):
    """Raised when a provider cannot be used in the current environment."""


class ProviderExecutionError(Exception):
    """Raised when a provider fails unexpectedly during execution."""
