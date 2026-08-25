"""Kubernetes ``Status`` error wire model."""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field

StatusReason = Literal[
    "BadRequest",
    "NotFound",
    "Invalid",
    "ServiceUnavailable",
    "InternalError",
]


class Status(BaseModel):
    """Stable, sanitized API failure."""

    api_version: Literal["v1"] = Field(default="v1", serialization_alias="apiVersion")
    kind: Literal["Status"] = "Status"
    status: Literal["Failure"] = "Failure"
    reason: StatusReason
    message: str
    code: int
