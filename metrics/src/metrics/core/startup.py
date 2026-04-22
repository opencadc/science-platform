"""Fail-fast validation before the API accepts traffic."""

from __future__ import annotations

import httpx

from metrics.core.settings import Settings
from metrics.kueue_api import (
    cluster_queue_object_url,
    cohort_object_url,
    kueue_clusterqueues_list_url,
)
from metrics.providers.kube_http import (
    kube_auth_headers,
    kube_get_json,
    kube_parallel_get_json,
    resolve_kube_tls_verify,
    resolve_kube_token,
)


class KueueStartupError(RuntimeError):
    """Raised when required platform sources are not satisfied.

    FastAPI lifespan propagation treats this as a fatal startup error.
    """


async def validate_application_startup(settings: Settings) -> None:
    """Verify Kueue and Prometheus prerequisites for the composed platform model."""
    try:
        _validate_prometheus_settings(settings)
        await _validate_kueue_platform_startup_impl(settings)
    except KueueStartupError:
        raise
    except Exception as exc:
        raise KueueStartupError(
            f"Unexpected error during application startup validation: {exc}"
        ) from exc


def _validate_prometheus_settings(settings: Settings) -> None:
    if not settings.platform.prometheus.url:
        raise KueueStartupError(
            "METRICS_PLATFORM__PROMETHEUS__URL (or legacy METRICS_PROMETHEUS_URL) "
            "is required for Prometheus-backed usage queries"
        )


async def validate_kueue_mode_startup(settings: Settings) -> None:
    """Backward-compatible name for tests; delegates to :func:`validate_application_startup`."""
    await validate_application_startup(settings)


async def _validate_kueue_platform_startup_impl(settings: Settings) -> None:
    k = settings.platform.kueue
    if not k.kube_api_url:
        raise KueueStartupError(
            "METRICS_PLATFORM__KUEUE__KUBE_API_URL (or legacy METRICS_KUBE_API_URL / "
            "KUEUE_METRICS_URL) is required"
        )
    if not k.cluster_queues:
        raise KueueStartupError(
            "METRICS_PLATFORM__KUEUE__CLUSTER_QUEUES (or legacy METRICS_KUEUE_CLUSTER_QUEUES / "
            "KUEUE_METRICS_CLUSTER_QUEUES) must list at least one ClusterQueue"
        )
    if not k.cohort:
        raise KueueStartupError(
            "METRICS_PLATFORM__KUEUE__COHORT (or legacy METRICS_KUEUE_COHORT / "
            "KUEUE_METRICS_COHORT) is required"
        )

    token = resolve_kube_token(k.kube_api_token)
    if not token:
        raise KueueStartupError(
            "No Kubernetes API bearer token found: set METRICS_PLATFORM__KUEUE__KUBE_API_TOKEN "
            "or legacy METRICS_KUBE_API_TOKEN, or mount a service account token "
            "(expected file /var/run/secrets/kubernetes.io/serviceaccount/token in-cluster)"
        )
    headers = kube_auth_headers(token)
    verify = resolve_kube_tls_verify(k.kube_verify_tls)
    timeout = k.kube_request_timeout_seconds

    list_url = kueue_clusterqueues_list_url(settings)
    try:
        await kube_get_json(list_url, headers=headers, timeout=timeout, verify=verify)
    except httpx.HTTPStatusError as exc:
        if exc.response is not None and exc.response.status_code in (403, 404):
            raise KueueStartupError(
                "Kueue ClusterQueue API is not reachable or Kueue is not installed "
                f"(HTTP {exc.response.status_code})"
            ) from exc
        raise KueueStartupError(f"Kubernetes request failed: {exc}") from exc
    except httpx.RequestError as exc:
        raise KueueStartupError(
            f"Cannot reach Kubernetes API for Kueue checks: {exc}"
        ) from exc

    cohort_url = cohort_object_url(settings, k.cohort)
    queue_urls = [cluster_queue_object_url(settings, q) for q in k.cluster_queues]

    try:
        docs = await kube_parallel_get_json(
            [cohort_url, *queue_urls],
            headers=headers,
            timeout=timeout,
            verify=verify,
        )
    except httpx.HTTPStatusError as exc:
        if exc.response is not None and exc.response.status_code == 404:
            raise KueueStartupError(
                "Configured cohort or a ClusterQueue was not found in the cluster"
            ) from exc
        raise KueueStartupError(
            f"Kubernetes request failed during Kueue validation: {exc}"
        ) from exc
    except httpx.RequestError as exc:
        raise KueueStartupError(
            f"Cannot reach Kubernetes API for Kueue checks: {exc}"
        ) from exc

    cohort_doc = docs[0]
    cqs = docs[1:]

    if not isinstance(cohort_doc, dict):
        raise KueueStartupError("Unexpected response when loading Cohort object")
    meta = cohort_doc.get("metadata") or {}
    if meta.get("name") != k.cohort:
        raise KueueStartupError(
            "Cohort response metadata did not match requested cohort name"
        )

    for cq, queue_name in zip(cqs, k.cluster_queues, strict=True):
        if not isinstance(cq, dict):
            raise KueueStartupError(
                f"Unexpected response when loading ClusterQueue {queue_name!r}"
            )
        spec = cq.get("spec") or {}
        cohort_ref = str(
            spec.get("cohortName") or spec.get("cohort") or "",
        )
        if cohort_ref != k.cohort:
            raise KueueStartupError(
                f"ClusterQueue {queue_name!r} has cohort {cohort_ref!r}, "
                f"expected {k.cohort!r}"
            )
