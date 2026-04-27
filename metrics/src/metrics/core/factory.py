"""FastAPI application factory and dependency wiring for the Metrics API."""

from __future__ import annotations

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from opentelemetry.instrumentation.fastapi import FastAPIInstrumentor
from opentelemetry.instrumentation.httpx import HTTPXClientInstrumentor

from metrics.api.v1.routes import router
from metrics.core.runtime import MetricsRuntime
from metrics.core.settings import Settings, apply_metrics_package_log_level
from metrics.errors import AppError, RuntimeStartupError
from metrics.schemas.metrics import ErrorDetail, ErrorResponse, ResponseMetadata
from metrics.services.platform import PlatformMetricsService
from metrics.telemetry import setup_telemetry

_logger = logging.getLogger(__name__)


def _metric_scope_from_path(path: str) -> str | None:
    if path == "/api/v1/metrics/platform":
        return "platform"
    return None


def create_app(
    *,
    settings: Settings | None = None,
    platform_service: PlatformMetricsService | None = None,
) -> FastAPI:
    """Create and configure the metrics API application."""
    settings = settings or Settings()
    apply_metrics_package_log_level(settings)
    telemetry = setup_telemetry(settings)
    httpx_instrumentor = HTTPXClientInstrumentor()
    fastapi_instrumented = False
    httpx_instrumented = False
    _injected_platform = platform_service

    @asynccontextmanager
    async def lifespan(_app: FastAPI):
        nonlocal fastapi_instrumented, httpx_instrumented
        if _injected_platform is not None:
            runtime = MetricsRuntime.for_injected_platform(
                settings,
                _injected_platform,
                recorder=telemetry.recorder,
            )
            _app.state.runtime = runtime
            _app.state.api_version = f"{settings.api_group}/{settings.app_version}"
            _app.state.cache_control_public = settings.cache_control_public
            try:
                yield
            finally:
                if fastapi_instrumented:
                    FastAPIInstrumentor.uninstrument_app(_app)
                if httpx_instrumented:
                    httpx_instrumentor.uninstrument()
                if telemetry.meter_provider is not None:
                    telemetry.meter_provider.shutdown()
            return

        runtime = MetricsRuntime.from_settings(settings, recorder=telemetry.recorder)
        _app.state.runtime = runtime
        _app.state.api_version = f"{settings.api_group}/{settings.app_version}"
        _app.state.cache_control_public = settings.cache_control_public
        try:
            try:
                await runtime.start()
            except RuntimeStartupError:
                _logger.exception("Application startup validation failed; see configuration docs")
                raise
            yield
        finally:
            if fastapi_instrumented:
                FastAPIInstrumentor.uninstrument_app(_app)
            if httpx_instrumented:
                httpx_instrumentor.uninstrument()
            await runtime.shutdown()
            if telemetry.meter_provider is not None:
                telemetry.meter_provider.shutdown()

    app = FastAPI(
        title=settings.app_name,
        version=settings.app_version,
        summary="CANFAR Science Platform Metrics API",
        description=("API for platform metrics from configured Kueue and reserved sources."),
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
                cached=getattr(request.state, "metrics_cache_hit", False),
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

    app.include_router(router)

    @app.get("/healthz", include_in_schema=False)
    async def healthcheck() -> dict[str, str]:
        return {"status": "ok"}

    @app.exception_handler(AppError)
    async def app_error_handler(_request: Request, exc: AppError) -> JSONResponse:
        body = ErrorResponse(
            version=app.state.api_version,
            metadata=ResponseMetadata(),
            error=ErrorDetail(
                code=exc.code,
                message=exc.message,
                details=exc.details,
            ),
        )
        return JSONResponse(
            status_code=exc.status_code,
            content=body.model_dump(mode="json", by_alias=True),
            headers={"Cache-Control": "no-store"},
        )

    @app.exception_handler(Exception)
    async def unhandled_exception_handler(_request: Request, exc: Exception) -> JSONResponse:
        body = ErrorResponse(
            version=app.state.api_version,
            metadata=ResponseMetadata(),
            error=ErrorDetail(
                code="internal_error",
                message="Unexpected internal server error",
                details={"exception": exc.__class__.__name__},
            ),
        )
        return JSONResponse(
            status_code=500,
            content=body.model_dump(mode="json", by_alias=True),
            headers={"Cache-Control": "no-store"},
        )

    return app


def _attach_app_state(app: FastAPI, settings: Settings) -> None:
    """Set API version and cache headers on ``app.state`` (used by the module-level app)."""
    app.state.api_version = f"{settings.api_group}/{settings.app_version}"
    app.state.cache_control_public = settings.cache_control_public


app = create_app()
_attach_app_state(app, Settings())
