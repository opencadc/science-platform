"""Grafana dashboards query only the series and labels Metrics actually exports.

The Metrics service pushes OTLP to a Prometheus-compatible receiver that keeps
dotted names and adds unit and type suffixes (``NoUTF8EscapingWithSuffixes``):
``canfar.metrics.compute.duration`` in seconds becomes
``canfar.metrics.compute.duration_seconds_bucket`` and friends, a counter gains
``_total``, and an up-down counter keeps its bare name. A dashboard query that
names anything else matches nothing and fails silently, so this test drives
every recorder method, derives the exported series, and checks each dashboard
against them.
"""

from __future__ import annotations

import json
import re
from collections.abc import Iterator
from pathlib import Path
from typing import Any

import pytest
from opentelemetry.sdk.metrics import MeterProvider
from opentelemetry.sdk.metrics.export import Histogram, InMemoryMetricReader, Sum

from metrics.telemetry import instruments
from metrics.telemetry.instruments import OpenTelemetryMetricsRecorder

DASHBOARDS = Path(__file__).parents[2] / "helm" / "dashboards"
_UNIT_SUFFIXES = {"s": "_seconds", "1": ""}
_SERIES = re.compile(r'\{"(canfar\.metrics\.[a-z_.]+)"([^}]*)\}')
_MATCHER = re.compile(r'"?([A-Za-z_][A-Za-z0-9_.]*)"?\s*(=~|!~|!=|=)\s*"([^"]*)"')
_GROUPING = re.compile(r"\b(?:by|without)\s*\(([^)]*)\)")
_PROMETHEUS_LABELS = {"job", "instance", "le"}
_ALLOWED_VALUES = {
    "cache.backend": instruments._BACKENDS,  # noqa: SLF001
    "cache.result": instruments._CACHE_RESULTS,  # noqa: SLF001
    "db.operation.name": instruments._REDIS_OPERATIONS,  # noqa: SLF001
    "lease.outcome": instruments._LEASE_OUTCOMES,  # noqa: SLF001
    "lifecycle.operation": instruments._LIFECYCLE_OPERATIONS,  # noqa: SLF001
    "metrics.scope": instruments._SCOPES,  # noqa: SLF001
    "provider.name": instruments._PROVIDERS,  # noqa: SLF001
    "result.status": instruments._STATUSES,  # noqa: SLF001
}


def _exported() -> tuple[set[str], set[str]]:
    """Record through every instrument and return the exported series and labels."""
    reader = InMemoryMetricReader()
    provider = MeterProvider(metric_readers=[reader])
    recorder = OpenTelemetryMetricsRecorder(meter=provider.get_meter("dashboard-contract"))
    recorder.record_cache_lookup(backend="redis", result="hit", scope="user", age_seconds=1.0)
    recorder.record_lease(outcome="acquired", scope="user")
    recorder.record_fill_duration(seconds=0.1, outcome="ok", scope="user")
    recorder.record_compute_duration(seconds=0.1, status="ok", scope="user")
    recorder.record_provider_duration(provider="kueue", scope="user", status="error", seconds=0.1)
    recorder.record_redis(operation="observe", outcome="ok", seconds=0.001)
    recorder.record_lifecycle(operation="startup", outcome="ok", seconds=0.1)
    recorder.record_readiness(True)
    data = reader.get_metrics_data()
    provider.shutdown()
    assert data is not None

    series: set[str] = set()
    labels: set[str] = set(_PROMETHEUS_LABELS)
    for resource_metrics in data.resource_metrics:
        for scope_metrics in resource_metrics.scope_metrics:
            for metric in scope_metrics.metrics:
                base = metric.name + _UNIT_SUFFIXES[metric.unit]
                if isinstance(metric.data, Histogram):
                    series |= {f"{base}_bucket", f"{base}_count", f"{base}_sum"}
                elif isinstance(metric.data, Sum) and metric.data.is_monotonic:
                    series.add(f"{base}_total")
                else:
                    series.add(base)
                for point in metric.data.data_points:
                    labels |= set(point.attributes or {})
    return series, labels


def _queries(dashboard: dict[str, Any]) -> Iterator[tuple[str, str]]:
    """Yield (where, PromQL) for every panel target and query variable."""

    def panels(items: list[dict[str, Any]]) -> Iterator[dict[str, Any]]:
        for item in items:
            yield item
            yield from panels(item.get("panels", []))

    for panel in panels(dashboard.get("panels", [])):
        for target in panel.get("targets", []):
            if target.get("expr"):
                yield f"panel {panel['id']} {target.get('refId')}", target["expr"]
    for variable in dashboard.get("templating", {}).get("list", []):
        query = variable.get("query")
        if isinstance(query, dict):
            query = query.get("query")
        if isinstance(query, str) and query:
            yield f"variable {variable['name']}", query


def _dashboard_queries() -> list[tuple[str, str]]:
    return [
        (f"{path.name} {where}", expr)
        for path in sorted(DASHBOARDS.glob("*.json"))
        for where, expr in _queries(json.loads(path.read_text(encoding="utf-8")))
        if "canfar.metrics" in expr or "$metrics_job" in expr
    ]


EXPORTED_SERIES, EXPORTED_LABELS = _exported()
QUERIES = _dashboard_queries()


def test_dashboards_query_the_metrics_service() -> None:
    assert len(QUERIES) >= 20


@pytest.mark.parametrize(("where", "expr"), QUERIES, ids=[where for where, _ in QUERIES])
def test_dashboard_query_matches_exported_series(where: str, expr: str) -> None:
    for name, matchers in _SERIES.findall(expr):
        assert name in EXPORTED_SERIES, f"{where}: {name} is not exported"
        for label, operator, value in _MATCHER.findall(matchers):
            assert label in EXPORTED_LABELS, f"{where}: label {label!r} is not exported"
            allowed = _ALLOWED_VALUES.get(label)
            if allowed is None or operator not in {"=", "=~"}:
                continue
            for option in value.split("|") if operator == "=~" else [value]:
                assert option in allowed, f"{where}: {label}={option!r} is never recorded"
    if "canfar.metrics" in expr:
        for grouping in _GROUPING.findall(expr):
            for label in (part.strip().strip('"') for part in grouping.split(",")):
                assert label in EXPORTED_LABELS, (
                    f"{where}: grouping label {label!r} is not exported"
                )
    # A Metrics-scoped query may read only the service's own series and its OTLP target.
    for name in re.findall(r'\{"([^"]+)"', expr):
        assert name.startswith("canfar.metrics."), f"{where}: {name} is not a Metrics series"
    assert not re.search(r"\b(?:http|rpc)\.", expr), (
        f"{where}: HTTP instrumentation is not exported"
    )
