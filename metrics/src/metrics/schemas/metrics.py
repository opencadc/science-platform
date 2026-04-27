"""Pydantic API and service models."""

from __future__ import annotations

from datetime import UTC, datetime
from typing import Literal

from pydantic import BaseModel, Field


class ResponseMetadata(BaseModel):
    """Common metadata for all API responses.

    Freshness and cache visibility are expressed via HTTP headers
    (``Cache-Control``, ``Date``, ``Expires``, ``Last-Modified``), not JSON fields.
    """

    created: datetime = Field(
        default_factory=lambda: datetime.now(UTC),
        description="Timestamp when this metric snapshot was produced (also ``Last-Modified``).",
    )


class ErrorDetail(BaseModel):
    """Structured error payload."""

    code: str = Field(description="Stable machine-readable error code.")
    message: str = Field(description="Human-readable error summary.")
    details: dict[str, str] | None = Field(
        default=None,
        description="Optional key/value details for debugging and remediation.",
    )


class PlatformMetricsData(BaseModel):
    """Platform metrics payload."""

    scope: Literal["platform"] = "platform"
    cluster: str = Field(description="Cluster identifier for this metric scope.")
    capacity: dict[str, str] = Field(
        default_factory=dict,
        description=(
            "Aggregated nominal quota keyed by Kubernetes resource name "
            "(for example cpu, memory, nvidia.com/gpu). Per-resource units match "
            "`allocated` (CPU in cores, memory in Gi, etc.)."
        ),
    )
    allocated: dict[str, str] = Field(
        default_factory=dict,
        description=(
            "Aggregated admitted usage from Kueue ClusterQueue status.flavorsUsage "
            "total per resource, keyed by resource name. Kueue total already "
            "includes borrowed quota. Per-resource units match `capacity` "
            "(CPU in cores, memory in Gi, etc.)."
        ),
    )


class PlatformMetricsResponse(BaseModel):
    """Success envelope for platform metrics."""

    version: str = Field(
        description="Versioned API group identifier.",
        examples=["metrics.canfar.net/v1"],
    )
    kind: Literal["PlatformMetrics"] = "PlatformMetrics"
    metadata: ResponseMetadata
    status: Literal["Success"] = "Success"
    data: PlatformMetricsData


class ErrorResponse(BaseModel):
    """Error envelope for all API routes."""

    version: str = Field(
        description="Versioned API group identifier.",
        examples=["metrics.canfar.net/v1"],
    )
    kind: Literal["Status"] = "Status"
    metadata: ResponseMetadata
    status: Literal["Error"] = "Error"
    error: ErrorDetail


