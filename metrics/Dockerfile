# Production Metrics image: locked uv install into python:3.13-slim, one worker.
# Base images are pinned by digest; Dependabot (.github/dependabot.yml) bumps
# the tag and digest together so security patches still arrive.
FROM ghcr.io/astral-sh/uv:0.12.19@sha256:04d046b13e60d6bcec73cbc5e1cad25d680dea90c8573340950a0ac2d1aef424 AS uv

FROM python:3.13-slim@sha256:7c61056e61ac89e852de05f3dc6fa51a6dd2181797bceed46aa725dd7cb2cd3b AS builder

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    UV_PROJECT_ENVIRONMENT=/opt/venv \
    UV_NO_DEV=1

COPY --from=uv /uv /usr/local/bin/uv

WORKDIR /build

# Dependencies first, so a source-only change reuses this layer.
COPY pyproject.toml uv.lock README.md ./
RUN --mount=type=cache,target=/root/.cache/uv \
    uv sync --frozen --no-dev --no-install-project

COPY src ./src
RUN --mount=type=cache,target=/root/.cache/uv \
    uv sync --frozen --no-dev --no-editable

FROM python:3.13-slim@sha256:7c61056e61ac89e852de05f3dc6fa51a6dd2181797bceed46aa725dd7cb2cd3b AS runtime

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
