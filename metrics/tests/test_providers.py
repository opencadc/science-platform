from __future__ import annotations

import httpx
import pytest

from metrics.core.settings import (
    KueueProviderConfig,
    ProviderConfigs,
    Settings,
    SourceConfig,
)
from metrics.providers.kueue import KueueMetrics


@pytest.mark.anyio
async def test_kueue_metrics_reads_nominal_for_platform(monkeypatch) -> None:
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
    cohort = {
        "metadata": {"name": "cohort-x"},
        "spec": {"resourceGroups": []},
    }

    async def fake_parallel(_c, urls: list[str], *, headers, **_kwargs: object) -> list[dict]:
        out = []
        for u in urls:
            if u.endswith("/clusterqueues/cq-test"):
                out.append(doc)
            elif u.endswith("/cohorts/cx"):
                out.append(cohort)
        return out

    monkeypatch.setattr("metrics.providers.kueue.kube_parallel_get_json", fake_parallel)
    monkeypatch.setattr("metrics.providers.kueue.resolve_kube_token", lambda *a, **k: "t")

    settings = Settings(
        cluster_name="x",
        sources=SourceConfig(platform="kueue"),
        providers=ProviderConfigs(
            kueue=KueueProviderConfig(
                kube_api_url="https://kube.local",
                cluster_queues=["cq-test"],
                cohort="cx",
            ),
        ),
    )
    c = httpx.AsyncClient()
    try:
        km = KueueMetrics(settings=settings, client=c, kueue_config=settings.providers.kueue)
        data = await km.platform()
    finally:
        await c.aclose()
    assert "cpu" in data.capacity
    assert "memory" in data.capacity
