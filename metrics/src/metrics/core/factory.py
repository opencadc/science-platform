"""FastAPI application factory and dependency wiring for the Metrics API."""

from __future__ import annotations

import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from opentelemetry.instrumentation.fastapi import FastAPIInstrumentor
from opentelemetry.instrumentation.httpx import HTTPXClientInstrumentor
from starlette.exceptions import HTTPException

from metrics.api.v1alpha1.routes import router
from metrics.core.runtime import MetricsRuntime
from metrics.core.settings import Settings
from metrics.errors import AppError, RuntimeStartupError
from metrics.schemas.status import Status, StatusReason
from metrics.telemetry import setup_telemetry

_logger = logging.getLogger(__name__)
_STATUS_REASONS: dict[int, tuple[StatusReason, str]] = {
    400: ("BadRequest", "The request is malformed."),
    404: ("NotFound", "The requested resource was not found."),
    422: ("Invalid", "The requested subject is not supported."),
    503: ("ServiceUnavailable", "The requested metrics report could not be produced."),
}


def _sanitize_http_span(span, scope: dict, _message: dict | None = None) -> None:
    """Replace concrete request targets with a bounded route template.

    Args:
        span: OpenTelemetry server span, when recording is active.
        scope: ASGI scope containing the matched FastAPI route.
        _message: Optional ASGI message accepted by response hooks.
    """
    if span is None or not span.is_recording():
        return
    route = scope.get("route")
    template = getattr(route, "path", None) or "unmatched"
    span.set_attribute("http.route", template)
    span.set_attribute("http.url", template)
    span.set_attribute("url.full", template)
    span.set_attribute("url.path", template)
    span.set_attribute("http.target", template)
    span.set_attribute("url.query", "")


def _sanitize_client_span(span, *_details: object) -> None:
    """Remove downstream URLs while retaining the bounded HTTP operation.

    Args:
        span: OpenTelemetry client span, when recording is active.
        *_details: Instrumentor-specific request or response details.
    """
    if span is None or not span.is_recording():
        return
    span.set_attribute("url.full", "redacted")
    span.set_attribute("http.url", "redacted")
    span.set_attribute("url.path", "redacted")
    span.set_attribute("url.query", "")


async def _sanitize_async_client_span(span, *_details: object) -> None:
    """Apply client URL redaction through the async instrumentor hook.

    Args:
        span: OpenTelemetry client span, when recording is active.
        *_details: Instrumentor-specific request or response details.
    """
    _sanitize_client_span(span)


def create_app(
    *,
    settings: Settings,
    runtime: MetricsRuntime | None = None,
) -> FastAPI:
    """Create the API and bind its runtime, telemetry, routes, and handlers.

    The returned application owns startup and shutdown of all injected
    resources through its lifespan.

    Args:
        settings: Validated process configuration.
        runtime: Optional pre-wired runtime, primarily for controlled callers.

    Returns:
        A configured FastAPI application.
    """
    telemetry = setup_telemetry(settings)
    runtime = runtime or MetricsRuntime.from_settings(settings, recorder=telemetry.recorder)
    httpx_instrumentor = HTTPXClientInstrumentor()

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        """Start and reliably stop runtime and telemetry resources."""
        started = False
        app.state.runtime = runtime
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
        """Report process liveness independently of upstream dependencies."""
        return {"status": "ok"}

    @app.get("/readyz", include_in_schema=False)
    async def readiness() -> JSONResponse:
        """Report whether startup completed and required caches remain available."""
        status = 200 if runtime.ready else 503
        telemetry.recorder.record_readiness(runtime.ready)
        return JSONResponse(
            status_code=status, content={"status": "ready" if status == 200 else "not ready"}
        )

    @app.exception_handler(AppError)
    async def app_error_handler(_request: Request, exc: AppError) -> JSONResponse:
        """Map an expected application failure to a sanitized Status response."""
        reason, message = _STATUS_REASONS.get(
            exc.status_code, ("InternalError", "The request could not be completed.")
        )
        body = Status(reason=reason, message=message, code=exc.status_code)
        headers = {"Cache-Control": "no-store"}
        if exc.retry_after is not None:
            headers["Retry-After"] = str(exc.retry_after)
        return JSONResponse(
            status_code=exc.status_code,
            content=body.model_dump(mode="json", by_alias=True),
            headers=headers,
        )

    @app.exception_handler(HTTPException)
    async def http_error_handler(_request: Request, exc: HTTPException) -> JSONResponse:
        """Map framework HTTP failures to the stable Status response contract."""
        reason, message = _STATUS_REASONS.get(
            exc.status_code, ("InternalError", "The request could not be completed.")
        )
        body = Status(reason=reason, message=message, code=exc.status_code)
        return JSONResponse(
            status_code=exc.status_code,
            content=body.model_dump(mode="json", by_alias=True),
            headers={"Cache-Control": "no-store"},
        )

    @app.exception_handler(RequestValidationError)
    async def validation_error_handler(
        _request: Request, _exc: RequestValidationError
    ) -> JSONResponse:
        """Hide request-validation internals behind a stable bad-request response."""
        reason, message = _STATUS_REASONS[400]
        body = Status(reason=reason, message=message, code=400)
        return JSONResponse(
            status_code=400,
            content=body.model_dump(mode="json", by_alias=True),
            headers={"Cache-Control": "no-store"},
        )

    @app.exception_handler(Exception)
    async def unhandled_exception_handler(_request: Request, exc: Exception) -> JSONResponse:
        """Fail closed with a sanitized response for unexpected exceptions."""
        del exc
        _logger.error("Unhandled request failure")
        body = Status(
            reason="InternalError",
            message="The request could not be completed.",
            code=500,
        )
        return JSONResponse(
            status_code=500,
            content=body.model_dump(mode="json", by_alias=True),
            headers={"Cache-Control": "no-store"},
        )

    return app
