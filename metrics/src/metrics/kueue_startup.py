"""Fail-fast validation that Kueue mode can run before the API accepts traffic.

Intent
------
M2 requires **explicit Kueue mode** with no silent fallback to static or node
providers. If the cluster cannot satisfy configuration (API missing, queues
absent, cohort mismatch), the process should exit during application lifespan
startup rather than returning misleading metrics on first request.

This module therefore performs **read-only** checks against the live API:
verify the ClusterQueue list endpoint, load the configured ``Cohort`` and each
``ClusterQueue``, and assert cohort membership matches ``METRICS_KUEUE_COHORT``.
Request-time collection (:mod:`metrics.providers.kueue_platform`) assumes these
checks already passed.
"""

from __future__ import annotations

import httpx

from metrics.config import Settings
from metrics.kueue_api import cluster_queue_object_url, cohort_object_url, kueue_clusterqueues_list_url
from metrics.providers.kube_http import (
    kube_auth_headers,
    kube_get_json,
    kube_parallel_get_json,
    resolve_kube_tls_verify,
    resolve_kube_token,
)


class KueueStartupError(RuntimeError):
    """Raised when Kueue mode prerequisites are not satisfied.

    FastAPI lifespan propagation treats this as a fatal startup error so the
    operator gets an immediate traceback instead of a partially live service.
    """


async def validate_kueue_mode_startup(settings: Settings) -> None:
    """Verify Kueue API reachability, installation, queue set, and cohort wiring.

    Steps (high level):
    1. Ensure required settings (API URL, non-empty queue list, cohort name).
    2. ``GET`` the ClusterQueue **collection** URL to prove the API group exists.
    3. In parallel, ``GET`` the cohort object and every configured queue object.
    4. Validate cohort metadata and each queue's cohort reference (``spec.cohortName``
       in API version ``v1beta2``, or legacy ``spec.cohort`` in ``v1beta1``).

    When ``settings.provider_mode`` is not ``kueue``, this function is a no-op.

    Args:
        settings: Fully loaded application settings.

    Raises:
        KueueStartupError: On misconfiguration or unreachable / incomplete cluster
            state (messages are intended for operators).
    """
    if settings.provider_mode != "kueue":
        return

    try:
        await _validate_kueue_mode_startup_impl(settings)
    except KueueStartupError:
        raise
    except Exception as exc:
        raise KueueStartupError(
            f"Unexpected error during Kueue startup validation: {exc}"
        ) from exc


async def _validate_kueue_mode_startup_impl(settings: Settings) -> None:
    if not settings.kube_api_url:
        raise KueueStartupError(
            "METRICS_KUBE_API_URL (or KUEUE_METRICS_URL) is required in Kueue mode"
        )
    if not settings.kueue_cluster_queues:
        raise KueueStartupError(
            "METRICS_KUEUE_CLUSTER_QUEUES (or KUEUE_METRICS_CLUSTER_QUEUES) "
            "must list at least one ClusterQueue"
        )
    if not settings.kueue_cohort:
        raise KueueStartupError(
            "METRICS_KUEUE_COHORT (or KUEUE_METRICS_COHORT) is required in Kueue mode"
        )

    token = resolve_kube_token(settings.kube_api_token)
    if not token:
        raise KueueStartupError(
            "No Kubernetes API bearer token found: set METRICS_KUBE_API_TOKEN or mount "
            "a service account token (expected file "
            "/var/run/secrets/kubernetes.io/serviceaccount/token in-cluster)"
        )
    headers = kube_auth_headers(token)
    verify = resolve_kube_tls_verify(settings.kube_verify_tls)
    timeout = settings.kube_request_timeout_seconds

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
        raise KueueStartupError(f"Cannot reach Kubernetes API for Kueue checks: {exc}") from exc

    cohort_url = cohort_object_url(settings, settings.kueue_cohort)
    queue_urls = [cluster_queue_object_url(settings, q) for q in settings.kueue_cluster_queues]

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
        raise KueueStartupError(f"Kubernetes request failed during Kueue validation: {exc}") from exc
    except httpx.RequestError as exc:
        raise KueueStartupError(f"Cannot reach Kubernetes API for Kueue checks: {exc}") from exc

    cohort_doc = docs[0]
    cqs = docs[1:]

    if not isinstance(cohort_doc, dict):
        raise KueueStartupError("Unexpected response when loading Cohort object")
    meta = cohort_doc.get("metadata") or {}
    if meta.get("name") != settings.kueue_cohort:
        raise KueueStartupError("Cohort response metadata did not match requested cohort name")

    for cq, queue_name in zip(cqs, settings.kueue_cluster_queues, strict=True):
        if not isinstance(cq, dict):
            raise KueueStartupError(
                f"Unexpected response when loading ClusterQueue {queue_name!r}"
            )
        spec = cq.get("spec") or {}
        cohort_ref = str(
            spec.get("cohortName") or spec.get("cohort") or "",
        )
        if cohort_ref != settings.kueue_cohort:
            raise KueueStartupError(
                f"ClusterQueue {queue_name!r} has cohort {cohort_ref!r}, "
                f"expected {settings.kueue_cohort!r}"
            )


