"""FastAPI application factory and dependency wiring for the Metrics API."""

from __future__ import annotations

import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from opentelemetry.instrumentation.fastapi import FastAPIInstrumentor
from opentelemetry.instrumentation.httpx import HTTPXClientInstrumentor

from metrics.api.v1.routes import router
from metrics.core.runtime import MetricsRuntime
from metrics.core.settings import Settings
from metrics.errors import AppError, RuntimeStartupError
from metrics.schemas.metrics import ErrorDetail, ErrorResponse, ResponseMetadata
from metrics.telemetry import setup_telemetry

_logger = logging.getLogger(__name__)


def create_app(
    *,
    settings: Settings,
    runtime: MetricsRuntime | None = None,
) -> FastAPI:
    """Create and configure the metrics API application."""
    recorder, meter_provider = setup_telemetry(settings)
    runtime = runtime or MetricsRuntime.from_settings(settings, recorder=recorder)
    httpx_instrumentor = HTTPXClientInstrumentor()

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        started = False
        app.state.runtime = runtime
        app.state.api_version = f"{settings.api_group}/{settings.app_version}"
        app.state.cache_control_public = settings.cache_control_public
        try:
            try:
                await runtime.start()
                started = True
            except RuntimeStartupError:
                _logger.exception("Application startup validation failed; see configuration docs")
                raise
            yield
        finally:
            if settings.otel_metrics_enabled:
                FastAPIInstrumentor.uninstrument_app(app)
                httpx_instrumentor.uninstrument()
            if started:
                await runtime.shutdown()
            if meter_provider is not None:
                meter_provider.shutdown()

    app = FastAPI(
        title=settings.app_name,
        version=settings.app_version,
        summary="CANFAR Science Platform Metrics API",
        description="API for platform metrics from the configured Kueue source.",
        lifespan=lifespan,
    )

    if settings.otel_metrics_enabled:
        FastAPIInstrumentor.instrument_app(
            app,
            meter_provider=meter_provider,
        )
        httpx_instrumentor.instrument()

    app.include_router(router)

    @app.get("/livez", include_in_schema=False)
    @app.get("/healthz", include_in_schema=False)
    async def healthcheck() -> dict[str, str]:
        return {"status": "ok"}

    @app.get("/readyz", include_in_schema=False)
    async def readiness() -> JSONResponse:
        status = 200 if runtime.ready else 503
        return JSONResponse(
            status_code=status, content={"status": "ready" if status == 200 else "not ready"}
        )

    @app.exception_handler(AppError)
    async def app_error_handler(_request: Request, exc: AppError) -> JSONResponse:
        body = ErrorResponse(
            version=app.state.api_version,
            metadata=ResponseMetadata(),
            error=ErrorDetail(code=exc.code, message=exc.message),
        )
        headers = {"Cache-Control": "no-store"}
        if exc.retry_after is not None:
            headers["Retry-After"] = str(exc.retry_after)
        return JSONResponse(
            status_code=exc.status_code,
            content=body.model_dump(mode="json", by_alias=True),
            headers=headers,
        )

    @app.exception_handler(Exception)
    async def unhandled_exception_handler(_request: Request, exc: Exception) -> JSONResponse:
        _logger.error("Unhandled request failure", exc_info=exc)
        body = ErrorResponse(
            version=app.state.api_version,
            metadata=ResponseMetadata(),
            error=ErrorDetail(
                code="internal_error",
                message="Unexpected internal server error",
            ),
        )
        return JSONResponse(
            status_code=500,
            content=body.model_dump(mode="json", by_alias=True),
            headers={"Cache-Control": "no-store"},
        )

    return app
