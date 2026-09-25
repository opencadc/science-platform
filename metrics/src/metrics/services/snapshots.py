"""Assemble each surface's cached snapshot from its sources.

A snapshot is one primary observation (Kueue or Session Jobs) plus optional
enrichment: live Session usage and PromQL efficiency. Optional work starts only
after the primary read proves the subject exists and has workloads, and runs
only within what is left of the fill deadline, so a slow optional source
degrades a report to ``PartialData`` instead of failing the whole fill.
"""

from __future__ import annotations

import asyncio
import logging
from collections.abc import Awaitable, Callable
from datetime import datetime
from time import perf_counter
from typing import Protocol, TypeVar

from metrics.cache import CacheNotFound, CacheUnavailable, describe_failure, fill_budget
from metrics.errors import ProviderExecutionError, ProviderUnavailableError, SubjectNotFoundError
from metrics.services.models import (
    CachedSnapshot,
    CommunityObservation,
    EfficiencyObservation,
    PlatformObservation,
    SessionObservation,
    SessionUsageObservation,
    UserObservation,
)
from metrics.telemetry import MetricsRecorder, NoopMetricsRecorder

_logger = logging.getLogger(__name__)
_T = TypeVar("_T")
_PUBLISH_RESERVE_SECONDS = 0.5
_MIN_SESSION_WINDOW_SECONDS = 60.0


class KueueSource(Protocol):
    """Read the Kueue-backed surfaces."""

    async def read_platform(self) -> PlatformObservation:
        """Read Platform capacity and allocation."""

    async def read_user(self, username: str) -> UserObservation:
        """Read one User's LocalQueue reservations."""

    async def read_community(self, community: str) -> CommunityObservation:
        """Read one Community's ClusterQueue reservations."""


class SessionSource(Protocol):
    """Read Session Jobs and pod state."""

    async def read_session(self, session_id: str) -> SessionObservation:
        """Read one Session's Jobs."""


class UsageSource(Protocol):
    """Read live Session usage."""

    async def read_session_usage(self, observation: SessionObservation) -> SessionUsageObservation:
        """Sum one Session's Running-pod usage."""


class EfficiencySource(Protocol):
    """Read optional CPU and memory efficiency."""

    async def read_platform(self) -> EfficiencyObservation:
        """Read Platform efficiency."""

    async def read_user(self, username: str) -> EfficiencyObservation:
        """Read one User's efficiency."""

    async def read_community(self, community: str) -> EfficiencyObservation:
        """Read one Community's efficiency."""

    async def read_session(
        self,
        session_id: str,
        *,
        start_time: datetime,
        window_end: datetime,
        job_names: tuple[str, ...] = (),
    ) -> EfficiencyObservation:
        """Read one Session's duration efficiency."""


def _oldest(first: datetime, *others: datetime | None) -> datetime:
    """Return the oldest of several optional timestamps."""
    return min([first, *(other for other in others if other is not None)])


class SnapshotLoader:
    """Build one cached snapshot per surface inside a cache fill."""

    def __init__(
        self,
        *,
        kueue: KueueSource,
        session: SessionSource,
        usage: UsageSource,
        efficiency: EfficiencySource | None = None,
        usage_timeout_seconds: float = 5.0,
        efficiency_timeout_seconds: float = 5.0,
        telemetry: MetricsRecorder | None = None,
    ) -> None:
        """Attach the primary and optional sources and each optional source's bound."""
        if usage_timeout_seconds <= 0 or efficiency_timeout_seconds <= 0:
            raise ValueError("optional source timeouts must be positive")
        self._kueue = kueue
        self._session = session
        self._usage = usage
        self._efficiency = efficiency
        self._usage_timeout = usage_timeout_seconds
        self._efficiency_timeout = efficiency_timeout_seconds
        self._telemetry = telemetry or NoopMetricsRecorder()

    async def _primary(self, scope: str, provider: str, read: Callable[[], Awaitable[_T]]) -> _T:
        """Run one primary read, mapping provider failures onto cache outcomes."""
        started = perf_counter()
        status = "ok"
        try:
            return await read()
        except SubjectNotFoundError as exc:
            status = "not_found"
            raise CacheNotFound() from exc
        except (ProviderUnavailableError, ProviderExecutionError) as exc:
            status = "error"
            raise CacheUnavailable(
                f"{scope} source is unavailable", cache_available=True, source_reachable=False
            ) from exc
        except asyncio.CancelledError:
            status = "cancelled"
            raise
        except Exception:
            status = "error"
            raise
        finally:
            self._telemetry.record_provider_duration(
                provider=provider, scope=scope, status=status, seconds=perf_counter() - started
            )

    @staticmethod
    def _budget(bound: float) -> float:
        """Return the time one optional read may use: its bound or the fill's remainder."""
        remaining = fill_budget()
        if remaining is None:
            return bound
        return min(bound, remaining - _PUBLISH_RESERVE_SECONDS)

    async def _optional(
        self, scope: str, source: str, read: Callable[[], Awaitable[_T]], budget: float
    ) -> _T | None:
        """Run one optional read within ``budget``; ``None`` means it failed."""
        if budget <= 0:
            _logger.warning("optional %s skipped scope=%s: fill deadline reached", source, scope)
            return None
        try:
            async with asyncio.timeout(budget):
                return await read()
        except Exception as exc:
            _logger.warning(
                "optional %s unavailable scope=%s error=%s", source, scope, describe_failure(exc)
            )
            return None

    async def platform(self) -> CachedSnapshot:
        """Read Platform queue state, then efficiency when workloads are reserving."""
        observation = await self._primary("platform", "kueue", self._kueue.read_platform)
        return await self._with_efficiency(
            "platform", observation, lambda source: source.read_platform()
        )

    async def user(self, username: str) -> CachedSnapshot:
        """Read one User's queues, then efficiency when the user has workloads."""
        observation = await self._primary("user", "kueue", lambda: self._kueue.read_user(username))
        return await self._with_efficiency(
            "user", observation, lambda source: source.read_user(username)
        )

    async def community(self, community: str) -> CachedSnapshot:
        """Read one Community's queues, then efficiency when it has workloads."""
        observation = await self._primary(
            "community", "kueue", lambda: self._kueue.read_community(community)
        )
        return await self._with_efficiency(
            "community", observation, lambda source: source.read_community(community)
        )

    async def _with_efficiency(
        self,
        scope: str,
        observation: PlatformObservation | UserObservation | CommunityObservation,
        read: Callable[[EfficiencySource], Awaitable[EfficiencyObservation]],
    ) -> CachedSnapshot:
        """Add efficiency for a subject that exists and has reserving workloads.

        Without reserving workloads there are no labelled Running pods, so
        there is no ratio to report and nothing is queried.
        """
        source = self._efficiency
        if source is None or observation.reserving_workloads == 0:
            return CachedSnapshot(observation=observation, created=observation.observed_at)
        efficiency = await self._optional(
            scope, "efficiency", lambda: read(source), self._budget(self._efficiency_timeout)
        )
        return CachedSnapshot(
            observation=observation,
            created=_oldest(
                observation.observed_at, efficiency.observed_at if efficiency else None
            ),
            efficiency=efficiency,
            partial=efficiency is None,
        )

    async def session(self, session_id: str) -> CachedSnapshot:
        """Read one Session's Jobs, then its usage and duration efficiency together.

        Efficiency is reported per requested resource, so a session without
        active Jobs (no requests) or with a window under a minute skips it.
        """
        observation = await self._primary(
            "session", "session", lambda: self._session.read_session(session_id)
        )
        usage_task: asyncio.Future[SessionUsageObservation | None] | None = None
        efficiency_task: asyncio.Future[EfficiencyObservation | None] | None = None
        if observation.has_running_pods:
            usage_task = asyncio.ensure_future(
                self._optional(
                    "session",
                    "usage",
                    lambda: self._usage.read_session_usage(observation),
                    self._budget(self._usage_timeout),
                )
            )
        source = self._efficiency
        start_time = observation.start_time
        if (
            source is not None
            and observation.reserving_workloads > 0
            and start_time is not None
            and (observation.window_end - start_time).total_seconds() >= _MIN_SESSION_WINDOW_SECONDS
        ):
            efficiency_task = asyncio.ensure_future(
                self._optional(
                    "session",
                    "efficiency",
                    lambda: source.read_session(
                        session_id,
                        start_time=start_time,
                        window_end=observation.window_end,
                        job_names=observation.job_names,
                    ),
                    self._budget(self._efficiency_timeout),
                )
            )
        tasks = [task for task in (usage_task, efficiency_task) if task is not None]
        try:
            await asyncio.gather(*tasks)
        except BaseException:
            for task in tasks:
                task.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)
            raise
        usage = None if usage_task is None else usage_task.result()
        efficiency = None if efficiency_task is None else efficiency_task.result()
        partial = (
            not observation.pods_reachable
            or (usage_task is not None and usage is None)
            or (efficiency_task is not None and efficiency is None)
        )
        if not observation.pods_reachable:
            _logger.warning("optional pod state unavailable scope=session")
        return CachedSnapshot(
            observation=observation,
            # Duration efficiency describes the whole window, not a moment, so
            # its evaluation time does not age the report.
            created=_oldest(observation.observed_at, usage.observed_at if usage else None),
            efficiency=efficiency,
            usage=(usage.usage or None) if usage is not None else None,
            partial=partial,
        )
