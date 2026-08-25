"""Cache-coordinated active-workload accounting reads."""

from __future__ import annotations

from collections.abc import Awaitable, Callable
from datetime import UTC, datetime

from metrics.cache import CacheCoordinator, CacheIdentity, CacheResult
from metrics.services.models import AccountingSnapshot, ActiveWorkloadLifetime


class AccountingService:
    """Publish one validated snapshot per subject through shared coordination."""

    def __init__(
        self,
        *,
        user: Callable[[str], Awaitable[ActiveWorkloadLifetime]],
        community: Callable[[str], Awaitable[ActiveWorkloadLifetime]],
        user_cache: CacheCoordinator[AccountingSnapshot],
        community_cache: CacheCoordinator[AccountingSnapshot],
        user_identity: Callable[[str], CacheIdentity],
        community_identity: Callable[[str], CacheIdentity],
    ) -> None:
        """Attach controlled loaders and subject-specific cache policies."""
        self._user = user
        self._community = community
        self._user_cache = user_cache
        self._community_cache = community_cache
        self._user_identity = user_identity
        self._community_identity = community_identity

    async def get_user(self, username: str) -> CacheResult:
        """Return one cache-coordinated User accounting snapshot."""
        return await self._user_cache.get_or_fill(
            self._user_identity(username),
            lambda: self._load(self._user, username),
        )

    async def get_community(self, community: str) -> CacheResult:
        """Return one cache-coordinated Community accounting snapshot."""
        return await self._community_cache.get_or_fill(
            self._community_identity(community),
            lambda: self._load(self._community, community),
        )

    @staticmethod
    async def _load(
        loader: Callable[[str], Awaitable[ActiveWorkloadLifetime]],
        subject: str,
    ) -> AccountingSnapshot:
        lifetime = await loader(subject)
        return AccountingSnapshot(lifetime=lifetime, created=datetime.now(UTC))
