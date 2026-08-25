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


def _sanitize_http_span(span, scope: dict, _message: dict | None = None) -> None:
    """Replace concrete request targets with a bounded route template."""
    if span is None or not span.is_recording():
        return
    route = scope.get("route")
    template = getattr(route, "path", None) or "unmatched"
    span.set_attribute("http.route", template)
    span.set_attribute("url.path", template)
    span.set_attribute("http.target", template)
    span.set_attribute("url.query", "")


def _sanitize_client_span(span, *_details: object) -> None:
    """Remove downstream URLs while retaining the bounded HTTP operation."""
    if span is None or not span.is_recording():
        return
    span.set_attribute("url.full", "redacted")
    span.set_attribute("http.url", "redacted")
    span.set_attribute("url.path", "redacted")
    span.set_attribute("url.query", "")


async def _sanitize_async_client_span(span, *_details: object) -> None:
    """Sanitize an asynchronous downstream HTTP span."""
    _sanitize_client_span(span)


def create_app(
    *,
    settings: Settings,
    runtime: MetricsRuntime | None = None,
) -> FastAPI:
    """Create and configure the metrics API application."""
    telemetry = setup_telemetry(settings)
    runtime = runtime or MetricsRuntime.from_settings(settings, recorder=telemetry.recorder)
    httpx_instrumentor = HTTPXClientInstrumentor()

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        started = False
        app.state.runtime = runtime
        app.state.api_version = f"{settings.api_group}/{settings.app_version}"
        app.state.cache_control_public = settings.cache_control_public
        try:
            try:
                with telemetry.recorder.span("application.lifespan"):
                    await runtime.start()
                started = True
            except RuntimeStartupError:
                _logger.error("Application startup validation failed; see configuration docs")
                raise
            yield
        finally:
            if telemetry.enabled:
                FastAPIInstrumentor.uninstrument_app(app)
                httpx_instrumentor.uninstrument()
            if started:
                await runtime.shutdown()
            telemetry.shutdown()

    app = FastAPI(
        title=settings.app_name,
        version=settings.app_version,
        summary="CANFAR Science Platform Metrics API",
        description="API for platform metrics from the configured Kueue source.",
        lifespan=lifespan,
    )

    if telemetry.enabled:
        FastAPIInstrumentor.instrument_app(
            app,
            meter_provider=telemetry.meter_provider,
            tracer_provider=telemetry.tracer_provider,
            server_request_hook=_sanitize_http_span,
            client_request_hook=_sanitize_http_span,
            client_response_hook=_sanitize_http_span,
        )
        httpx_instrumentor.instrument(
            meter_provider=telemetry.meter_provider,
            tracer_provider=telemetry.tracer_provider,
            request_hook=_sanitize_client_span,
            response_hook=_sanitize_client_span,
            async_request_hook=_sanitize_async_client_span,
            async_response_hook=_sanitize_async_client_span,
        )

    app.include_router(router)

    @app.get("/livez", include_in_schema=False)
    @app.get("/healthz", include_in_schema=False)
    async def healthcheck() -> dict[str, str]:
        return {"status": "ok"}

    @app.get("/readyz", include_in_schema=False)
    async def readiness() -> JSONResponse:
        status = 200 if runtime.ready else 503
        telemetry.recorder.record_readiness(runtime.ready)
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
        del exc
        _logger.error("Unhandled request failure")
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
