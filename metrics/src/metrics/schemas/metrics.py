"""Public ``canfar.net/v1alpha1`` Metrics wire models."""

from __future__ import annotations

from datetime import datetime
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


class WireModel(BaseModel):
    """Strict public wire model."""

    model_config = ConfigDict(extra="forbid")


class ObjectMetadata(WireModel):
    """Truthful metadata for the standalone response."""

    name: str


class MetricsSpec(WireModel):
    """One compact Metrics subject selector."""

    platform: str


class ResourceMetrics(WireModel):
    """Named Kubernetes resource observation."""

    name: str
    capacity: str
    allocated: str


class Condition(WireModel):
    """Kubernetes-style report condition."""

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
    """Observed resources and exactly Ready/Cached conditions."""

    observed_at: datetime = Field(serialization_alias="observedAt")
    resources: list[ResourceMetrics]
    conditions: list[Condition]


class Metrics(WireModel):
    """One Kubernetes-style metrics report."""

    api_version: Literal["canfar.net/v1alpha1"] = Field(
        default="canfar.net/v1alpha1", serialization_alias="apiVersion"
    )
    kind: Literal["Metrics"] = "Metrics"
    metadata: ObjectMetadata
    spec: MetricsSpec
    status: MetricsStatus
