FROM python:3.13-alpine AS builder

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /build

COPY pyproject.toml README.md ./
COPY src ./src

RUN python -m venv /opt/venv \
    && /opt/venv/bin/pip install --no-cache-dir --no-compile . \
    && find /opt/venv -type d -name "__pycache__" -prune -exec rm -rf '{}' + \
    && find /opt/venv -type d \( -name "test" -o -name "tests" \) -prune -exec rm -rf '{}' + \
    && find /opt/venv -type f \( -name "*.pyc" -o -name "*.pyo" \) -delete \
    && find /opt/venv -type f \( -name "*.pyi" -o -name "py.typed" \) -delete \
    && rm -rf /opt/venv/lib/python*/site-packages/pip* \
        /opt/venv/lib/python*/site-packages/setuptools* \
        /opt/venv/lib/python*/site-packages/wheel* \
        /opt/venv/bin/pip*

FROM python:3.13-alpine AS runtime

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PATH="/opt/venv/bin:$PATH" \
    METRICS_HOST=0.0.0.0 \
    METRICS_PORT=8000

WORKDIR /app

RUN addgroup -S -g 65532 metrics \
    && adduser -S -D -H -h /nonexistent -G metrics -u 65532 metrics

COPY --from=builder --chown=metrics:metrics /opt/venv /opt/venv

USER metrics

EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/healthz', timeout=3)"

CMD ["python", "-m", "metrics.main"]
