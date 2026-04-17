"""FastAPI application factory."""

from __future__ import annotations

from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from opentelemetry.instrumentation.fastapi import FastAPIInstrumentor
from opentelemetry.instrumentation.httpx import HTTPXClientInstrumentor
from pydantic import TypeAdapter
from redis.asyncio import Redis

from metrics.api.routes import router
from metrics.cache import InMemoryTTLCache, RedisJSONTTLCache, TTLCacheBackend
from metrics.config import Settings
from metrics.errors import AppError
from metrics.models import ErrorDetail, ErrorResponse, ResponseMetadata
from metrics.providers.composite import FallbackCapacityProvider
from metrics.providers.kueue import KueueCapacityProvider
from metrics.providers.node import NodeCapacityProvider
from metrics.providers.prometheus import PrometheusUsageProvider
from metrics.providers.static import StaticCapacityProvider, StaticUsageProvider
from metrics.service import CachedMetrics, PlatformMetricsService
from metrics.telemetry import setup_telemetry


def _build_cache_backend(
    settings: Settings,
) -> tuple[TTLCacheBackend[CachedMetrics], Redis | None]:
    if settings.cache_backend == "memory":
        return (
            InMemoryTTLCache[CachedMetrics](ttl_seconds=settings.cache_ttl_seconds),
            None,
        )

    redis_client = Redis.from_url(settings.redis_url)
    adapter = TypeAdapter(CachedMetrics)
    return (
        RedisJSONTTLCache[CachedMetrics](
            ttl_seconds=settings.cache_ttl_seconds,
            redis=redis_client,
            key_prefix=settings.redis_key_prefix,
            serializer=lambda value: adapter.dump_json(value).decode("utf-8"),
            deserializer=adapter.validate_json,
        ),
        redis_client,
    )


def create_app(
    *,
    settings: Settings | None = None,
    platform_service: PlatformMetricsService | None = None,
) -> FastAPI:
    """Create and configure the metrics API application."""
    settings = settings or Settings()
    telemetry = setup_telemetry(settings)
    redis_client: Redis | None = None
    httpx_instrumentor = HTTPXClientInstrumentor()
    fastapi_instrumented = False
    httpx_instrumented = False

    @asynccontextmanager
    async def lifespan(_app_instance: FastAPI):
        try:
            yield
        finally:
            if fastapi_instrumented:
                FastAPIInstrumentor.uninstrument_app(app)
            if httpx_instrumented:
                httpx_instrumentor.uninstrument()
            if redis_client is not None:
                await redis_client.aclose()
            if telemetry.meter_provider is not None:
                telemetry.meter_provider.shutdown()

    app = FastAPI(
        title=settings.app_name,
        version=settings.app_version,
        summary="CANFAR Science Platform Metrics API",
        description=(
            "API for platform size and utilization metrics with Kueue-first"
            "capacity sourcing, node fallback, and Prometheus-backed usage."
        ),
        lifespan=lifespan,
    )

    @app.middleware("http")
    async def otel_request_middleware(request: Request, call_next):
        scope = _metric_scope_from_path(request.url.path)
        try:
            response = await call_next(request)
        except Exception:
            if scope is not None:
                telemetry.recorder.record_http_request(
                    scope=scope,
                    status_code=500,
                    cached=False,
                )
            raise

        if scope is not None:
            telemetry.recorder.record_http_request(
                scope=scope,
                status_code=response.status_code,
                cached=response.headers.get("X-Metrics-Cached", "false").lower()
                == "true",
            )
        return response

    if settings.otel_metrics_enabled:
        FastAPIInstrumentor.instrument_app(
            app,
            meter_provider=telemetry.meter_provider,
        )
        httpx_instrumentor.instrument()
        fastapi_instrumented = True
        httpx_instrumented = True

    app.state.api_version = f"{settings.api_group}/{settings.app_version}"
    app.state.cache_control_public = settings.cache_control_public

    if platform_service is None:
        if settings.provider_mode == "static":
            capacity_provider = StaticCapacityProvider(settings=settings)
            usage_provider = StaticUsageProvider(settings=settings)
        else:
            capacity_provider = FallbackCapacityProvider(
                providers=[
                    KueueCapacityProvider(settings=settings),
                    NodeCapacityProvider(settings=settings),
                ]
            )
            usage_provider = PrometheusUsageProvider(settings=settings)
        cache, redis_client = _build_cache_backend(settings)
        platform_service = PlatformMetricsService(
            cluster_name=settings.cluster_name,
            capacity_provider=capacity_provider,
            usage_provider=usage_provider,
            cache=cache,
            metrics_recorder=telemetry.recorder,
        )

    app.state.platform_service = platform_service
    app.include_router(router)

    @app.get("/healthz", include_in_schema=False)
    async def healthcheck() -> dict[str, str]:
        return {"status": "ok"}

    @app.exception_handler(AppError)
    async def app_error_handler(_request: Request, exc: AppError) -> JSONResponse:
        body = ErrorResponse(
            version=app.state.api_version,
            metadata=ResponseMetadata(ttl=settings.cache_ttl_seconds),
            error=ErrorDetail(
                code=exc.code,
                message=exc.message,
                details=exc.details,
            ),
        )
        return JSONResponse(
            status_code=exc.status_code,
            content=body.model_dump(mode="json", by_alias=True),
        )

    @app.exception_handler(Exception)
    async def unhandled_exception_handler(
        _request: Request, exc: Exception
    ) -> JSONResponse:
        body = ErrorResponse(
            version=app.state.api_version,
            metadata=ResponseMetadata(ttl=settings.cache_ttl_seconds),
            error=ErrorDetail(
                code="internal_error",
                message="Unexpected internal server error",
                details={"exception": exc.__class__.__name__},
            ),
        )
        return JSONResponse(
            status_code=500,
            content=body.model_dump(mode="json", by_alias=True),
        )

    return app


app = create_app()


def _metric_scope_from_path(path: str) -> str | None:
    if path in ("/api/v1/metrics/platform", "/metrics"):
        return "platform"
    if path.startswith("/api/v1/metrics/users/"):
        if "/sessions/" in path:
            return "session"
        return "user"
    if path.startswith("/metrics/"):
        parts = path.split("/")
        if len(parts) >= 4:
            return "session"
        if len(parts) >= 3:
            return "user"
    return None
