"""Cache contracts shared by the Redis adapter and the coordinator."""

from __future__ import annotations

import hashlib
import hmac
import json
from dataclasses import dataclass
from datetime import datetime
from enum import StrEnum
from typing import Generic, Literal, TypeVar

Value = TypeVar("Value")


class Freshness(StrEnum):
    """Name the only two stages of a stored snapshot."""

    FRESH = "fresh"
    STALE = "stale"


class CacheFailureCategory(StrEnum):
    """Bound the failure semantics that may cross the Redis cache boundary."""

    SOURCE_UNAVAILABLE = "source_unavailable"
    INTERNAL = "internal"


@dataclass(frozen=True, slots=True)
class FreshnessPolicy:
    """Define the fresh and stale windows of one surface.

    A snapshot is written to Redis with a TTL equal to the stale window,
    measured from the start of the fill that produced it. Its stage is read
    from the remaining Redis TTL, so no replica or source clock is ever
    compared with another. When the TTL reaches zero the snapshot no longer
    exists.
    """

    fresh_seconds: float
    stale_seconds: float

    def __post_init__(self) -> None:
        """Require 0 < fresh < stale."""
        if not 0 < self.fresh_seconds < self.stale_seconds:
            raise ValueError("freshness windows must satisfy 0 < fresh < stale")

    @property
    def stale_ms(self) -> int:
        """Return the Redis TTL, in milliseconds, of a snapshot of age zero."""
        return round(self.stale_seconds * 1000)

    @property
    def fresh_floor_ms(self) -> int:
        """Return the smallest remaining TTL at which a snapshot is still fresh."""
        return round((self.stale_seconds - self.fresh_seconds) * 1000)

    def classify(self, ttl_ms: int) -> Freshness | None:
        """Return the stage for a remaining Redis TTL, or ``None`` once expired.

        Age equal to the fresh window is still fresh; age equal to the stale
        window is expired because Redis has already deleted the key.
        """
        if ttl_ms <= 0:
            return None
        return Freshness.FRESH if ttl_ms >= self.fresh_floor_ms else Freshness.STALE

    def age_seconds(self, ttl_ms: int) -> float:
        """Return the snapshot age implied by its remaining Redis TTL."""
        return max(0.0, self.stale_seconds - ttl_ms / 1000)


FRESHNESS_POLICIES = {
    "platform": FreshnessPolicy(5 * 60, 10 * 60),
    "user": FreshnessPolicy(2 * 60, 4 * 60),
    "community": FreshnessPolicy(5 * 60, 10 * 60),
    "session": FreshnessPolicy(30, 60),
}


@dataclass(frozen=True, slots=True)
class CacheIdentity:
    """Identify one opaque cache subject and its source contract."""

    subject_kind: Literal["platform", "user", "community", "session"]
    subject_value: str
    cluster: str
    source: str
    fingerprint: str = ""

    def canonical(self) -> bytes:
        """Encode every identity dimension deterministically."""
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
    """Name the value and lease keys of one opaque subject."""

    base: str

    @property
    def value(self) -> str:
        """Return the authenticated snapshot or not-found key."""
        return f"{self.base}:value"

    @property
    def lease(self) -> str:
        """Return the refresher-token or failure-cooldown key."""
        return f"{self.base}:lease"


def cache_keys(
    *,
    prefix: str,
    identity: CacheIdentity,
    secret: bytes,
    schema_revision: str,
    source_revision: str,
    query_revision: str,
) -> CacheKeys:
    """Derive the two opaque keys of one identity.

    The HMAC digest hides subject values from Redis key paths. It is wrapped
    in a Redis Cluster hash tag so both keys share one slot and the two-key
    scripts stay valid on a clustered Redis.
    """
    digest = hmac.new(secret, identity.canonical(), hashlib.sha256).hexdigest()
    return CacheKeys(
        f"{prefix}{schema_revision}:{source_revision}:{query_revision}:"
        f"{identity.subject_kind}:{{{digest}}}"
    )


@dataclass(frozen=True, slots=True)
class CacheResult(Generic[Value]):
    """Return a value with request-local cache and source provenance.

    Attributes:
        value: The served snapshot.
        cached: Whether a stored snapshot answered this request.
        stale: Whether the snapshot is past its fresh window.
        cache_available: Whether Redis answered the lookup.
        source_reachable: ``True`` when this request's fill read the source,
            ``None`` when the source was not probed.
        serviceable_until: Wall-clock end of the snapshot's stale window.
        age_seconds: Snapshot age measured from the start of its fill.
    """

    value: Value
    cached: bool
    stale: bool
    cache_available: bool = True
    source_reachable: bool | None = None
    serviceable_until: datetime | None = None
    age_seconds: float = 0.0


class CacheUnavailable(RuntimeError):
    """Indicate that no safe durable or local result can be returned."""

    def __init__(
        self,
        message: str = "cache result unavailable",
        *,
        cache_available: bool = False,
        source_reachable: bool | None = None,
    ) -> None:
        """Attach cache and source provenance to an unavailable result."""
        super().__init__(message)
        self.cache_available = cache_available
        self.source_reachable = source_reachable


class CacheInternalError(RuntimeError):
    """Represent a sanitized internal fill failure shared by cache replicas."""

    def __init__(self, message: str = "The source fill failed") -> None:
        """Avoid exposing the original source exception to cache consumers."""
        super().__init__(message)


class CacheFillTimeout(CacheUnavailable):
    """Indicate that a source fill exceeded its bounded deadline."""

    def __init__(self, message: str = "Cache fill timed out") -> None:
        """Mark source reachability false while preserving Redis availability."""
        super().__init__(message, cache_available=True, source_reachable=False)


class CacheNotFound(RuntimeError):
    """Represent an authenticated subject-level not-found result."""

    def __init__(self, message: str = "subject not found") -> None:
        """Keep the sanitized not-found message."""
        super().__init__(message)
