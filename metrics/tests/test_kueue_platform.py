from __future__ import annotations

import pytest

from metrics.core.settings import (
    PlatformKueueSettings,
    PlatformSettings,
    Settings,
)
from metrics.providers.kueue_platform import KueuePlatformEngine


@pytest.mark.anyio
async def test_kueue_platform_engine_aggregates_queues_and_cohort(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    settings = Settings(
        platform=PlatformSettings(
            kueue=PlatformKueueSettings(
                kube_api_url="https://kube.test",
                cluster_queues=["cq-a", "cq-b"],
                cohort="cohort-atom",
            ),
        ),
    )

    cq_a = {
        "spec": {
            "resourceGroups": [
                {
                    "flavors": [
                        {
                            "resources": [
                                {"name": "cpu", "nominalQuota": "10"},
                                {"name": "memory", "nominalQuota": "20Gi"},
                            ]
                        }
                    ]
                }
            ]
        },
        "status": {
            "flavorsUsage": [
                {
                    "resources": [
                        {"name": "cpu", "total": "1", "borrowed": "500m"},
                        {
                            "name": "memory",
                            "total": "2Gi",
                            "borrowed": "1Gi",
                        },
                    ]
                }
            ]
        },
    }
    cq_b = {
        "spec": {
            "resourceGroups": [
                {
                    "flavors": [
                        {
                            "resources": [
                                {"name": "cpu", "nominalQuota": "5"},
                                {"name": "memory", "nominalQuota": "8Gi"},
                            ]
                        }
                    ]
                }
            ]
        },
        "status": {"flavorsUsage": []},
    }
    cohort = {
        "metadata": {"name": "cohort-atom"},
        "spec": {
            "resourceGroups": [
                {
                    "flavors": [
                        {
                            "resources": [
                                {"name": "cpu", "nominalQuota": "3"},
                                {"name": "memory", "nominalQuota": "4Gi"},
                            ]
                        }
                    ]
                }
            ]
        },
    }

    async def fake_parallel(urls: list[str], **_kwargs):
        out: list[dict] = []
        for url in urls:
            if url.endswith("/clusterqueues/cq-a"):
                out.append(cq_a)
            elif url.endswith("/clusterqueues/cq-b"):
                out.append(cq_b)
            elif url.endswith("/cohorts/cohort-atom"):
                out.append(cohort)
            else:
                raise AssertionError(f"unexpected url {url!r}")
        return out

    monkeypatch.setattr(
        "metrics.providers.kueue_platform.kube_parallel_get_json",
        fake_parallel,
    )

    engine = KueuePlatformEngine(settings)
    maps = await engine.collect()

    assert maps.capacity["cpu"] == "18"
    assert maps.allocated["cpu"] == "1"
    assert maps.allocated["memory"] == "2Gi"
    assert "memory" in maps.capacity
    assert "memory" in maps.allocated


@pytest.mark.anyio
async def test_kueue_platform_subcore_cpu_uses_cores_in_capacity_and_allocated(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """100m in usage must not print as 100m while capacity prints whole cores."""
    settings = Settings(
        platform=PlatformSettings(
            kueue=PlatformKueueSettings(
                kube_api_url="https://kube.test",
                cluster_queues=["cq-a"],
                cohort="cohort-atom",
            ),
        ),
    )
    cq = {
        "spec": {
            "resourceGroups": [
                {
                    "flavors": [
                        {
                            "resources": [
                                {"name": "cpu", "nominalQuota": "10"},
                                {"name": "memory", "nominalQuota": "20Gi"},
                            ]
                        }
                    ]
                }
            ]
        },
        "status": {
            "flavorsUsage": [
                {
                    "resources": [
                        {"name": "cpu", "total": "100m", "borrowed": "0"},
                    ]
                }
            ]
        },
    }
    cohort = {
        "metadata": {"name": "cohort-atom"},
        "spec": {"resourceGroups": []},
    }

    async def fake_parallel(urls: list[str], **_kwargs):
        out: list[dict] = []
        for url in urls:
            if url.endswith("/clusterqueues/cq-a"):
                out.append(cq)
            elif url.endswith("/cohorts/cohort-atom"):
                out.append(cohort)
            else:
                raise AssertionError(url)
        return out

    monkeypatch.setattr(
        "metrics.providers.kueue_platform.kube_parallel_get_json",
        fake_parallel,
    )
    maps = await KueuePlatformEngine(settings).collect()
    assert maps.capacity["cpu"] == "10"
    assert maps.allocated["cpu"] == "0.1"


@pytest.mark.anyio
async def test_kueue_platform_zero_allocated_when_no_flavors_usage(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """No admitted workloads → Kueue often omits usage rows; API still keys allocated like capacity."""
    settings = Settings(
        platform=PlatformSettings(
            kueue=PlatformKueueSettings(
                kube_api_url="https://kube.test",
                cluster_queues=["cq-a"],
                cohort="cohort-atom",
            ),
        ),
    )
    cq = {
        "spec": {
            "resourceGroups": [
                {
                    "flavors": [
                        {
                            "resources": [
                                {"name": "cpu", "nominalQuota": "10"},
                                {"name": "memory", "nominalQuota": "20Gi"},
                            ]
                        }
                    ]
                }
            ]
        },
        "status": {},
    }
    cohort = {
        "metadata": {"name": "cohort-atom"},
        "spec": {"resourceGroups": []},
    }

    async def fake_parallel(urls: list[str], **_kwargs):
        out: list[dict] = []
        for url in urls:
            if url.endswith("/clusterqueues/cq-a"):
                out.append(cq)
            elif url.endswith("/cohorts/cohort-atom"):
                out.append(cohort)
            else:
                raise AssertionError(url)
        return out

    monkeypatch.setattr(
        "metrics.providers.kueue_platform.kube_parallel_get_json",
        fake_parallel,
    )
    maps = await KueuePlatformEngine(settings).collect()
    assert maps.allocated["cpu"] == "0"
    assert maps.allocated["memory"] == "0Gi"
    assert set(maps.allocated.keys()) == set(maps.capacity.keys())
