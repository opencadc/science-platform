"""FastAPI application factory and dependency wiring for the Metrics API."""

from __future__ import annotations

import asyncio
import logging
from collections.abc import AsyncIterator, Coroutine
from contextlib import asynccontextmanager
from functools import partial
from typing import Any, cast

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.openapi.utils import get_openapi
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException
from starlette.types import ExceptionHandler

from metrics.api.v1alpha1.routes import router
from metrics.core.runtime import MetricsRuntime
from metrics.core.settings import Settings
from metrics.errors import AppError, RuntimeStartupError
from metrics.schemas.status import Status, StatusReason
from metrics.telemetry import Telemetry, setup_telemetry

_logger = logging.getLogger(__name__)
_STATUS_REASONS: dict[int, tuple[StatusReason, str]] = {
    400: ("BadRequest", "The request is malformed."),
    404: ("NotFound", "The requested resource was not found."),
    405: ("Invalid", "The requested method is not allowed."),
    503: ("ServiceUnavailable", "The requested metrics report could not be produced."),
}
_SAFE_HTTP_EXCEPTION_HEADERS = frozenset({"allow", "retry-after", "www-authenticate"})


async def _await_shutdown(
    shutdown: Coroutine[Any, Any, None],
    *,
    component: str,
    pending_cancellation: asyncio.CancelledError | None = None,
) -> None:
    """Drain one shutdown task before restoring any caller cancellation."""
    cleanup = asyncio.create_task(shutdown)
    cancellation = pending_cancellation
    while True:
        try:
            await asyncio.shield(cleanup)
        except asyncio.CancelledError as exc:
            cancellation = cancellation or exc
            if cleanup.done():
                break
        except Exception:
            break
        else:
            break

    cleanup_error: Exception | None = None
    try:
        cleanup.result()
    except asyncio.CancelledError as exc:
        cancellation = cancellation or exc
    except Exception as exc:
        cleanup_error = exc

    if cleanup_error is not None:
        if cancellation is None:
            raise cleanup_error
        _logger.error(
            "%s shutdown failed during lifespan cancellation: %s",
            component,
            cleanup_error,
            exc_info=(type(cleanup_error), cleanup_error, cleanup_error.__traceback__),
        )
    if cancellation is not None:
        raise cancellation


async def _shutdown_telemetry(
    telemetry: Telemetry,
    *,
    pending_cancellation: asyncio.CancelledError | None = None,
) -> None:
    """Complete telemetry cleanup before restoring lifespan cancellation."""
    await _await_shutdown(
        telemetry.shutdown(),
        component="Telemetry",
        pending_cancellation=pending_cancellation,
    )


def _remove_generated_validation_responses(schema: dict[str, Any]) -> None:
    """Remove FastAPI's default 422 contract from the public OpenAPI schema.

    Request validation is deliberately translated to a Kubernetes ``Status``
    response with HTTP 400 by this application, so FastAPI's generated
    ``HTTPValidationError`` response would describe behavior that cannot occur.

    Args:
        schema: Mutable OpenAPI document generated for the application.
    """
    for path_item in schema.get("paths", {}).values():
        if not isinstance(path_item, dict):
            continue
        for operation in path_item.values():
            if isinstance(operation, dict):
                responses = operation.get("responses")
                if isinstance(responses, dict):
                    responses.pop("422", None)
    schemas = schema.get("components", {}).get("schemas", {})
    if isinstance(schemas, dict):
        schemas.pop("HTTPValidationError", None)
        schemas.pop("ValidationError", None)


def _safe_http_exception_headers(exc: HTTPException) -> dict[str, str]:
    """Keep only standard response headers safe for sanitized error bodies.

    Args:
        exc: Framework exception containing optional response headers.

    Returns:
        ``Cache-Control`` plus the allowlisted standard headers.
    """
    headers = {"Cache-Control": "no-store"}
    for name, value in (exc.headers or {}).items():
        if name.lower() in _SAFE_HTTP_EXCEPTION_HEADERS:
            headers[name] = value
    return headers


@asynccontextmanager
async def _application_lifespan(
    app: FastAPI,
    *,
    runtime: MetricsRuntime,
    telemetry: Telemetry,
) -> AsyncIterator[None]:
    """Start and reliably stop the injected runtime and telemetry resources."""
    started = False
    app.state.runtime = runtime
    try:
        try:
            await runtime.start()
            started = True
        except RuntimeStartupError:
            _logger.error("Application startup validation failed; see configuration docs")
            raise
        yield
    finally:
        cancellation: asyncio.CancelledError | None = None
        try:
            if started:
                try:
                    await _await_shutdown(runtime.shutdown(), component="Runtime")
                except asyncio.CancelledError as exc:
                    cancellation = exc
        finally:
            await _shutdown_telemetry(telemetry, pending_cancellation=cancellation)


def _install_openapi(app: FastAPI) -> None:
    """Install the public OpenAPI response cleanup hook."""

    def custom_openapi() -> dict[str, Any]:
        if app.openapi_schema is None:
            schema = get_openapi(
                title=app.title,
                version=app.version,
                summary=app.summary,
                description=app.description,
                routes=app.routes,
            )
            _remove_generated_validation_responses(schema)
            app.openapi_schema = schema
        return app.openapi_schema

    setattr(app, "openapi", custom_openapi)


def _install_health_routes(app: FastAPI, runtime: MetricsRuntime, telemetry: Telemetry) -> None:
    """Install liveness and readiness endpoints outside the public API schema."""

    @app.get("/livez", include_in_schema=False)
    @app.get("/healthz", include_in_schema=False)
    async def healthcheck() -> dict[str, str]:
        """Report process liveness independently of upstream dependencies."""
        return {"status": "ok"}

    @app.get("/readyz", include_in_schema=False)
    async def readiness() -> JSONResponse:
        """Report readiness and trigger bounded recovery after a cache outage."""
        ready = await runtime.check_readiness()
        telemetry.recorder.record_readiness(ready)
        return JSONResponse(
            status_code=200 if ready else 503, content={"status": "ready" if ready else "not ready"}
        )


def _status_response(status_code: int, *, headers: dict[str, str] | None = None) -> JSONResponse:
    """Create one sanitized Kubernetes Status response."""
    reason, message = _STATUS_REASONS.get(
        status_code, ("InternalError", "The request could not be completed.")
    )
    body = Status(reason=reason, message=message, code=status_code)
    return JSONResponse(
        status_code=status_code,
        content=body.model_dump(mode="json", by_alias=True),
        headers=headers or {"Cache-Control": "no-store"},
    )


async def _handle_app_error(_request: Request, exc: AppError) -> JSONResponse:
    """Map expected application failures to sanitized Status responses."""
    headers = {"Cache-Control": "no-store"}
    if exc.retry_after is not None:
        headers["Retry-After"] = str(exc.retry_after)
    return _status_response(exc.status_code, headers=headers)


async def _handle_http_error(_request: Request, exc: HTTPException) -> JSONResponse:
    """Map framework HTTP failures to the stable Status response contract."""
    return _status_response(exc.status_code, headers=_safe_http_exception_headers(exc))


async def _handle_validation_error(_request: Request, _exc: RequestValidationError) -> JSONResponse:
    """Hide request-validation internals behind a stable bad-request response."""
    return _status_response(400)


async def _handle_unexpected_error(request: Request, exc: Exception) -> JSONResponse:
    """Fail closed with a sanitized response for unexpected exceptions."""
    route = request.scope.get("route")
    _logger.error(
        "Unhandled request failure type=%s method=%s route=%s",
        type(exc).__name__,
        request.method,
        getattr(route, "path", None) or "unmatched",
    )
    return _status_response(500)


def _install_exception_handlers(app: FastAPI) -> None:
    """Bind the stable application error response handlers."""

    def register(exception_type: type[Exception], handler: object) -> None:
        """Bridge narrow concrete handlers to Starlette's broad callback type."""
        app.add_exception_handler(exception_type, cast(ExceptionHandler, handler))

    register(AppError, _handle_app_error)
    register(HTTPException, _handle_http_error)
    register(RequestValidationError, _handle_validation_error)
    app.add_exception_handler(Exception, _handle_unexpected_error)


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

    app = FastAPI(
        title=settings.app_name,
        version=settings.app_version,
        summary="CANFAR Science Platform Metrics API",
        description=(
            "API for Kueue ClusterQueue capacity, LocalQueue reservations, "
            "Session Job usage, and optional attributed efficiency across "
            "platform, user, community, and session reports."
        ),
        lifespan=partial(
            _application_lifespan,
            runtime=runtime,
            telemetry=telemetry,
        ),
    )
    app.include_router(router)
    _install_openapi(app)
    _install_health_routes(app, runtime, telemetry)
    _install_exception_handlers(app)
    return app
