# Production Metrics image: locked uv install into python:3.13-slim, one worker.
# UV pin matches the supported local toolchain; bumping it is an explicit change.
ARG UV_VERSION=0.12.5

FROM ghcr.io/astral-sh/uv:${UV_VERSION} AS uv

FROM python:3.13-slim AS builder

ARG UV_VERSION=0.12.5

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    UV_PROJECT_ENVIRONMENT=/opt/venv \
    UV_NO_DEV=1

COPY --from=uv /uv /usr/local/bin/uv

WORKDIR /build

COPY pyproject.toml uv.lock README.md ./
COPY src ./src

RUN --mount=type=cache,target=/root/.cache/uv \
    uv sync --frozen --no-dev --no-editable

FROM python:3.13-slim AS runtime

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PATH="/opt/venv/bin:$PATH" \
    METRICS_HOST=0.0.0.0 \
    METRICS_PORT=8000

WORKDIR /app

RUN groupadd --system --gid 65532 metrics \
    && useradd --system --uid 65532 --gid metrics --home-dir /nonexistent --shell /usr/sbin/nologin metrics

COPY --from=builder --chown=metrics:metrics /opt/venv /opt/venv

USER metrics

EXPOSE 8000

# Exactly one Uvicorn worker; scale with Pod replicas.
CMD ["python", "-m", "metrics.main"]
