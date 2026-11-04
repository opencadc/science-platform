# Production Metrics image: locked uv install into python:3.13-slim, one worker.
# Base images are pinned by digest; Dependabot (.github/dependabot.yml) bumps
# the tag and digest together so security patches still arrive.
FROM ghcr.io/astral-sh/uv:0.13.7@sha256:96f40d3904aad8d0bd010539001d36c8e2188b981e1334471a90c4a1d93bb490 AS uv

# Stay on 3.13: pydantic-core ships cp313/cp314 wheels only; 3.15 forces a
# source build and slim has no C compiler (CI kind-smoke linker `cc` not found).
FROM python:3.13-slim@sha256:ae14d4ce8771606477a81446e7ef429edd6762305fc50c84507c1041fa8285d1 AS builder

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

FROM python:3.13-slim@sha256:ae14d4ce8771606477a81446e7ef429edd6762305fc50c84507c1041fa8285d1 AS runtime

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
