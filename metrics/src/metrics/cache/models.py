"""Cache identities, freshness policies, and strict snapshot envelopes."""

from __future__ import annotations

import hashlib
import hmac
import json
from dataclasses import dataclass
from datetime import UTC, datetime
from enum import StrEnum
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict


class Freshness(StrEnum):
    """Serviceability state derived from the snapshot collection time."""

    FRESH = "fresh"
    STALE = "stale"
    RETAINED = "retained"
    PURGED = "purged"


@dataclass(frozen=True, slots=True)
class FreshnessPolicy:
    """Fresh, stale-serviceable, and retention boundaries in seconds."""

    fresh_seconds: int
    stale_seconds: int
    retention_seconds: int

    def classify(self, created: datetime, *, now: datetime | None = None) -> Freshness:
        """Classify a snapshot from its collection time."""
        clock = now or datetime.now(UTC)
        observed = (
            created.replace(tzinfo=UTC) if created.tzinfo is None else created.astimezone(UTC)
        )
        age = max(0.0, (clock - observed).total_seconds())
        if age <= self.fresh_seconds:
            return Freshness.FRESH
        if age <= self.stale_seconds:
            return Freshness.STALE
        if age <= self.retention_seconds:
            return Freshness.RETAINED
        return Freshness.PURGED


FRESHNESS_POLICIES = {
    "platform": FreshnessPolicy(5 * 60, 30 * 60, 60 * 60),
    "user": FreshnessPolicy(2 * 60, 10 * 60, 15 * 60),
    "community": FreshnessPolicy(2 * 60, 10 * 60, 15 * 60),
}


@dataclass(frozen=True, slots=True)
class CacheIdentity:
    """Stable dimensions for one source snapshot stream."""

    subject_kind: Literal["platform", "user", "community"]
    subject_value: str
    cluster: str
    source: str
    fingerprint: str = ""

    def canonical(self) -> bytes:
        """Return an unambiguous canonical representation for keyed hashing."""
        return json.dumps(
            {
                "cluster": self.cluster,
                "fingerprint": self.fingerprint,
                "source": self.source,
                "subject_kind": self.subject_kind,
                "subject_value": self.subject_value,
            },
            ensure_ascii=False,
            separators=(",", ":"),
            sort_keys=True,
        ).encode()


@dataclass(frozen=True, slots=True)
class CacheKeys:
    """Redis keys for one opaque cache identity."""

    base: str

    @property
    def latest(self) -> str:
        """Latest immutable snapshot pointer."""
        return f"{self.base}:latest"

    def snapshot(self, snapshot_id: str) -> str:
        """Key for an immutable snapshot."""
        return f"{self.base}:snapshot:{snapshot_id}"

    def lease(self, bucket: int) -> str:
        """Key for a refresh-bucket lease."""
        return f"{self.base}:lease:{bucket}"


def cache_keys(
    *,
    prefix: str,
    identity: CacheIdentity,
    secret: bytes,
    schema_revision: str,
    source_revision: str,
    query_revision: str,
) -> CacheKeys:
    """Build an opaque key path; raw subject values never enter Redis keys."""
    digest = hmac.new(secret, identity.canonical(), hashlib.sha256).hexdigest()
    revisions = f"{schema_revision}:{source_revision}:{query_revision}"
    return CacheKeys(f"{prefix}{revisions}:{identity.subject_kind}:{digest}")


class SnapshotEnvelope(BaseModel):
    """Strict, signed JSON envelope stored as an immutable Redis value."""

    model_config = ConfigDict(extra="forbid", strict=True)

    format: Literal["metrics-cache-v1"]
    schema_revision: str
    source_revision: str
    query_revision: str
    snapshot_id: str
    created: datetime
    value: dict[str, Any]
    integrity: str

    def signed_bytes(self) -> bytes:
        """Return canonical bytes covered by the integrity HMAC."""
        body = self.model_dump(mode="json", exclude={"integrity"})
        return json.dumps(
            body,
            ensure_ascii=False,
            separators=(",", ":"),
            sort_keys=True,
        ).encode()

    def verify(self, secret: bytes) -> bool:
        """Verify envelope integrity without timing-dependent comparison."""
        expected = hmac.new(secret, self.signed_bytes(), hashlib.sha256).hexdigest()
        return hmac.compare_digest(self.integrity, expected)


@dataclass(frozen=True, slots=True)
class CacheResult:
    """One cache-coordinated result returned to the service."""

    value: Any
    cached: bool
    stale: bool


class CacheUnavailable(RuntimeError):
    """Raised when no serviceable snapshot can be returned safely."""
