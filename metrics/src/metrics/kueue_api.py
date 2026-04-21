"""Kubernetes URL builders for the Kueue ``kueue.x-k8s.io/v1beta2`` API.

This module exists so **every HTTP caller** (startup validation, platform
aggregation, capacity provider) constructs identical URLs from ``Settings``.
That avoids subtle path drift between ``/clusterqueues`` list vs object GETs and
keeps the API version boundary in one place.

The service talks to the **Kubernetes API server** (not the Kueue controller
directly); paths follow the standard aggregated API layout documented in the
Kueue project for ``ClusterQueue`` and ``Cohort`` resources.
"""

from __future__ import annotations

from metrics.config import Settings


def kueue_clusterqueues_list_url(settings: Settings) -> str:
    """Return the collection URL used to verify the ClusterQueue API is installed.

    A successful GET here proves the aggregated API is registered (CRDs
    present) and credentials can reach it. Individual queue validation uses
    :func:`cluster_queue_object_url` instead.

    Args:
        settings: Runtime settings; ``kube_api_url`` must be set for Kueue mode.

    Returns:
        Full URL to ``GET .../apis/kueue.x-k8s.io/v1beta2/clusterqueues`` (list).
    """
    return f"{settings.kube_api_url.rstrip('/')}{settings.kube_clusterqueue_path}"


def cluster_queue_object_url(settings: Settings, name: str) -> str:
    """Return the URL for a single ``ClusterQueue`` by metadata name.

    Used for per-queue nominal quota reads and for startup checks that each
    configured queue exists and references the expected cohort.

    Args:
        settings: Runtime settings including ``kube_api_url`` and path prefix.
        name: ClusterQueue object name (for example ``cq-proton``).

    Returns:
        Full URL for ``GET .../clusterqueues/{name}``.
    """
    base = settings.kube_api_url.rstrip("/")
    return f"{base}{settings.kube_clusterqueue_path}/{name}"


def cohort_object_url(settings: Settings, name: str) -> str:
    """Return the URL for a single ``Cohort`` by metadata name.

    Cohort objects hold **shared** nominal quota layered on top of member
    ClusterQueues. The platform engine adds cohort nominal quota **once** after
    summing per-queue nominal values so cohort capacity is not double-counted
    across queues.

    Args:
        settings: Runtime settings including ``kube_cohort_path``.
        name: Cohort object name (must match ``METRICS_KUEUE_COHORT``).

    Returns:
        Full URL for ``GET .../cohorts/{name}``.
    """
    base = settings.kube_api_url.rstrip("/")
    return f"{base}{settings.kube_cohort_path}/{name}"
