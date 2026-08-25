"""Define cache identity, freshness, integrity, and result contracts.

Shared models keep Redis keys opaque, snapshots versioned and signed, and
freshness decisions consistent across in-memory and distributed coordinators.
"""

from __future__ import annotations

import hashlib
import hmac
import json
from dataclasses import dataclass
from datetime import UTC, datetime
from enum import StrEnum
from typing import Any, Generic, Literal, TypeVar

from pydantic import BaseModel, ConfigDict

Value = TypeVar("Value")


class Freshness(StrEnum):
    """Classify whether a snapshot may be returned or should be discarded."""

    FRESH = "fresh"
    STALE = "stale"
    RETAINED = "retained"
    PURGED = "purged"


@dataclass(frozen=True, slots=True)
class FreshnessPolicy:
    """Set ordered fresh, stale-serviceable, and retention age boundaries."""

    fresh_seconds: int
    stale_seconds: int
    retention_seconds: int

    def classify(self, created: datetime, *, now: datetime | None = None) -> Freshness:
        """Classify a snapshot by elapsed time since source collection.

        Args:
            created: Snapshot collection time; naive values are interpreted as UTC.
            now: Optional clock override.

        Returns:
            The serviceability state for the snapshot's age.
        """
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
    """Identify one source snapshot stream without exposing it in Redis keys."""

    subject_kind: Literal["platform", "user", "community"]
    subject_value: str
    cluster: str
    source: str
    fingerprint: str = ""

    def canonical(self) -> bytes:
        """Serialize all identity dimensions deterministically for keyed hashing."""
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
    """Derive pointer, immutable snapshot, and lease keys from one opaque base."""

    base: str

    @property
    def latest(self) -> str:
        """Return the key holding the latest immutable snapshot ID."""
        return f"{self.base}:latest"

    def snapshot(self, snapshot_id: str) -> str:
        """Build the key for an immutable snapshot value.

        Args:
            snapshot_id: Unique ID assigned when the snapshot is published.

        Returns:
            Redis snapshot key.
        """
        return f"{self.base}:snapshot:{snapshot_id}"

    def lease(self, bucket: int) -> str:
        """Build the key coordinating one refresh time bucket.

        Args:
            bucket: Integer freshness bucket shared by replicas.

        Returns:
            Redis lease key.
        """
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
    """Build revisioned opaque keys without exposing raw subject values.

    Args:
        prefix: Operator-configured Redis key namespace.
        identity: Complete source stream identity.
        secret: HMAC key used to obscure identity dimensions.
        schema_revision: Cached value schema revision.
        source_revision: Provider data contract revision.
        query_revision: Source query revision.

    Returns:
        Key derivation object rooted at the revisioned identity digest.
    """
    digest = hmac.new(secret, identity.canonical(), hashlib.sha256).hexdigest()
    revisions = f"{schema_revision}:{source_revision}:{query_revision}"
    return CacheKeys(f"{prefix}{revisions}:{identity.subject_kind}:{digest}")


class SnapshotEnvelope(BaseModel):
    """Store a strict versioned payload with HMAC integrity protection."""

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
        """Serialize the envelope fields covered by the integrity HMAC."""
        body = self.model_dump(mode="json", exclude={"integrity"})
        return json.dumps(
            body,
            ensure_ascii=False,
            separators=(",", ":"),
            sort_keys=True,
        ).encode()

    def verify(self, secret: bytes) -> bool:
        """Verify envelope integrity with a constant-time digest comparison.

        Args:
            secret: HMAC key used when the envelope was published.

        Returns:
            Whether the stored digest matches the canonical envelope.
        """
        expected = hmac.new(secret, self.signed_bytes(), hashlib.sha256).hexdigest()
        return hmac.compare_digest(self.integrity, expected)


@dataclass(frozen=True, slots=True)
class CacheResult(Generic[Value]):
    """Return a value plus the cache provenance needed by the service."""

    value: Value
    cached: bool
    stale: bool


class CacheUnavailable(RuntimeError):
    """Indicate that neither the shared cache nor a safe fallback can serve."""
