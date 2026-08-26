"""Small, signed cache contracts shared by the Redis adapter and coordinator."""

from __future__ import annotations

import hashlib
import hmac
import json
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from enum import StrEnum
from typing import Any, Generic, Literal, TypeVar

from pydantic import BaseModel, ConfigDict, model_validator

Value = TypeVar("Value")


class Freshness(StrEnum):
    """Describe how old a positive observation is."""

    FRESH = "fresh"
    STALE = "stale"
    RETAINED = "retained"
    PURGED = "purged"


class CacheFailureCategory(StrEnum):
    """Bound the failure semantics that may cross the Redis cache boundary."""

    SOURCE_UNAVAILABLE = "source_unavailable"
    INTERNAL = "internal"


@dataclass(frozen=True, slots=True)
class FreshnessPolicy:
    """Define fresh, serviceable-stale, and physical-retention windows."""

    fresh_seconds: int
    stale_seconds: int
    retention_seconds: int

    def __post_init__(self) -> None:
        """Require strictly ordered positive windows."""
        if not 0 < self.fresh_seconds < self.stale_seconds <= self.retention_seconds:
            raise ValueError("freshness boundaries must satisfy 0 < fresh < stale <= retention")

    @staticmethod
    def _normalise(value: datetime) -> datetime:
        """Return one timezone-aware UTC timestamp."""
        if value.tzinfo is None:
            return value.replace(tzinfo=UTC)
        return value.astimezone(UTC)

    def age_seconds(self, created: datetime, *, now: datetime | None = None) -> float:
        """Return non-negative age, treating a future observation as invalid."""
        clock = self._normalise(now or datetime.now(UTC))
        observed = self._normalise(created)
        return (clock - observed).total_seconds()

    def classify(self, created: datetime, *, now: datetime | None = None) -> Freshness:
        """Classify a positive observation from its signed creation time."""
        age = self.age_seconds(created, now=now)
        if age < 0:
            return Freshness.PURGED
        if age <= self.fresh_seconds:
            return Freshness.FRESH
        if age <= self.stale_seconds:
            return Freshness.STALE
        if age <= self.retention_seconds:
            return Freshness.RETAINED
        return Freshness.PURGED

    def terminal_is_fresh(self, created: datetime, *, now: datetime | None = None) -> bool:
        """Return whether a negative terminal may still suppress a requery."""
        age = self.age_seconds(created, now=now)
        return 0 <= age <= self.fresh_seconds

    def remaining_seconds(
        self,
        created: datetime,
        *,
        max_age_seconds: int | None = None,
        now: datetime | None = None,
    ) -> float:
        """Return time remaining before an observation's physical expiry."""
        max_age = self.retention_seconds if max_age_seconds is None else max_age_seconds
        return max(0.0, max_age - max(0.0, self.age_seconds(created, now=now)))


FRESHNESS_POLICIES = {
    "platform": FreshnessPolicy(5 * 60, 30 * 60, 60 * 60),
    "user": FreshnessPolicy(2 * 60, 10 * 60, 15 * 60),
    "community": FreshnessPolicy(2 * 60, 10 * 60, 15 * 60),
}


@dataclass(frozen=True, slots=True)
class CacheIdentity:
    """Identify one opaque cache subject and its source contract."""

    subject_kind: Literal["platform", "user", "community"]
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
    """Name exactly the value and lease keys for one opaque subject."""

    base: str
    identity_digest: str = ""

    @property
    def value(self) -> str:
        """Return the stable signed-envelope key."""
        return f"{self.base}:value"

    @property
    def lease(self) -> str:
        """Return the stable token-owned lease key."""
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
    """Derive two opaque keys and the identity digest signed in their value."""
    digest = hmac.new(secret, identity.canonical(), hashlib.sha256).hexdigest()
    base = f"{prefix}{schema_revision}:{source_revision}:{query_revision}:{identity.subject_kind}:{digest}"
    return CacheKeys(base=base, identity_digest=digest)


class CacheEnvelope(BaseModel):
    """Hold one authenticated value, terminal, or failed-fill outcome."""

    model_config = ConfigDict(extra="forbid", strict=True)

    format: Literal["metrics-cache-v2"]
    identity_digest: str
    schema_revision: str
    source_revision: str
    query_revision: str
    kind: Literal["value", "not_found", "failure"]
    created: datetime
    value: Any | None = None
    failure_category: CacheFailureCategory | None = None
    integrity: str

    @model_validator(mode="after")
    def validate_payload(self) -> CacheEnvelope:
        """Reject envelopes whose kind and bounded payload disagree."""
        if self.kind == "failure":
            if self.value is not None or self.failure_category is None:
                raise ValueError("failure envelopes require only a failure category")
        elif self.failure_category is not None:
            raise ValueError("only failure envelopes may carry a failure category")
        return self

    def signed_bytes(self) -> bytes:
        """Serialize every field except the HMAC in canonical JSON."""
        return json.dumps(
            self.model_dump(mode="json", exclude={"integrity"}),
            ensure_ascii=False,
            separators=(",", ":"),
            sort_keys=True,
        ).encode()

    def sign(self, secret: bytes) -> CacheEnvelope:
        """Return a copy signed with the configured HMAC secret."""
        digest = hmac.new(secret, self.signed_bytes(), hashlib.sha256).hexdigest()
        return self.model_copy(update={"integrity": digest})

    def verify(self, secret: bytes) -> bool:
        """Verify the canonical HMAC in constant time."""
        expected = hmac.new(secret, self.signed_bytes(), hashlib.sha256).hexdigest()
        return hmac.compare_digest(self.integrity, expected)


@dataclass(frozen=True, slots=True)
class CacheResult(Generic[Value]):
    """Return a value with request-local cache and source provenance."""

    value: Value
    cached: bool
    stale: bool
    cache_available: bool = True
    source_reachable: bool | None = None
    serviceable_until: datetime | None = None


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
    """Represent an authenticated subject-level not-found terminal."""

    def __init__(
        self,
        message: str = "subject not found",
        *,
        cache_available: bool = True,
        source_reachable: bool | None = None,
    ) -> None:
        """Attach request-local provenance without changing HTTP mapping."""
        super().__init__(message)
        self.cache_available = cache_available
        self.source_reachable = source_reachable


def serviceable_until(created: datetime, policy: FreshnessPolicy) -> datetime:
    """Return the end of a positive observation's serviceable window."""
    observed = policy._normalise(created)
    return observed + timedelta(seconds=policy.stale_seconds)
