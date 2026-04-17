"""Composite provider implementations."""

from __future__ import annotations

from metrics.errors import ProviderExecutionError, ProviderUnavailableError
from metrics.models import CapacityReading
from metrics.providers.base import CapacityProvider


class FallbackCapacityProvider:
    """Try providers in order and return the first successful reading."""

    source_name = "capacity_fallback"

    def __init__(self, providers: list[CapacityProvider]) -> None:
        self._providers = providers

    async def get_capacity(self) -> CapacityReading:
        if not self._providers:
            raise ProviderUnavailableError("No capacity providers configured")

        errors: list[str] = []
        for provider in self._providers:
            try:
                return await provider.get_capacity()
            except ProviderUnavailableError as exc:
                errors.append(f"{provider.source_name}: {exc}")
                continue
            except ProviderExecutionError as exc:
                errors.append(f"{provider.source_name}: {exc}")
                continue

        error_message = "; ".join(errors) if errors else "No provider succeeded"
        raise ProviderUnavailableError(error_message)
