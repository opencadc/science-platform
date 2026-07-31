from __future__ import annotations

import pytest

from metrics.core.settings import (
    KueueProviderConfig,
    ProviderConfigs,
    Settings,
    SourceConfig,
)
from metrics.providers.kueue import KueueProvider
from tests.fakes import FakeKueueApi


@pytest.mark.anyio
async def test_kueue_metrics_reads_nominal_for_platform() -> None:
    doc = {
        "spec": {
            "resourceGroups": [
                {
                    "flavors": [
                        {
                            "resources": [
                                {"name": "cpu", "nominalQuota": "100"},
                                {"name": "memory", "nominalQuota": "512Gi"},
                            ]
                        }
                    ]
                }
            ]
        }
    }

    settings = Settings(
        cluster_name="x",
        sources=SourceConfig(platform="kueue"),
        providers=ProviderConfigs(
            kueue=KueueProviderConfig(cluster_queues=["cq-test"]),
        ),
    )
    data = await KueueProvider(settings, api=FakeKueueApi({"cq-test": doc})).platform()
    assert "cpu" in data.capacity
    assert "memory" in data.capacity
