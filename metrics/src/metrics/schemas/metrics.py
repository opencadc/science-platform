"""Define the strict public ``canfar.net/v1alpha1`` Metrics contract."""

from __future__ import annotations

from datetime import UTC, datetime
import re
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator


_MAX_WIRE_VALUE_LENGTH = 4_096
_CANONICAL_DECIMAL = re.compile(r"(?:0|[1-9][0-9]*)(?:\.[0-9]*[1-9])?$")
_EFFICIENCY_RESOURCES = frozenset({"cpu", "memory"})
_STORAGE_RESOURCE_NAMES = frozenset({"memory", "ephemeral-storage"})


def _normalize_utc(value: datetime) -> datetime:
    """Require an aware datetime and normalize it to UTC."""
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("datetime values must be timezone-aware")
    return value.astimezone(UTC)


class WireModel(BaseModel):
    """Reject undeclared fields in every public response model."""

    model_config = ConfigDict(extra="forbid", populate_by_name=True)


class ObjectMetadata(WireModel):
    """Identify one standalone Metrics report."""

    name: str = Field(min_length=1)


class MetricsSpec(WireModel):
    """Select exactly one platform, user, or community subject."""

    platform: str | None = Field(default=None, min_length=1)
    user: str | None = Field(default=None, min_length=1)
    community: str | None = Field(default=None, min_length=1)

    @model_validator(mode="after")
    def _exactly_one_subject(self) -> MetricsSpec:
        """Reject ambiguous or empty subject selectors."""
        if sum(value is not None for value in (self.platform, self.user, self.community)) != 1:
            raise ValueError("spec must contain exactly one subject")
        return self


class ResourceMetrics(WireModel):
    """Present queue quantities and optional current efficiency."""

    name: str = Field(min_length=1)
    capacity: str | None = None
    allocated: str | None = None
    requests: str | None = None
    efficiency: str | None = None

    @model_validator(mode="before")
    @classmethod
    def _validate_wire_values(cls, values: object) -> object:
        """Reject unbounded or non-canonical values at the wire boundary."""
        if not isinstance(values, dict):
            return values
        name = values.get("name")
        if not isinstance(name, str):
            return values
        for field_name in ("capacity", "allocated", "requests", "efficiency"):
            value = values.get(field_name)
            if value is not None:
                if field_name == "efficiency" and name not in _EFFICIENCY_RESOURCES:
                    raise ValueError("efficiency is supported only for cpu and memory")
                _validate_wire_value(name, field_name, value)
        return values

    @model_validator(mode="after")
    def _validate_surface_shape(self) -> ResourceMetrics:
        """Require one coherent platform or workload resource shape."""
        has_capacity = self.capacity is not None
        has_allocated = self.allocated is not None
        has_requests = self.requests is not None
        if has_capacity != has_allocated:
            raise ValueError("capacity and allocated must be provided together")
        if has_capacity and has_requests:
            raise ValueError("platform resources cannot contain requests")
        if not has_capacity and not has_requests:
            raise ValueError("workload resources must contain requests")
        return self


def _validate_wire_value(resource_name: str, field_name: str, value: object) -> None:
    """Validate one bounded canonical quantity or efficiency string."""
    if not isinstance(value, str) or not value or len(value) > _MAX_WIRE_VALUE_LENGTH:
        raise ValueError(f"{field_name} must be a bounded plain decimal")
    if field_name == "efficiency":
        valid = _CANONICAL_DECIMAL.fullmatch(value) is not None
    elif resource_name in _STORAGE_RESOURCE_NAMES:
        valid = value.endswith("Gi") and _CANONICAL_DECIMAL.fullmatch(value[:-2]) is not None
    else:
        valid = _CANONICAL_DECIMAL.fullmatch(value) is not None
    if not valid:
        raise ValueError(f"{field_name} is not a canonical non-negative resource value")


class Condition(WireModel):
    """Describe report readiness or cache provenance in Kubernetes style."""

    type: Literal["Ready", "Cached"]
    status: Literal["True", "False", "Unknown"]
    reason: Literal[
        "Available",
        "PartialData",
        "StaleData",
        "FreshHit",
        "StaleHit",
        "Refreshed",
        "RedisUnavailable",
    ]
    last_transition_time: datetime = Field(
        alias="lastTransitionTime",
        serialization_alias="lastTransitionTime",
    )

    @field_validator("last_transition_time")
    @classmethod
    def _normalize_last_transition_time(cls, value: datetime) -> datetime:
        """Require an aware transition timestamp and normalize it to UTC."""
        return _normalize_utc(value)

    @model_validator(mode="after")
    def _validate_status_reason(self) -> Condition:
        """Require a status valid for the condition reason."""
        expected_status_by_reason = {
            "Ready": {
                "Available": "True",
                "PartialData": "False",
                "StaleData": "False",
            },
            "Cached": {
                "FreshHit": "True",
                "StaleHit": "True",
                "Refreshed": "False",
                "RedisUnavailable": "Unknown",
            },
        }
        expected_status = expected_status_by_reason[self.type].get(self.reason)
        if expected_status is None or self.status != expected_status:
            raise ValueError(f"{self.type} condition reason {self.reason} has an invalid status")
        return self


class MetricsStatus(WireModel):
    """Report observation time, queue state, resources, and conditions."""

    observed_at: datetime = Field(
        alias="observedAt",
        serialization_alias="observedAt",
    )
    reserving_workloads: int = Field(
        ge=0,
        alias="reservingWorkloads",
        serialization_alias="reservingWorkloads",
    )
    resources: list[ResourceMetrics]
    conditions: list[Condition]

    @field_validator("observed_at")
    @classmethod
    def _normalize_observed_at(cls, value: datetime) -> datetime:
        """Require an aware observation timestamp and normalize it to UTC."""
        return _normalize_utc(value)

    @model_validator(mode="after")
    def _validate_report_invariants(self) -> MetricsStatus:
        """Require exactly Ready/Cached conditions and unique resources."""
        condition_types = [condition.type for condition in self.conditions]
        if len(condition_types) != 2 or set(condition_types) != {"Ready", "Cached"}:
            raise ValueError("conditions must contain exactly one Ready and one Cached condition")
        resource_names = [resource.name for resource in self.resources]
        if len(resource_names) != len(set(resource_names)):
            raise ValueError("resources must have unique names")
        if any(condition.last_transition_time > self.observed_at for condition in self.conditions):
            raise ValueError("condition lastTransitionTime must not be later than observedAt")
        return self


class Metrics(WireModel):
    """Wrap one subject report in the versioned Kubernetes-style envelope."""

    api_version: Literal["canfar.net/v1alpha1"] = Field(
        default="canfar.net/v1alpha1",
        alias="apiVersion",
        serialization_alias="apiVersion",
    )
    kind: Literal["Metrics"] = "Metrics"
    metadata: ObjectMetadata
    spec: MetricsSpec
    status: MetricsStatus

    @model_validator(mode="after")
    def _validate_surface_invariants(self) -> Metrics:
        """Bind the subject selector to the resource shape."""
        is_platform = self.spec.platform is not None
        for resource in self.status.resources:
            if (resource.capacity is not None) != is_platform:
                raise ValueError("spec subject does not match resource surface")
        if is_platform and not self.status.resources:
            raise ValueError("platform status must contain resources")
        return self
