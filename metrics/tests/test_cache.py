from __future__ import annotations

import json

import pytest
from redis.exceptions import RedisError

from metrics.cache import InMemoryTTLCache, RedisJSONTTLCache


class FakeRedis:
    def __init__(self, *, fail_get: bool = False, fail_set: bool = False) -> None:
        self._values: dict[str, str] = {}
        self._fail_get = fail_get
        self._fail_set = fail_set

    async def get(self, key: str) -> str | None:
        if self._fail_get:
            raise RedisError("get failed")
        return self._values.get(key)

    async def set(self, key: str, value: str, *, ex: int) -> None:
        del ex
        if self._fail_set:
            raise RedisError("set failed")
        self._values[key] = value


@pytest.mark.anyio
async def test_in_memory_cache_hit_and_expiry() -> None:
    now = 100.0

    def clock() -> float:
        return now

    cache = InMemoryTTLCache[int](ttl_seconds=5, clock=clock)
    await cache.set("k", 1)
    assert await cache.get("k") == 1

    now = 106.0
    assert await cache.get("k") is None


@pytest.mark.anyio
async def test_redis_cache_round_trip() -> None:
    cache = RedisJSONTTLCache[int](
        ttl_seconds=30,
        redis=FakeRedis(),
        key_prefix="metrics:",
        serializer=lambda value: json.dumps({"value": value}),
        deserializer=lambda payload: json.loads(payload)["value"],
    )

    await cache.set("example", 42)
    assert await cache.get("example") == 42


@pytest.mark.anyio
async def test_redis_cache_degrades_on_get_error() -> None:
    cache = RedisJSONTTLCache[int](
        ttl_seconds=30,
        redis=FakeRedis(fail_get=True),
        key_prefix="metrics:",
        serializer=lambda value: json.dumps({"value": value}),
        deserializer=lambda payload: json.loads(payload)["value"],
    )

    assert await cache.get("example") is None


@pytest.mark.anyio
async def test_redis_cache_degrades_on_set_error() -> None:
    cache = RedisJSONTTLCache[int](
        ttl_seconds=30,
        redis=FakeRedis(fail_set=True),
        key_prefix="metrics:",
        serializer=lambda value: json.dumps({"value": value}),
        deserializer=lambda payload: json.loads(payload)["value"],
    )

    await cache.set("example", 42)
    assert await cache.get("example") is None
