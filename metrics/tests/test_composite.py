from __future__ import annotations

import pytest

from metrics.errors import ProviderExecutionError, ProviderUnavailableError
from metrics.models import CapacityReading
from metrics.providers.composite import FallbackCapacityProvider


class ProviderUnavailable:
    source_name = "kueue"

    async def get_capacity(self) -> CapacityReading:
        raise ProviderUnavailableError("not configured")


class ProviderBroken:
    source_name = "nodes"

    async def get_capacity(self) -> CapacityReading:
        raise ProviderExecutionError("timeout")


class ProviderWorking:
    source_name = "nodes"

    async def get_capacity(self) -> CapacityReading:
        return CapacityReading(
            cpu_cores=5,
            memory_gib=10,
            source=self.source_name,
            observed_at="2026-01-01T00:00:00Z",
        )


@pytest.mark.anyio
async def test_fallback_provider_returns_first_success() -> None:
    provider = FallbackCapacityProvider([ProviderUnavailable(), ProviderWorking()])
    reading = await provider.get_capacity()

    assert reading.cpu_cores == 5
    assert reading.memory_gib == 10


@pytest.mark.anyio
async def test_fallback_provider_raises_when_all_fail() -> None:
    provider = FallbackCapacityProvider([ProviderUnavailable(), ProviderBroken()])

    with pytest.raises(ProviderUnavailableError):
        await provider.get_capacity()
