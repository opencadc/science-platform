"""Pydantic API and service models."""

from __future__ import annotations

from datetime import UTC, datetime
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


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


class ResourceSnapshot(BaseModel):
    """Resource quantity values represented in Kubernetes-friendly strings."""

    model_config = ConfigDict(populate_by_name=True)

    cpu: str = Field(description="CPU quantity as a core string.", examples=["512"])
    memory: str = Field(
        description="Memory quantity string with GiB unit.",
        examples=["2048 GiB"],
    )
    ephemeral_memory: str = Field(
        default="0 GiB",
        alias="ephemeral-memory",
        description="Ephemeral memory quantity string with GiB unit.",
        examples=["0 GiB"],
    )
    gpu: str = Field(
        default="0",
        description="GPU resource count as a string quantity.",
        examples=["4"],
    )


class UtilizationSnapshot(BaseModel):
    """Utilization ratios (0-1) for requested over capacity resources."""

    model_config = ConfigDict(populate_by_name=True)

    cpu: float = Field(
        ge=0,
        le=1,
        description="CPU utilization ratio from 0 to 1.",
        examples=[0.25],
    )
    memory: float = Field(
        ge=0,
        le=1,
        description="Memory utilization ratio from 0 to 1.",
        examples=[0.375],
    )
    ephemeral_memory: float = Field(
        default=0.0,
        alias="ephemeral-memory",
        ge=0,
        le=1,
        description="Ephemeral memory utilization ratio from 0 to 1.",
        examples=[0.0],
    )
    gpu: float = Field(
        default=0.0,
        ge=0,
        le=1,
        description="GPU utilization ratio from 0 to 1.",
        examples=[0.0],
    )


class UsageSnapshot(BaseModel):
    """Requested and utilization usage sections."""

    requested: ResourceSnapshot
    utilization: UtilizationSnapshot


class PlatformMetricsData(BaseModel):
    """Platform metrics payload."""

    scope: Literal["platform"] = "platform"
    cluster: str = Field(description="Cluster identifier for this metric scope.")
    capacity: dict[str, str] = Field(
        default_factory=dict,
        description=(
            "Aggregated nominal quota keyed by Kubernetes resource name "
            "(for example cpu, memory, nvidia.com/gpu)."
        ),
    )
    allocated: dict[str, str] = Field(
        default_factory=dict,
        description=(
            "Aggregated admitted usage from Kueue ClusterQueue status.flavorsUsage "
            "(total plus borrowed per resource), keyed by resource name."
        ),
    )


class UserMetricsData(BaseModel):
    """User metrics payload."""

    scope: Literal["user"] = "user"
    cluster: str
    user_id: str
    capacity: ResourceSnapshot
    usage: UsageSnapshot


class SessionMetricsData(BaseModel):
    """Session metrics payload."""

    scope: Literal["session"] = "session"
    cluster: str
    user_id: str
    session_id: str
    capacity: ResourceSnapshot
    usage: UsageSnapshot


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


class UserMetricsResponse(BaseModel):
    """Envelope for user metrics responses."""

    version: str = Field(
        description="Versioned API group identifier.",
        examples=["metrics.canfar.net/v1"],
    )
    kind: Literal["UserMetrics"] = "UserMetrics"
    metadata: ResponseMetadata
    status: Literal["Success"] = "Success"
    data: UserMetricsData


class SessionMetricsResponse(BaseModel):
    """Envelope for session metrics responses."""

    version: str = Field(
        description="Versioned API group identifier.",
        examples=["metrics.canfar.net/v1"],
    )
    kind: Literal["SessionMetrics"] = "SessionMetrics"
    metadata: ResponseMetadata
    status: Literal["Success"] = "Success"
    data: SessionMetricsData


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


class CapacityReading(BaseModel):
    """Internal model returned by capacity providers."""

    cpu_cores: float
    memory_gib: float
    source: str
    observed_at: datetime


class UsageReading(BaseModel):
    """Internal model returned by utilization providers."""

    requested_cpu_cores: float
    requested_memory_gib: float
    source: str
    observed_at: datetime
