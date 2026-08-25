"""Define the strict public ``canfar.net/v1alpha1`` Metrics wire contract.

These models isolate serialized field names and response invariants from the
transport-neutral service observations used internally.
"""

from __future__ import annotations

from datetime import datetime
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator


class WireModel(BaseModel):
    """Reject undeclared fields in every public Metrics response model."""

    model_config = ConfigDict(extra="forbid")


class ObjectMetadata(WireModel):
    """Identify a standalone report with a deterministic presentation name."""

    name: str


class MetricsSpec(WireModel):
    """Select exactly one platform, user, or community report subject."""

    platform: str | None = None
    user: str | None = None
    community: str | None = None

    @model_validator(mode="after")
    def _exactly_one_subject(self) -> MetricsSpec:
        """Reject ambiguous or empty subject selectors."""
        if sum(value is not None for value in (self.platform, self.user, self.community)) != 1:
            raise ValueError("spec must contain exactly one subject")
        return self


class ResourceMetrics(WireModel):
    """Present current and optional lifetime values for one resource.

    Platform reports populate capacity and allocation. User and community
    reports populate requests and, when complete, additive lifetime fields.
    """

    name: str
    capacity: str | None = None
    allocated: str | None = None
    requests: str | None = None
    usage_hours: str | None = Field(default=None, serialization_alias="usageHours")
    requested_hours: str | None = Field(default=None, serialization_alias="requestedHours")
    efficiency: str | None = None


class Condition(WireModel):
    """Describe report readiness or cache provenance in Kubernetes style."""

    type: Literal["Ready", "Cached"]
    status: Literal["True", "False", "Unknown"]
    reason: Literal[
        "Available",
        "PartialData",
        "AccountingIncomplete",
        "StaleData",
        "FreshHit",
        "StaleHit",
        "Refreshed",
        "RedisUnavailable",
    ]
    last_transition_time: datetime = Field(serialization_alias="lastTransitionTime")


class MetricsStatus(WireModel):
    """Report observation time, resources, and Ready/Cached conditions."""

    observed_at: datetime = Field(serialization_alias="observedAt")
    accounting_period: Literal["ActiveWorkloadLifetime"] | None = Field(
        default=None,
        serialization_alias="accountingPeriod",
    )
    running_pods: int | None = Field(default=None, serialization_alias="runningPods")
    resources: list[ResourceMetrics]
    conditions: list[Condition]


class Metrics(WireModel):
    """Wrap one subject report in the versioned Kubernetes-style envelope."""

    api_version: Literal["canfar.net/v1alpha1"] = Field(
        default="canfar.net/v1alpha1", serialization_alias="apiVersion"
    )
    kind: Literal["Metrics"] = "Metrics"
    metadata: ObjectMetadata
    spec: MetricsSpec
    status: MetricsStatus
