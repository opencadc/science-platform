"""Define sanitized failures shared by providers, runtime, and HTTP adapters.

These exception types separate expected dependency and domain failures from
unexpected programming errors without exposing upstream details to clients.
"""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(slots=True)
class AppError(Exception):
    """Describe a safe application failure that maps to an HTTP response.

    Attributes:
        code: Internal stable identifier used for telemetry and logs.
        message: Sanitized description safe to retain inside the application.
        status_code: HTTP status returned by the API adapter.
        retry_after: Optional delay advertised through ``Retry-After``.
    """

    code: str
    message: str
    status_code: int
    retry_after: int | None = None


class ProviderUnavailableError(Exception):
    """Indicate that a configured provider cannot currently serve requests.

    This covers missing connectivity or an unusable client, rather than a
    malformed response from a provider that successfully ran.
    """


class ProviderExecutionError(Exception):
    """Indicate that a provider call or returned payload could not be used."""


class RuntimeStartupError(RuntimeError):
    """Indicate that required startup validation or dependency setup failed."""
