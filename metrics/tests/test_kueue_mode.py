from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

import metrics.app as app_module
from metrics.app import create_app
from metrics.config import Settings
from metrics.kueue_startup import KueueStartupError, validate_kueue_mode_startup


@pytest.mark.anyio
async def test_validate_kueue_mode_startup_skips_when_static() -> None:
    await validate_kueue_mode_startup(Settings(provider_mode="static"))


@pytest.mark.anyio
async def test_validate_kueue_mode_requires_kube_url() -> None:
    with pytest.raises(KueueStartupError, match="KUBE_API_URL"):
        await validate_kueue_mode_startup(
            Settings(
                provider_mode="kueue",
                kueue_cluster_queues=["cq-a"],
                kueue_cohort="c",
            )
        )


def test_kueue_app_lifespan_invokes_startup_validation(monkeypatch: pytest.MonkeyPatch) -> None:
    called = 0

    async def fake_validate(settings: Settings) -> None:
        nonlocal called
        called += 1
        assert settings.provider_mode == "kueue"

    monkeypatch.setattr(app_module, "validate_kueue_mode_startup", fake_validate)

    settings = Settings(
        provider_mode="kueue",
        cache_backend="memory",
        kube_api_url="https://kubernetes.default.svc",
        kueue_cluster_queues=["cq-proton"],
        kueue_cohort="cohort-atom",
    )
    with TestClient(create_app(settings=settings)) as client:
        assert client.get("/healthz").status_code == 200
    assert called == 1


def test_kueue_app_fails_when_startup_validation_raises(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    async def boom(_settings: Settings) -> None:
        raise KueueStartupError("misconfigured")

    monkeypatch.setattr(app_module, "validate_kueue_mode_startup", boom)

    settings = Settings(
        provider_mode="kueue",
        cache_backend="memory",
        kube_api_url="https://kubernetes.default.svc",
        kueue_cluster_queues=["cq-proton"],
        kueue_cohort="cohort-atom",
    )
    with pytest.raises(KueueStartupError):
        with TestClient(create_app(settings=settings)):
            pass
