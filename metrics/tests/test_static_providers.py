from __future__ import annotations

import pytest

from metrics.config import Settings
from metrics.providers.static import StaticCapacityProvider, StaticUsageProvider


@pytest.mark.anyio
async def test_static_capacity_provider_uses_settings_values() -> None:
    provider = StaticCapacityProvider(
        Settings(
            provider_mode="static",
            static_capacity_cpu_cores=64,
            static_capacity_memory_gib=256,
        )
    )
    reading = await provider.get_capacity()
    assert reading.cpu_cores == 64
    assert reading.memory_gib == 256
    assert reading.source == "static-capacity"


@pytest.mark.anyio
async def test_static_usage_provider_supports_all_scopes() -> None:
    provider = StaticUsageProvider(
        Settings(
            provider_mode="static",
            static_usage_cpu_cores=40,
            static_usage_memory_gib=80,
            static_user_usage_fraction=0.2,
            static_session_usage_fraction=0.1,
        )
    )
    platform = await provider.get_usage()
    user = await provider.get_usage_for_user("alice")
    session = await provider.get_usage_for_session("alice", "session-1")

    assert platform.requested_cpu_cores == 40
    assert platform.requested_memory_gib == 80
    assert user.requested_cpu_cores > session.requested_cpu_cores > 0
    assert user.source == "static-usage:user"
    assert session.source == "static-usage:session"
