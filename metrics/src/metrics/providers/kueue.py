"""Capacity provider that reads nominal CPU/memory from configured ClusterQueues.

Intent
------
User and session metrics still use :class:`metrics.schemas.metrics.CapacityReading`
(two floats + source). This provider answers that contract by summing **only**
``cpu`` and ``memory`` nominal quotas from the same configured queue list as
the platform engine, using shared parsing from :mod:`metrics.kueue_spec`.

Platform **maps** use :class:`metrics.providers.kueue_platform.KueuePlatformEngine`
instead; do not extend this class with arbitrary-resource logic—keep that in
``kueue_platform`` to avoid duplicating the cohort aggregation rules.
"""

from __future__ import annotations

from datetime import UTC, datetime

import httpx

from metrics.core.settings import Settings
from metrics.errors import ProviderExecutionError, ProviderUnavailableError
from metrics.kueue_api import cluster_queue_object_url
from metrics.kueue_spec import sum_nominal_quotas_by_resource
from metrics.schemas.metrics import CapacityReading
from metrics.providers.kube_http import (
    kube_auth_headers,
    kube_parallel_get_json,
    resolve_kube_tls_verify,
    resolve_kube_token,
)


class KueueCapacityProvider:
    """Aggregate nominal ``cpu`` and ``memory`` from selected ``ClusterQueue`` specs."""

    source_name = "kueue"

    def __init__(self, settings: Settings) -> None:
        self._settings = settings

    async def get_capacity(self) -> CapacityReading:
        """Return summed CPU cores and memory GiB across configured queues.

        Raises:
            ProviderUnavailableError: Missing API URL, empty queue list, or no
                cpu/memory nominal quota on any queue.
            ProviderExecutionError: HTTP or transport failures talking to the API.
        """
        k = self._settings.platform.kueue
        if not k.kube_api_url:
            raise ProviderUnavailableError(
                "METRICS_PLATFORM__KUEUE__KUBE_API_URL is not configured"
            )

        queues = k.cluster_queues
        if not queues:
            raise ProviderUnavailableError(
                "No ClusterQueues are configured for Kueue capacity aggregation"
            )

        token = resolve_kube_token(k.kube_api_token)
        headers = kube_auth_headers(token)
        verify = resolve_kube_tls_verify(k.kube_verify_tls)
        timeout = k.kube_request_timeout_seconds

        urls = [cluster_queue_object_url(self._settings, q) for q in queues]

        total_cpu = 0.0
        total_memory = 0.0

        try:
            items = await kube_parallel_get_json(
                urls, headers=headers, timeout=timeout, verify=verify
            )
        except httpx.HTTPStatusError as exc:
            raise ProviderExecutionError(
                f"Failed querying Kueue ClusterQueues: HTTP {exc.response.status_code}"
            ) from exc
        except httpx.RequestError as exc:
            raise ProviderExecutionError(
                f"Failed querying Kueue ClusterQueues: {exc}"
            ) from exc

        for item in items:
            for name, val in sum_nominal_quotas_by_resource(item).items():
                lowered = name.lower()
                if lowered == "cpu":
                    total_cpu += val
                elif lowered == "memory":
                    total_memory += val

        if total_cpu <= 0 and total_memory <= 0:
            raise ProviderUnavailableError(
                "ClusterQueue data did not include cpu or memory nominal quota values"
            )

        return CapacityReading(
            cpu_cores=total_cpu,
            memory_gib=total_memory,
            source=self.source_name,
            observed_at=datetime.now(UTC),
        )
