"""Building blocks for the CANFAR Science Platform Grafana dashboards.

Nothing here is CANFAR-specific beyond :data:`DIMS`; the rest is a
small, dependency-free layer over the Grafana dashboard JSON model (schemaVersion 41)
plus the PromQL idioms every session-scoped panel needs.

Run ``generate.py`` to emit dashboards. This module is never imported at runtime by
anything that ships to the cluster.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field

# --------------------------------------------------------------------------------------
# kube-state-metrics label mangling
# --------------------------------------------------------------------------------------

_INVALID_LABEL_CHARS = re.compile(r"[^a-zA-Z0-9_]")
_CAMEL_BOUNDARY = re.compile(r"([a-z0-9])([A-Z])")


def ksm_label(k8s_label_key: str) -> str:
    """Return the Prometheus series label kube-state-metrics exposes for a k8s label.

    kube-state-metrics v2 replaces every character outside ``[a-zA-Z0-9_]`` with an
    underscore, converts camelCase to snake_case, lowercases the result, and prefixes
    it with ``label_``. Deriving this instead of hand-writing it is what keeps the
    legacy and next-generation dashboards honest.

    >>> ksm_label("canfar-net-sessionType")
    'label_canfar_net_session_type'
    >>> ksm_label("canfar.net/app-id")
    'label_canfar_net_app_id'
    """
    sanitized = _INVALID_LABEL_CHARS.sub("_", k8s_label_key)
    snake = _CAMEL_BOUNDARY.sub(r"\1_\2", sanitized).lower()
    return f"label_{snake}"


# --------------------------------------------------------------------------------------
# Label schemes
# --------------------------------------------------------------------------------------


@dataclass(frozen=True)
class Dim:
    """One drill-down dimension: a Kubernetes pod label we can group and filter by.

    Dashboards are generated once with a ``__CANFAR_LABEL_*__`` placeholder wherever a
    Prometheus label name would go; the Helm chart substitutes the real name for the
    chosen label generation at install time. That keeps exactly one JSON file per
    dashboard in the repo instead of one per label generation.
    """

    #: Logical name, stable across generations, and also the Grafana variable name.
    name: str
    #: Human label shown in the variable picker and legends.
    display: str
    #: Kubernetes label key on the pre-realignment contract, if the dimension existed.
    legacy_key: str | None
    #: Kubernetes label key on the post-#1112 contract.
    next_key: str

    @property
    def promql(self) -> str:
        """Placeholder the Helm chart rewrites into a real Prometheus label name."""
        return f"__CANFAR_LABEL_{self.name.upper()}__"

    def resolved(self, generation: str) -> str:
        """Real Prometheus label name for one generation.

        Dimensions the legacy contract never had (project, community, flavor,
        accelerator) resolve to their post-#1112 name in both generations. On legacy
        data that label simply does not exist, so those panels read empty and their
        filters match only when left at ``All`` -- which is the honest rendering of
        "this attribution did not exist yet".
        """
        key = self.legacy_key if generation == "legacy" else self.next_key
        return ksm_label(key or self.next_key)


#: Every dimension a CANFAR session can be attributed by, in variable-picker order.
DIMS: list[Dim] = [
    Dim("community", "Community", None, "canfar.net/community"),
    Dim("project", "Project", None, "canfar.net/project"),
    Dim("user", "User", "canfar-net-userid", "canfar.net/username"),
    Dim("kind", "Session kind", "canfar-net-sessionType", "canfar.net/kind"),
    Dim("flavor", "Flavor", None, "canfar.net/flavor"),
    Dim("accelerator", "Accelerator", None, "canfar.net/accelerator"),
    Dim("name", "Session name", "canfar-net-sessionName", "canfar.net/name"),
    Dim("id", "Session ID", "canfar-net-sessionID", "canfar.net/id"),
    Dim("app_id", "Desktop app", "canfar-net-appID", "canfar.net/app-id"),
]

BY_NAME: dict[str, Dim] = {d.name: d for d in DIMS}

#: Dimensions offered as dashboard-wide filter pickers.
FILTER_DIMS: list[Dim] = [
    BY_NAME[n]
    for n in ("community", "project", "user", "kind", "flavor", "accelerator")
]

#: Label generations the Helm chart can substitute in.
GENERATIONS = ("legacy", "next")


def token_map(generation: str) -> dict[str, str]:
    """Placeholder -> real Prometheus label name, for one generation."""
    return {d.promql: d.resolved(generation) for d in DIMS}


def promql(name: str) -> str:
    return BY_NAME[name].promql


def display(name: str) -> str:
    return BY_NAME[name].display


#: Legacy job-level flavor markers, still read by the legacy queue/efficiency panels.
LEGACY_JOB_FIXED = ksm_label("opencadc.org/canfar-job-fixed")
LEGACY_JOB_FLEXIBLE = ksm_label("opencadc.org/canfar-job-flexible")


# --------------------------------------------------------------------------------------
# PromQL helpers
# --------------------------------------------------------------------------------------

DS = {"type": "prometheus", "uid": "${datasource}"}

#: Containers that represent real user workload (excludes the pause/sandbox container).
REAL_CONTAINER = 'container!="", container!="POD"'

#: Only count pods that are actually running, so Pending/Completed pods do not drag
#: efficiency numbers toward zero.
RUNNING_ONLY = 'and on (namespace, pod) (kube_pod_status_phase{phase="Running"} == 1)'


#: Label used to decide "is this a CANFAR session pod?".
#:
#: Deliberately the *user* label, not the session ID. kube-state-metrics only exposes
#: labels named in ``--metric-labels-allowlist``; the user label is the one every CANFAR
#: deployment already allowlists (it is what the existing dashboards attribute by),
#: whereas the session ID is frequently absent. Gating on the ID silently empties every
#: session panel on a cluster that does not allowlist it.
IDENTITY_DIM = "user"


def pod_selector(*, filtered: bool = True, extra: str = "") -> str:
    """Series selector for ``kube_pod_labels`` restricted to CANFAR session pods.

    ``filtered`` applies every dashboard filter variable the scheme supports, so a panel
    written once respects whatever pickers the scheme actually exposes.
    """
    parts = ['namespace=~"$namespace"', f'{promql(IDENTITY_DIM)}!=""']
    if filtered:
        parts += [f'{d.promql}=~"${d.name}"' for d in FILTER_DIMS]
    if extra:
        parts.append(extra)
    return "{" + ", ".join(parts) + "}"


def session_pods(*, filtered: bool = True, extra: str = "") -> str:
    return f"kube_pod_labels{pod_selector(filtered=filtered, extra=extra)}"


def _group_left(dims: list[str]) -> str:
    return ", ".join(dims)


def by_dim(
    inner: str,
    dims: list[str],
    *,
    running_only: bool = False,
    filtered: bool = True,
) -> str:
    """Attribute a per-pod expression to CANFAR dimensions.

    ``inner`` must already reduce to one series per ``(namespace, pod)``; the
    ``kube_pod_labels`` vector carries the constant 1, so the multiplication copies the
    label set through without changing the value.
    """
    promql_dims = [promql(d) for d in dims]
    guard = f"\n    {RUNNING_ONLY}" if running_only else ""
    return (
        f"sum by ({_group_left(promql_dims)}) (\n"
        f"  (\n"
        f"    {inner}{guard}\n"
        f"  )\n"
        f"  * on (namespace, pod) group_left ({_group_left(promql_dims)})\n"
        f"  {session_pods(filtered=filtered)}\n"
        f")"
    )


def by_pod(inner: str, *, extra: str = "") -> str:
    """Per-pod series for the selected sessions, keyed by pod name.

    Drilldown keys off ``pod`` rather than the session-ID label: the pod name is always
    present regardless of the kube-state-metrics allowlist, and it already encodes kind,
    user, and session ID (``skaha-notebook-msok-qrop75eb``), which makes for a strictly
    more informative legend than the bare ID.
    """
    return (
        f"sum by (pod) (\n"
        f"  (\n"
        f"    {inner}\n"
        f"  )\n"
        f"  and on (namespace, pod) {session_pods(extra=extra)}\n"
        f")"
    )


def pod_memory_used(extra: str = "") -> str:
    sel = f"namespace=~\"$namespace\", {REAL_CONTAINER}{extra}"
    return f"sum by (namespace, pod) (container_memory_working_set_bytes{{{sel}}})"


def pod_cpu_used(extra: str = "") -> str:
    sel = f"namespace=~\"$namespace\", {REAL_CONTAINER}{extra}"
    return (
        "sum by (namespace, pod) "
        f"(rate(container_cpu_usage_seconds_total{{{sel}}}[$__rate_interval]))"
    )


def pod_requests(resource: str) -> str:
    return (
        "sum by (namespace, pod) (kube_pod_container_resource_requests"
        f'{{namespace=~"$namespace", resource="{resource}"}})'
    )


def pod_limits(resource: str) -> str:
    return (
        "sum by (namespace, pod) (kube_pod_container_resource_limits"
        f'{{namespace=~"$namespace", resource="{resource}"}})'
    )


def pod_net(direction: str) -> str:
    metric = f"container_network_{direction}_bytes_total"
    return (
        f'sum by (namespace, pod) (rate({metric}{{namespace=~"$namespace"}}'
        "[$__rate_interval]))"
    )


def pod_fs(direction: str) -> str:
    metric = f"container_fs_{direction}_bytes_total"
    return (
        f'sum by (namespace, pod) (rate({metric}{{namespace=~"$namespace", '
        f'{REAL_CONTAINER}}}[$__rate_interval]))'
    )


def job_selector(*, filtered: bool = True) -> str:
    """Series selector for ``kube_job_labels`` restricted to CANFAR session jobs.

    Skaha launches every session as a Job, so the Job-level series are the only place
    lifecycle (created / completed / failed) and wall-clock duration can be read
    without guessing from a gauge.
    """
    parts = ['namespace=~"$namespace"', f'{promql(IDENTITY_DIM)}!=""']
    if filtered:
        parts += [f'{d.promql}=~"${d.name}"' for d in FILTER_DIMS]
    return "{" + ", ".join(parts) + "}"


def session_jobs(*, filtered: bool = True) -> str:
    return f"kube_job_labels{job_selector(filtered=filtered)}"


def jobs_matching(condition: str) -> str:
    """Count session Jobs satisfying ``condition`` (already a job_name-keyed vector)."""
    return (
        f"count(\n"
        f"  (\n"
        f"    {condition}\n"
        f"  )\n"
        f"  and on (namespace, job_name) {session_jobs()}\n"
        f")"
    )


#: Jobs whose completion timestamp falls inside the dashboard time range.
def jobs_completed_in_range() -> str:
    return jobs_matching('time() - kube_job_status_completion_time{namespace=~"$namespace"} '
        "< $__range_s",
    )


def jobs_created_in_range() -> str:
    return jobs_matching('time() - kube_job_created{namespace=~"$namespace"} < $__range_s'
    )


def jobs_failed_in_range() -> str:
    return jobs_matching('(kube_job_status_failed{namespace=~"$namespace"} > 0)\n'
        "    and on (namespace, job_name)\n"
        '    (time() - kube_job_created{namespace=~"$namespace"} < $__range_s)',
    )


def session_duration(*, dims: list[str] | None = None) -> str:
    """Wall-clock seconds between Job creation and completion, per finished session."""
    expr = (
        'kube_job_status_completion_time{namespace=~"$namespace"}\n'
        "  - on (namespace, job_name)\n"
        '  kube_job_created{namespace=~"$namespace"}'
    )
    if not dims:
        return f"(\n  {expr}\n)\nand on (namespace, job_name) {session_jobs()}"
    promql_dims = [promql(d) for d in dims]
    return (
        f"(\n  {expr}\n)\n"
        f"* on (namespace, job_name) group_left ({_group_left(promql_dims)})\n"
        f"{session_jobs()}"
    )


def total(inner: str, *, running_only: bool = False) -> str:
    """Cluster-wide total of a per-pod expression, restricted to filtered session pods."""
    guard = f"\n  {RUNNING_ONLY}" if running_only else ""
    return (
        f"sum(\n"
        f"  (\n"
        f"    {inner}\n"
        f"  ){guard}\n"
        f"  and on (namespace, pod) {session_pods()}\n"
        f")"
    )


def waste_hours(resource: str, *, divisor: str = "") -> str:
    """Requested-but-unused resource integrated over the dashboard range.

    ``avg_over_time`` of the instantaneous gap multiplied by the range length gives
    core-hours / GiB-hours without hard-coding a window the way the current hand-built
    dashboards do. ``clamp_min`` drops the (physically meaningless) negative gap you get
    when a burstable container exceeds its own request.
    """
    if resource == "cpu":
        used, req = pod_cpu_used(), pod_requests("cpu")
    else:
        used, req = pod_memory_used(), pod_requests("memory")
    gap = (
        f"clamp_min(\n"
        f"    {total(req, running_only=True)}\n"
        f"    -\n"
        f"    {total(used, running_only=True)},\n"
        f"    0\n"
        f"  )"
    )
    scale = f" / {divisor}" if divisor else ""
    return f"avg_over_time(\n  {gap}[$__range:5m]\n) * $__range_s / 3600{scale}"


# --------------------------------------------------------------------------------------
# Panel primitives
# --------------------------------------------------------------------------------------

PALETTE = {"mode": "palette-classic"}
GRYLRD = {"mode": "continuous-GrYlRd"}


def thresholds(*steps: tuple[str, float | None], mode: str = "absolute") -> dict:
    return {
        "mode": mode,
        "steps": [{"color": c, "value": v} for c, v in steps],
    }


#: Higher is worse (saturation, queue depth, error counts).
SATURATION = thresholds(("green", None), ("#EAB839", 0.75), ("red", 0.9))
#: Higher is better (efficiency); red at the bottom flags waste.
EFFICIENCY = thresholds(("red", None), ("orange", 0.25), ("green", 0.5))
NEUTRAL = thresholds(("text", None))
#: Any non-zero value is a problem (OOM kills, not-ready nodes).
ANY_IS_BAD = thresholds(("green", None), ("red", 1))


_OPENERS, _CLOSERS = "([{", ")]}"


def format_promql(expr: str) -> str:
    """Re-indent a PromQL expression by bracket depth.

    Composing queries from nested helpers gives correct but ragged indentation, and
    these expressions are read by humans in the Grafana query editor. Only leading
    whitespace is rewritten -- never the expression itself -- and bracket counting skips
    anything inside a double-quoted string, so label matchers containing brackets cannot
    throw the depth off.
    """
    lines = [ln.strip() for ln in expr.strip().splitlines()]
    out: list[str] = []
    depth = 0
    for line in lines:
        if not line:
            continue
        # A line that opens with closers belongs to the enclosing level.
        lead = 0
        for ch in line:
            if ch in _CLOSERS:
                lead += 1
            elif not ch.isspace():
                break
        out.append("  " * max(depth - lead, 0) + line)
        depth += _net_depth(line)
    return "\n".join(out)


def _net_depth(line: str) -> int:
    depth, in_string, escaped = 0, False, False
    for ch in line:
        if escaped:
            escaped = False
            continue
        if ch == "\\":
            escaped = in_string
            continue
        if ch == '"':
            in_string = not in_string
            continue
        if in_string:
            continue
        if ch in _OPENERS:
            depth += 1
        elif ch in _CLOSERS:
            depth -= 1
    return depth


def target(expr: str, legend: str = "__auto", ref: str = "A", **kw) -> dict:
    t = {
        "refId": ref,
        "expr": format_promql(expr),
        "legendFormat": legend,
        "editorMode": "code",
        "range": True,
        "datasource": DS,
    }
    if kw.pop("instant", False):
        t["instant"], t["range"] = True, False
    if kw.pop("table", False):
        t["format"] = "table"
    t.update(kw)
    return t


def targets(*exprs: dict) -> list[dict]:
    """Assign sequential refIds so callers never have to."""
    letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    out = []
    for i, t in enumerate(exprs):
        t = dict(t)
        t["refId"] = letters[i]
        out.append(t)
    return out


def _panel(kind: str, title: str, tgts: list[dict], desc: str, **kw) -> dict:
    return {
        "type": kind,
        "title": title,
        "description": desc,
        "datasource": DS,
        "targets": tgts,
        "fieldConfig": {"defaults": {}, "overrides": []},
        "options": {},
        **kw,
    }


def timeseries(
    title: str,
    tgts: list[dict],
    *,
    unit: str = "short",
    desc: str = "",
    legend: str = "list",
    calcs: list[str] | None = None,
    stack: bool = False,
    fill: int = 8,
    color: dict | None = None,
    thr: dict | None = None,
    minimum: float | None = None,
    maximum: float | None = None,
    decimals: int | None = None,
    axis_label: str = "",
    placement: str = "bottom",
    overrides: list[dict] | None = None,
    draw: str = "line",
    width: int = 2,
) -> dict:
    p = _panel("timeseries", title, tgts, desc)
    p["options"] = {
        "legend": {
            "showLegend": legend != "hidden",
            "displayMode": "table" if legend == "table" else "list",
            "placement": placement,
            "calcs": calcs or [],
            **({"sortBy": "Mean", "sortDesc": True} if calcs and "mean" in calcs else {}),
        },
        "tooltip": {"mode": "multi", "sort": "desc", "hideZeros": False},
    }
    d = p["fieldConfig"]["defaults"]
    d["unit"] = unit
    d["color"] = color or PALETTE
    d["thresholds"] = thr or NEUTRAL
    if minimum is not None:
        d["min"] = minimum
    if maximum is not None:
        d["max"] = maximum
    if decimals is not None:
        d["decimals"] = decimals
    d["custom"] = {
        "drawStyle": draw,
        "lineInterpolation": "smooth",
        "lineWidth": width,
        "fillOpacity": fill,
        "gradientMode": "opacity",
        "showPoints": "never",
        "pointSize": 5,
        "spanNulls": False,
        "insertNulls": False,
        "axisLabel": axis_label,
        "axisPlacement": "auto",
        "axisColorMode": "text",
        "axisBorderShow": False,
        "axisCenteredZero": False,
        "barAlignment": 0,
        "barWidthFactor": 0.6,
        "scaleDistribution": {"type": "linear"},
        "stacking": {"group": "A", "mode": "normal" if stack else "none"},
        "hideFrom": {"legend": False, "tooltip": False, "viz": False},
        "thresholdsStyle": {"mode": "off"},
    }
    if overrides:
        p["fieldConfig"]["overrides"] = overrides
    return p


def stat(
    title: str,
    tgts: list[dict],
    *,
    unit: str = "short",
    desc: str = "",
    thr: dict | None = None,
    color_mode: str = "value",
    graph: str = "area",
    text_mode: str = "auto",
    decimals: int | None = None,
    calc: str = "lastNotNull",
    no_value: str = "0",
) -> dict:
    p = _panel("stat", title, tgts, desc)
    p["options"] = {
        "colorMode": color_mode,
        "graphMode": graph,
        "justifyMode": "auto",
        "orientation": "auto",
        "textMode": text_mode,
        "wideLayout": True,
        "showPercentChange": False,
        "percentChangeColorMode": "standard",
        "reduceOptions": {"calcs": [calc], "fields": "", "values": False},
    }
    d = p["fieldConfig"]["defaults"]
    d["unit"] = unit
    d["thresholds"] = thr or NEUTRAL
    d["color"] = {"mode": "thresholds"}
    d["noValue"] = no_value
    if decimals is not None:
        d["decimals"] = decimals
    return p


def gauge(
    title: str,
    tgts: list[dict],
    *,
    unit: str = "percentunit",
    desc: str = "",
    thr: dict | None = None,
    minimum: float = 0,
    maximum: float = 1,
    decimals: int = 0,
) -> dict:
    p = _panel("gauge", title, tgts, desc)
    p["options"] = {
        "reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False},
        "showThresholdLabels": False,
        "showThresholdMarkers": True,
        "minVizHeight": 75,
        "minVizWidth": 75,
        "sizing": "auto",
        "orientation": "auto",
    }
    d = p["fieldConfig"]["defaults"]
    d.update(
        {
            "unit": unit,
            "min": minimum,
            "max": maximum,
            "decimals": decimals,
            "thresholds": thr or SATURATION,
            "color": {"mode": "thresholds"},
        }
    )
    return p


def bargauge(
    title: str,
    tgts: list[dict],
    *,
    unit: str = "short",
    desc: str = "",
    thr: dict | None = None,
    minimum: float | None = 0,
    maximum: float | None = None,
    orientation: str = "horizontal",
    display: str = "lcd",
    color: dict | None = None,
    decimals: int | None = None,
    calc: str = "lastNotNull",
) -> dict:
    p = _panel("bargauge", title, tgts, desc)
    p["options"] = {
        "displayMode": display,
        "orientation": orientation,
        "reduceOptions": {"calcs": [calc], "fields": "", "values": False},
        "showUnfilled": True,
        "valueMode": "color",
        "namePlacement": "auto",
        "minVizHeight": 16,
        "minVizWidth": 8,
        "maxVizHeight": 300,
        "sizing": "auto",
        "legend": {"showLegend": False, "displayMode": "list", "placement": "bottom"},
    }
    d = p["fieldConfig"]["defaults"]
    d["unit"] = unit
    d["thresholds"] = thr or NEUTRAL
    d["color"] = color or {"mode": "thresholds"}
    if minimum is not None:
        d["min"] = minimum
    if maximum is not None:
        d["max"] = maximum
    if decimals is not None:
        d["decimals"] = decimals
    return p


def table(
    title: str,
    tgts: list[dict],
    *,
    desc: str = "",
    transformations: list[dict] | None = None,
    overrides: list[dict] | None = None,
    unit: str = "short",
    sort_by: list[dict] | None = None,
    paginate: bool = True,
    footer: list[str] | None = None,
) -> dict:
    p = _panel("table", title, tgts, desc)
    p["options"] = {
        "showHeader": True,
        "cellHeight": "sm",
        "enablePagination": paginate,
        "sortBy": sort_by or [],
        "footer": {
            "show": bool(footer),
            "reducer": footer or [],
            "countRows": False,
            "fields": "",
        },
    }
    p["transformations"] = transformations or []
    d = p["fieldConfig"]["defaults"]
    d["unit"] = unit
    d["thresholds"] = NEUTRAL
    d["color"] = {"mode": "thresholds"}
    d["custom"] = {
        "align": "auto",
        "cellOptions": {"type": "auto"},
        "filterable": True,
        "inspect": False,
    }
    p["fieldConfig"]["overrides"] = overrides or []
    return p


def heatmap(
    title: str,
    tgts: list[dict],
    *,
    desc: str = "",
    y_unit: str = "short",
    calculate: bool = False,
    scheme: str = "Turbo",
) -> dict:
    p = _panel("heatmap", title, tgts, desc)
    p["options"] = {
        "calculate": calculate,
        "cellGap": 1,
        "color": {
            "mode": "scheme",
            "scheme": scheme,
            "steps": 64,
            "reverse": False,
            "fill": "dark-orange",
            "exponent": 0.5,
            "scale": "exponential",
        },
        "exemplars": {"color": "rgba(255,0,255,0.7)"},
        "filterValues": {"le": 1e-9},
        "legend": {"show": True},
        "rowsFrame": {"layout": "auto"},
        "tooltip": {"mode": "single", "showColorScale": True, "yHistogram": False},
        "yAxis": {"axisPlacement": "left", "reverse": False, "unit": y_unit},
    }
    p["fieldConfig"]["defaults"]["custom"] = {
        "hideFrom": {"legend": False, "tooltip": False, "viz": False},
        "scaleDistribution": {"type": "linear"},
    }
    return p


def piechart(
    title: str, tgts: list[dict], *, unit: str = "short", desc: str = ""
) -> dict:
    p = _panel("piechart", title, tgts, desc)
    p["options"] = {
        "pieType": "donut",
        "displayLabels": ["percent"],
        "reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False},
        "legend": {
            "showLegend": True,
            "displayMode": "table",
            "placement": "right",
            "values": ["value"],
        },
        "tooltip": {"mode": "single", "sort": "desc"},
    }
    p["fieldConfig"]["defaults"]["unit"] = unit
    p["fieldConfig"]["defaults"]["color"] = PALETTE
    return p


# --------------------------------------------------------------------------------------
# Transformations & overrides
# --------------------------------------------------------------------------------------


def override(name: str, *props: tuple[str, object], regex: bool = False) -> dict:
    return {
        "matcher": {"id": "byRegexp" if regex else "byName", "options": name},
        "properties": [{"id": k, "value": v} for k, v in props],
    }


def gradient_cell(color: dict | None = None) -> list[tuple[str, object]]:
    return [
        ("custom.cellOptions", {"type": "color-background", "mode": "gradient"}),
        ("color", color or GRYLRD),
    ]


def organize(rename: dict[str, str], exclude: list[str], order: list[str]) -> dict:
    return {
        "id": "organize",
        "options": {
            "renameByName": rename,
            "excludeByName": {k: True for k in exclude},
            "indexByName": {k: i for i, k in enumerate(order)},
        },
    }


# --------------------------------------------------------------------------------------
# Layout
# --------------------------------------------------------------------------------------


@dataclass
class Layout:
    """Flowing 24-column placer, so panels never carry hand-computed ``gridPos``."""

    panels: list[dict] = field(default_factory=list)
    _x: int = 0
    _y: int = 0
    _row_h: int = 0
    _next_id: int = 1

    def _place(self, panel: dict, w: int, h: int) -> dict:
        if self._x + w > 24:
            self._newline()
        panel["gridPos"] = {"x": self._x, "y": self._y, "w": w, "h": h}
        panel["id"] = self._next_id
        self._next_id += 1
        self._x += w
        self._row_h = max(self._row_h, h)
        self.panels.append(panel)
        return panel

    def _newline(self) -> None:
        self._y += self._row_h
        self._x = 0
        self._row_h = 0

    def add(self, panel: dict | None, w: int, h: int) -> "Layout":
        """Place a panel. ``None`` is ignored so callers can inline scheme conditionals."""
        if panel is not None:
            self._place(panel, w, h)
        return self

    def row(self, title: str, *, collapsed: bool = False) -> "Layout":
        if self._x or self._row_h:
            self._newline()
        self._place(
            {
                "type": "row",
                "title": title,
                "collapsed": collapsed,
                "panels": [],
                "datasource": DS,
            },
            24,
            1,
        )
        self._newline()
        return self

    def build(self) -> list[dict]:
        return self.panels


# --------------------------------------------------------------------------------------
# Variables
# --------------------------------------------------------------------------------------


#: uid of the Prometheus datasource to preselect.
#:
#: A datasource variable with an empty ``current`` does not reliably resolve on a
#: *provisioned* dashboard the way it does on one saved from the UI -- ``${datasource}``
#: interpolates to nothing and every panel on every dashboard silently returns no data.
#: Seeding ``current`` is what makes a sidecar-delivered dashboard work on first load.
DEFAULT_DATASOURCE_UID = "prometheus"
DEFAULT_DATASOURCE_NAME = "Prometheus"


def var_datasource() -> dict:
    return {
        "name": "datasource",
        "type": "datasource",
        "label": "Data source",
        "query": "prometheus",
        "pluginId": "prometheus",
        "refresh": 1,
        "current": {
            "text": DEFAULT_DATASOURCE_NAME,
            "value": DEFAULT_DATASOURCE_UID,
        },
        "hide": 0,
        "includeAll": False,
        "multi": False,
        "options": [],
        "regex": "",
        "skipUrlSync": False,
    }


def var_query(
    name: str,
    query: str,
    *,
    label: str = "",
    desc: str = "",
    multi: bool = True,
    include_all: bool = True,
    all_value: str = ".*",
    regex: str = "",
    current: dict | None = None,
    sort: int = 1,
    refresh: int = 2,
    hide: int = 0,
) -> dict:
    return {
        "name": name,
        "type": "query",
        "label": label or name,
        "description": desc,
        "datasource": DS,
        "definition": query,
        "query": {"qryType": 1, "query": query, "refId": f"{name}-variable-query"},
        "refresh": refresh,
        "regex": regex,
        "sort": sort,
        "multi": multi,
        "includeAll": include_all,
        "allValue": all_value if include_all else None,
        "current": current or {"text": ["All"], "value": ["$__all"]},
        "options": [],
        "hide": hide,
        "skipUrlSync": False,
        "allowCustomValue": False,
    }


def var_custom(name: str, values: str, *, label: str = "", current: str = "") -> dict:
    first = current or values.split(",")[0].strip()
    return {
        "name": name,
        "type": "custom",
        "label": label or name,
        "query": values,
        "current": {"text": first, "value": first},
        "options": [
            {
                "text": v.strip(),
                "value": v.strip(),
                "selected": v.strip() == first,
            }
            for v in values.split(",")
        ],
        "multi": False,
        "includeAll": False,
        "hide": 0,
        "skipUrlSync": False,
    }


def var_namespace() -> dict:
    return var_query(
        "namespace",
        "label_values(kube_pod_info, namespace)",
        label="Namespace",
        desc="CANFAR session workload namespace(s).",
        regex=".*workload.*",
        current={"text": ["All"], "value": ["$__all"]},
    )


def filter_vars() -> list[dict]:
    """One picker per filterable dimension."""
    out = []
    for d in FILTER_DIMS:
        out.append(
            var_query(
                d.name,
                f"label_values(kube_pod_labels{{namespace=~\"$namespace\", "
                f'{d.promql}!=""}}, {d.promql})',
                label=d.display,
                desc=(
                    f"CANFAR session {d.display.lower()}. Resolves to "
                    f"`{d.legacy_key or d.next_key}` or `{d.next_key}` depending on "
                    "the installed label generation."
                ),
            )
        )
    return out


# --------------------------------------------------------------------------------------
# Dashboard assembly
# --------------------------------------------------------------------------------------

def nav_links() -> list[dict]:
    """A dashboard-level dropdown that keeps the current filters when hopping around.

    Driven by the shared ``canfar`` tag rather than a hard-coded list, so a new
    dashboard joins the nav simply by being generated.
    """
    return [
        {
            "type": "dashboards",
            "title": "CANFAR",
            "tags": ["canfar"],
            "asDropdown": True,
            "includeVars": True,
            "keepTime": True,
            "targetBlank": False,
            "icon": "external link",
            "tooltip": "Other CANFAR Science Platform dashboards",
            "url": "",
        }
    ]


def dashboard(
    *,
    uid: str,
    title: str,
    description: str,
    panels: list[dict],
    variables: list[dict],
    tags: list[str],
    refresh: str = "1m",
    time_from: str = "now-6h",
) -> dict:
    return {
        "uid": uid,
        "title": title,
        "description": description,
        "tags": sorted(set(tags + ["canfar"])),
        "editable": True,
        "graphTooltip": 1,
        "schemaVersion": 41,
        "version": 1,
        "refresh": refresh,
        "timezone": "browser",
        "fiscalYearStartMonth": 0,
        "preload": False,
        "time": {"from": time_from, "to": "now"},
        "timepicker": {},
        "templating": {"list": variables},
        "annotations": {"list": [_builtin_annotation()]},
        "links": nav_links(),
        "panels": panels,
    }


def _builtin_annotation() -> dict:
    return {
        "builtIn": 1,
        "datasource": {"type": "grafana", "uid": "-- Grafana --"},
        "enable": True,
        "hide": True,
        "iconColor": "rgba(0, 211, 255, 1)",
        "name": "Annotations & Alerts",
        "type": "dashboard",
    }
