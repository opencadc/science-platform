from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

import metrics.core.factory as factory_module
from metrics.core.factory import create_app
from metrics.core.settings import (
    PlatformKueueSettings,
    PlatformPrometheusSettings,
    PlatformSettings,
    Settings,
)
from metrics.core.startup import KueueStartupError, validate_application_startup


@pytest.mark.anyio
async def test_validate_application_startup_requires_prometheus() -> None:
    with pytest.raises(KueueStartupError, match="PROMETHEUS"):
        await validate_application_startup(
            Settings(
                platform=PlatformSettings(
                    prometheus=PlatformPrometheusSettings(url=None),
                ),
            )
        )


@pytest.mark.anyio
async def test_validate_kueue_mode_requires_kube_url() -> None:
    with pytest.raises(KueueStartupError, match="KUBE_API_URL"):
        await validate_application_startup(
            Settings(
                platform=PlatformSettings(
                    prometheus=PlatformPrometheusSettings(url="http://prometheus:9090"),
                    kueue=PlatformKueueSettings(
                        cluster_queues=["cq-a"],
                        cohort="c",
                        kube_api_url=None,
                    ),
                ),
            )
        )


def test_kueue_app_lifespan_invokes_startup_validation(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    called = 0

    async def fake_validate(settings: Settings) -> None:
        nonlocal called
        called += 1
        assert settings.platform.kueue.cluster_queues == ["cq-proton"]

    monkeypatch.setattr(factory_module, "validate_application_startup", fake_validate)

    settings = Settings(
        cache_backend="memory",
        platform=PlatformSettings(
            kueue=PlatformKueueSettings(
                kube_api_url="https://kubernetes.default.svc",
                cluster_queues=["cq-proton"],
                cohort="cohort-atom",
            ),
            prometheus=PlatformPrometheusSettings(url="http://prometheus:9090"),
        ),
    )
    with TestClient(create_app(settings=settings)) as client:
        assert client.get("/healthz").status_code == 200
    assert called == 1


def test_kueue_app_fails_when_startup_validation_raises(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    async def boom(_settings: Settings) -> None:
        raise KueueStartupError("misconfigured")

    monkeypatch.setattr(factory_module, "validate_application_startup", boom)

    settings = Settings(
        cache_backend="memory",
        platform=PlatformSettings(
            kueue=PlatformKueueSettings(
                kube_api_url="https://kubernetes.default.svc",
                cluster_queues=["cq-proton"],
                cohort="cohort-atom",
            ),
            prometheus=PlatformPrometheusSettings(url="http://prometheus:9090"),
        ),
    )
    with pytest.raises(KueueStartupError):
        with TestClient(create_app(settings=settings)):
            pass
