#!/usr/bin/env python3
"""Generate the CANFAR Science Platform Grafana dashboards.

    python3 generate.py            # write dist/
    python3 generate.py --check    # fail if dist/ is stale (CI)

Eight dashboards, emitted once. Wherever a Prometheus label name would appear the
output carries a ``__CANFAR_LABEL_*__`` placeholder; the Helm chart substitutes the
names for the chosen label generation (``legacy`` for the pre-realignment
``canfar-net-*`` keys, ``next`` for the ``canfar.net/*`` keys from
opencadc/science-platform#1112) at install time. Installing both generations renders
the same eight files twice with different substitutions and uid suffixes.

See README.md for the design rationale and the kube-state-metrics prerequisite.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import sys

from lib import (
    GENERATIONS,
    IDENTITY_DIM,
    token_map,
    display,
    promql,
    ANY_IS_BAD,
    EFFICIENCY,
    GRYLRD,
    LEGACY_JOB_FIXED,
    LEGACY_JOB_FLEXIBLE,
    NEUTRAL,
    SATURATION,
    DIMS,
    FILTER_DIMS,
    Layout,
    bargauge,
    by_dim,
    by_pod,
    dashboard,
    gauge,
    gradient_cell,
    heatmap,
    jobs_completed_in_range,
    jobs_created_in_range,
    jobs_failed_in_range,
    override,
    piechart,
    pod_cpu_used,
    pod_fs,
    pod_limits,
    pod_memory_used,
    pod_net,
    pod_requests,
    filter_vars,
    session_duration,
    session_jobs,
    session_pods,
    stat,
    table,
    target,
    targets,
    thresholds,
    timeseries,
    total,
    var_custom,
    var_datasource,
    var_namespace,
    var_query,
    waste_hours,
)

DIST = pathlib.Path(__file__).parent / "dist"

CORE_HOURS = "suffix:core·h"
GIB_HOURS = "suffix:GiB·h"
CORES = "suffix:cores"

# Drilldown scopes by pod name, which never depends on the kube-state-metrics
# label allowlist the way the session-ID label does.
POD_FILTER = 'pod=~"$pod"'

# Only pods that are actually running should count toward efficiency.
RUNNING = 'and on (namespace, pod) (kube_pod_status_phase{phase="Running"} == 1)'


# ======================================================================================
# Reusable composite panels
# ======================================================================================


def _instant_table(
    dim_label: str,
    cols: list[tuple[str, str]],
    title: str,
    desc: str,
    *,
    dim_display: str,
    overrides: list[dict],
    sort_col: str | None = None,
    unit: str = "short",
) -> dict:
    """Join several instant queries into one row-per-``dim_label`` table.

    Each query contributes a ``Value #<ref>`` column; ``joinByField`` stitches them on
    the shared label and ``organize`` renames them to the human column headings.
    """
    tgts = targets(
        *[target(expr, ref="A", instant=True, table=True) for _, expr in cols]
    )
    letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    rename = {dim_label: dim_display}
    rename.update({f"Value #{letters[i]}": name for i, (name, _) in enumerate(cols)})
    # Instant table frames each carry a Time column; after the join they are suffixed.
    exclude = ["Time"] + [f"Time {i}" for i in range(1, len(cols) + 1)]
    return table(
        title,
        tgts,
        desc=desc,
        unit=unit,
        transformations=[
            {"id": "joinByField", "options": {"byField": dim_label, "mode": "outer"}},
            {
                "id": "organize",
                "options": {
                    "renameByName": rename,
                    "excludeByName": {k: True for k in exclude},
                    "indexByName": {
                        k: i
                        for i, k in enumerate(
                            [dim_label]
                            + [f"Value #{letters[i]}" for i in range(len(cols))]
                        )
                    },
                },
            },
        ],
        overrides=overrides,
        sort_by=[{"displayName": sort_col, "desc": True}] if sort_col else None,
        footer=["sum"] if sort_col else None,
    )


def _usage_columns(dim: str) -> list[tuple[str, str]]:
    """Sessions / requested / used / efficiency, attributed to one dimension."""
    d = [dim]
    cpu_req = by_dim(pod_requests("cpu"), d, running_only=True)
    cpu_use = by_dim(pod_cpu_used(), d, running_only=True)
    ram_req = by_dim(pod_requests("memory"), d, running_only=True)
    ram_use = by_dim(pod_memory_used(), d, running_only=True)
    lbl = promql(dim)
    return [
        (
            "Sessions",
            f"count by ({lbl}) (\n  {session_pods()}\n  {RUNNING}\n)",
        ),
        ("CPU requested", cpu_req),
        ("CPU used", cpu_use),
        ("CPU efficiency", f"(\n{cpu_use}\n)\n/\n(\n{cpu_req}\n)"),
        ("RAM requested", ram_req),
        ("RAM used", ram_use),
        ("RAM efficiency", f"(\n{ram_use}\n)\n/\n(\n{ram_req}\n)"),
    ]


def _usage_overrides() -> list[dict]:
    return [
        override("CPU requested", ("unit", CORES), ("decimals", 2)),
        override("CPU used", ("unit", CORES), ("decimals", 2)),
        override("RAM requested", ("unit", "bytes")),
        override("RAM used", ("unit", "bytes")),
        override(
            "CPU efficiency",
            ("unit", "percentunit"),
            ("decimals", 0),
            ("max", 1),
            ("min", 0),
            ("thresholds", EFFICIENCY),
            *gradient_cell({"mode": "continuous-RdYlGr"}),
        ),
        override(
            "RAM efficiency",
            ("unit", "percentunit"),
            ("decimals", 0),
            ("max", 1),
            ("min", 0),
            ("thresholds", EFFICIENCY),
            *gradient_cell({"mode": "continuous-RdYlGr"}),
        ),
    ]


def _usage_table(dim: str, title: str, desc: str) -> dict:
    return _instant_table(
        promql(dim),
        _usage_columns(dim),
        title,
        desc,
        dim_display=display(dim),
        overrides=_usage_overrides(),
        sort_col="CPU requested",
    )


def _efficiency_ts(resource: str, dim: str) -> dict:
    used = pod_cpu_used() if resource == "cpu" else pod_memory_used()
    req = pod_requests("cpu" if resource == "cpu" else "memory")
    name = "CPU" if resource == "cpu" else "RAM"
    return timeseries(
        f"{name} efficiency by {display(dim).lower()}",
        targets(
            target(
                f"(\n{by_dim(used, [dim], running_only=True)}\n)\n"
                f"/\n(\n{by_dim(req, [dim], running_only=True)}\n)",
                legend=f"{{{{{promql(dim)}}}}}",
            )
        ),
        unit="percentunit",
        desc=(
            f"Working-set {name} divided by requested {name}, for running sessions only. "
            "Sustained values under 25% mean the platform is holding capacity that "
            "nobody is using."
        ),
        legend="table",
        calcs=["mean", "max", "lastNotNull"],
        placement="right",
        minimum=0,
        thr=EFFICIENCY,
        fill=4,
    )


def _capacity_ts(resource: str) -> dict:
    """Cluster allocatable vs. committed vs. actually burned, on one axis."""
    if resource == "cpu":
        alloc = 'sum(kube_node_status_allocatable{resource="cpu"})'
        req = 'sum(kube_pod_container_resource_requests{resource="cpu"})'
        lim = 'sum(kube_pod_container_resource_limits{resource="cpu"})'
        used = "sum(rate(container_cpu_usage_seconds_total{container!=\"\"}[$__rate_interval]))"
        unit, title = CORES, "Cluster CPU: allocatable vs requested vs used"
    else:
        alloc = 'sum(kube_node_status_allocatable{resource="memory"})'
        req = 'sum(kube_pod_container_resource_requests{resource="memory"})'
        lim = 'sum(kube_pod_container_resource_limits{resource="memory"})'
        used = 'sum(container_memory_working_set_bytes{container!=""})'
        unit, title = "bytes", "Cluster memory: allocatable vs requested vs used"
    return timeseries(
        title,
        targets(
            target(alloc, legend="Allocatable"),
            target(req, legend="Requested"),
            target(lim, legend="Limits"),
            target(used, legend="Used"),
        ),
        unit=unit,
        desc=(
            "Allocatable is the ceiling. Requested is what the scheduler has already "
            "promised away. Used is real consumption. A wide Requested-to-Used gap is "
            "capacity you paid for and nobody burned."
        ),
        legend="table",
        calcs=["lastNotNull", "max"],
        placement="bottom",
        fill=4,
        overrides=[
            override(
                "Allocatable",
                ("color", {"mode": "fixed", "fixedColor": "text"}),
                ("custom.fillOpacity", 0),
                ("custom.lineStyle", {"fill": "dash", "dash": [10, 10]}),
            ),
            override(
                "Limits",
                ("color", {"mode": "fixed", "fixedColor": "purple"}),
                ("custom.fillOpacity", 0),
            ),
            override("Used", ("color", {"mode": "fixed", "fixedColor": "green"})),
            override("Requested", ("color", {"mode": "fixed", "fixedColor": "blue"})),
        ],
    )


def _termination_ts() -> dict:
    def by_reason(reason: str) -> str:
        return (
            f"sum(\n"
            f"  sum by (namespace, pod) (\n"
            f'    kube_pod_container_status_terminated_reason{{namespace=~"$namespace", '
            f'reason="{reason}"}}\n'
            f"  )\n"
            f"  and on (namespace, pod) {session_pods()}\n"
            f")"
        )

    return timeseries(
        "Session container terminations by reason",
        targets(
            target(by_reason("OOMKilled"), legend="OOMKilled"),
            target(by_reason("Error"), legend="Error"),
            target(by_reason("Completed"), legend="Completed"),
        ),
        unit="short",
        desc=(
            "OOMKilled means the user under-requested memory and lost work. Error means "
            "the payload itself failed. Completed is the healthy exit path."
        ),
        legend="table",
        calcs=["lastNotNull", "max"],
        stack=True,
        overrides=[
            override("OOMKilled", ("color", {"mode": "fixed", "fixedColor": "red"})),
            override("Error", ("color", {"mode": "fixed", "fixedColor": "orange"})),
            override("Completed", ("color", {"mode": "fixed", "fixedColor": "green"})),
        ],
    )


def _active_sessions_expr() -> str:
    return f"count(\n  {session_pods()}\n  {RUNNING}\n)"


def _active_users_expr() -> str:
    lbl = promql("user")
    return (
        f"count(\n"
        f"  count by ({lbl}) (\n"
        f"    {session_pods()}\n"
        f"    {RUNNING}\n"
        f"  )\n"
        f")"
    )


def _pulse_stats() -> list[dict]:
    return [
        stat(
            "Active sessions",
            targets(target(_active_sessions_expr())),
            desc="Running CANFAR session pods matching the current filters.",
            thr=NEUTRAL,
            color_mode="none",
        ),
        stat(
            "Active users",
            targets(target(_active_users_expr())),
            desc="Distinct users with at least one running session.",
            thr=NEUTRAL,
            color_mode="none",
        ),
        stat(
            "Pending sessions",
            targets(
                target(
                    f"count(\n"
                    f"  {session_pods()}\n"
                    f"  and on (namespace, pod) "
                    f'(kube_pod_status_phase{{phase="Pending"}} == 1)\n'
                    f")"
                )
            ),
            desc="Sessions accepted but not yet running: unscheduled, queued, or "
            "pulling an image.",
            thr=thresholds(("green", None), ("#EAB839", 5), ("red", 20)),
        ),
        stat(
            "Queue backlog",
            targets(target("sum(kueue_pending_workloads)")),
            desc="Workloads Kueue is holding before admission, across all ClusterQueues.",
            thr=thresholds(("green", None), ("#EAB839", 10), ("red", 50)),
        ),
        stat(
            "CPU committed",
            targets(
                target(
                    'sum(kube_pod_container_resource_requests{resource="cpu"})\n'
                    '/ sum(kube_node_status_allocatable{resource="cpu"})'
                )
            ),
            unit="percentunit",
            decimals=0,
            desc="Fraction of allocatable cores already promised to pod requests.",
            thr=SATURATION,
            graph="none",
        ),
        stat(
            "RAM committed",
            targets(
                target(
                    'sum(kube_pod_container_resource_requests{resource="memory"})\n'
                    '/ sum(kube_node_status_allocatable{resource="memory"})'
                )
            ),
            unit="percentunit",
            decimals=0,
            desc="Fraction of allocatable memory already promised to pod requests.",
            thr=SATURATION,
            graph="none",
        ),
        stat(
            "Nodes not ready",
            targets(
                target(
                    'sum(kube_node_status_condition{condition="Ready", status="true"} == 0)\n'
                    "or vector(0)"
                )
            ),
            desc="Nodes failing the Ready condition.",
            thr=ANY_IS_BAD,
            graph="none",
        ),
        stat(
            "OOM kills in range",
            targets(
                target(
                    f"count(\n"
                    f"  sum by (namespace, pod) (\n"
                    f"    kube_pod_container_status_last_terminated_reason"
                    f'{{namespace=~"$namespace", reason="OOMKilled"}}\n'
                    f"  ) > 0\n"
                    f"  and on (namespace, pod) {session_pods()}\n"
                    f")\n"
                    f"or vector(0)"
                )
            ),
            desc="Session pods whose last container termination was an OOM kill: "
            "users who under-requested memory and lost their work.",
            thr=ANY_IS_BAD,
        ),
    ]


# ======================================================================================
# Per-scheme dashboards
# ======================================================================================


def board_overview() -> dict:
    lay = Layout()
    lay.row("Platform pulse")
    for s in _pulse_stats():
        lay.add(s, 3, 4)

    lay.row("Demand")
    lay.add(
        timeseries(
            "Active sessions by kind",
            targets(
                target(
                    f"count by ({promql('kind')}) (\n"
                    f"  {session_pods()}\n  {RUNNING}\n)",
                    legend=f"{{{{{promql('kind')}}}}}",
                )
            ),
            desc="Running sessions split by session type.",
            legend="table",
            calcs=["lastNotNull", "mean", "max"],
            placement="right",
            stack=True,
            fill=25,
        ),
        12,
        9,
    )
    lay.add(
        timeseries(
            "Session CPU: requested vs used",
            targets(
                target(
                    total(pod_requests("cpu"), running_only=True),
                    legend="Requested",
                ),
                target(
                    total(pod_cpu_used(), running_only=True), legend="Used"
                ),
            ),
            unit=CORES,
            desc="Aggregate CPU held by running sessions against what they actually burn.",
            legend="table",
            calcs=["lastNotNull", "mean"],
            overrides=[
                override("Requested", ("color", {"mode": "fixed", "fixedColor": "blue"})),
                override("Used", ("color", {"mode": "fixed", "fixedColor": "green"})),
            ],
        ),
        12,
        9,
    )

    lay.add(
        timeseries(
            f"Active sessions by {display('project').lower()}",
            targets(
                target(
                    f"topk(15,\n"
                    f"  count by ({promql('project')}) (\n"
                    f"    {session_pods()}\n    {RUNNING}\n"
                    f"  )\n)",
                    legend=f"{{{{{promql('project')}}}}}",
                )
            ),
            desc=(
                "Top 15 projects by running session count. Reads empty on the "
                "legacy label generation, which had no project dimension."
            ),
            legend="table",
            calcs=["lastNotNull", "max"],
            placement="right",
            stack=True,
            fill=25,
        ),
        12,
        9,
    )
    lay.add(
        timeseries(
            "Session memory: requested vs used",
            targets(
                target(
                    total(pod_requests("memory"), running_only=True),
                    legend="Requested",
                ),
                target(
                    total(pod_memory_used(), running_only=True), legend="Used"
                ),
            ),
            unit="bytes",
            desc="Aggregate memory held by running sessions against working-set usage.",
            legend="table",
            calcs=["lastNotNull", "mean"],
            overrides=[
                override("Requested", ("color", {"mode": "fixed", "fixedColor": "blue"})),
                override("Used", ("color", {"mode": "fixed", "fixedColor": "green"})),
            ],
        ),
        12,
        9,
    )

    lay.row("Efficiency at a glance")
    lay.add(
        gauge(
            "CPU efficiency",
            targets(
                target(
                    f"(\n{total(pod_cpu_used(), running_only=True)}\n)\n/\n"
                    f"(\n{total(pod_requests('cpu'), running_only=True)}\n)"
                )
            ),
            desc="Used ÷ requested CPU across all running sessions.",
            thr=EFFICIENCY,
        ),
        4,
        8,
    )
    lay.add(
        gauge(
            "RAM efficiency",
            targets(
                target(
                    f"(\n{total(pod_memory_used(), running_only=True)}\n)\n/\n"
                    f"(\n{total(pod_requests('memory'), running_only=True)}\n)"
                )
            ),
            desc="Working set ÷ requested memory across all running sessions.",
            thr=EFFICIENCY,
        ),
        4,
        8,
    )
    lay.add(_efficiency_ts("cpu", "kind"), 8, 8)
    lay.add(_efficiency_ts("memory", "kind"), 8, 8)

    lay.row("Reliability")
    lay.add(_termination_ts(), 12, 8)
    lay.add(
        timeseries(
            "Session container restarts",
            targets(
                target(
                    f"sum by ({promql('kind')}) (\n"
                    f"  (\n"
                    f"    sum by (namespace, pod) (\n"
                    f"      increase(kube_pod_container_status_restarts_total"
                    f'{{namespace=~"$namespace"}}[$__rate_interval])\n'
                    f"    )\n"
                    f"  )\n"
                    f"  * on (namespace, pod) group_left ({promql('kind')})\n"
                    f"  {session_pods()}\n"
                    f")",
                    legend=f"{{{{{promql('kind')}}}}}",
                )
            ),
            desc="Restarts inside session containers. Interactive sessions should "
            "essentially never restart.",
            legend="table",
            calcs=["max"],
            thr=ANY_IS_BAD,
        ),
        12,
        8,
    )

    return dashboard(
        uid="canfar-overview__CANFAR_UID_SUFFIX__",
        title="CANFAR · Platform Overview__CANFAR_TITLE_SUFFIX__",
        description=(
            "Single-screen health and demand picture for the CANFAR Science Platform."
        ),
        panels=lay.build(),
        variables=[var_datasource(), var_namespace(), *filter_vars()],
        tags=["overview"],
        refresh="1m",
    )


def board_sessions() -> dict:
    lay = Layout()
    lay.row("Activity in the selected range")
    lay.add(
        stat(
            "Active sessions",
            targets(target(_active_sessions_expr())),
            desc="Running now.",
            color_mode="none",
        ),
        4,
        4,
    )
    lay.add(
        stat(
            "Active users",
            targets(target(_active_users_expr())),
            desc="Distinct users running a session now.",
            color_mode="none",
        ),
        4,
        4,
    )
    lay.add(
        stat(
            "Sessions started",
            targets(target(jobs_created_in_range())),
            desc="Session Jobs created inside the dashboard time range.",
            color_mode="none",
        ),
        4,
        4,
    )
    lay.add(
        stat(
            "Sessions completed",
            targets(target(jobs_completed_in_range())),
            desc="Session Jobs that reached a completion time inside the range.",
            thr=NEUTRAL,
            color_mode="none",
        ),
        4,
        4,
    )
    lay.add(
        stat(
            "Sessions failed",
            targets(target(jobs_failed_in_range())),
            desc="Session Jobs created in the range with at least one failed pod.",
            thr=ANY_IS_BAD,
        ),
        4,
        4,
    )
    lay.add(
        stat(
            "Median session duration",
            targets(target(f"quantile(0.5,\n  {session_duration()}\n)")),
            unit="s",
            desc="Median wall-clock time from Job creation to completion, over sessions "
            "that have finished.",
            thr=NEUTRAL,
            color_mode="none",
        ),
        4,
        4,
    )

    lay.row("Trends")
    lay.add(
        timeseries(
            "Active sessions by kind",
            targets(
                target(
                    f"count by ({promql('kind')}) (\n"
                    f"  {session_pods()}\n  {RUNNING}\n)",
                    legend=f"{{{{{promql('kind')}}}}}",
                )
            ),
            desc="Running sessions over time, split by session type.",
            legend="table",
            calcs=["mean", "max", "lastNotNull"],
            placement="right",
            stack=True,
            fill=25,
        ),
        12,
        9,
    )
    lay.add(
        timeseries(
            "Distinct active users",
            targets(target(_active_users_expr(), legend="Users")),
            desc="Number of users with at least one running session, over time.",
            legend="hidden",
            color=GRYLRD,
            fill=15,
            width=3,
        ),
        12,
        9,
    )
    lay.add(
        timeseries(
            "Session lifecycle",
            targets(
                target(
                    f"sum(kube_job_status_active{{namespace=~\"$namespace\"}}\n"
                    f"  and on (namespace, job_name) {session_jobs()})",
                    legend="Active",
                ),
                target(
                    f"sum(kube_job_status_succeeded{{namespace=~\"$namespace\"}}\n"
                    f"  and on (namespace, job_name) {session_jobs()})",
                    legend="Succeeded",
                ),
                target(
                    f"sum(kube_job_status_failed{{namespace=~\"$namespace\"}}\n"
                    f"  and on (namespace, job_name) {session_jobs()})",
                    legend="Failed",
                ),
            ),
            desc="Job-level view of session state. Sessions are Kubernetes Jobs, so this "
            "is the authoritative lifecycle signal.",
            legend="table",
            calcs=["lastNotNull", "max"],
            overrides=[
                override("Failed", ("color", {"mode": "fixed", "fixedColor": "red"})),
                override("Succeeded", ("color", {"mode": "fixed", "fixedColor": "green"})),
                override("Active", ("color", {"mode": "fixed", "fixedColor": "blue"})),
            ],
        ),
        12,
        9,
    )
    lay.add(
        timeseries(
            "Session duration percentiles",
            targets(
                *[
                    target(
                        f"quantile({q},\n  {session_duration()}\n)",
                        legend=f"p{int(q * 100)}",
                    )
                    for q in (0.5, 0.75, 0.9, 0.99)
                ]
            ),
            unit="s",
            desc="Distribution of completed-session wall-clock duration.",
            legend="table",
            calcs=["lastNotNull", "max"],
            fill=0,
        ),
        12,
        9,
    )

    lay.row("Who is using what")
    lay.add(
        _usage_table("user",
            "Per-user session summary",
            "One row per user, over sessions running right now. Sort by CPU requested to "
            "find the biggest footprint; sort by efficiency ascending to find the biggest "
            "waste.",
        ),
        24,
        12,
    )
    lay.add(
        _usage_table("kind",
            "Per-kind session summary",
            "Same breakdown grouped by session type, for capacity planning per workload "
            "class.",
        ),
        24,
        9,
    )
    lay.add(
        _usage_table(
            "project",
            "Per-project session summary",
            "Project-level accounting. Reads empty on the legacy label generation, "
            "which had no project dimension.",
        ),
        24,
        9,
    )

    lay.row("Composition")
    lay.add(
        piechart(
            "Sessions by kind",
            targets(
                target(
                    f"count by ({promql('kind')}) (\n"
                    f"  {session_pods()}\n  {RUNNING}\n)",
                    legend=f"{{{{{promql('kind')}}}}}",
                )
            ),
            desc="Share of running sessions by type.",
        ),
        8,
        8,
    )
    lay.add(
        piechart(
            "Sessions by flavor",
            targets(
                target(
                    f"count by ({promql('flavor')}) (\n"
                    f"  {session_pods()}\n  {RUNNING}\n)",
                    legend=f"{{{{{promql('flavor')}}}}}",
                )
            ),
            desc="Fixed sessions pin request == limit; flexible sessions may burst. "
            "Reads empty on the legacy label generation, which recorded sizing as two "
            "boolean Job labels rather than a pod label.",
        ),
        8,
        8,
    )
    lay.add(
        piechart(
            "Sessions by accelerator",
            targets(
                target(
                    f"count by ({promql('accelerator')}) (\n"
                    f"  {session_pods()}\n  {RUNNING}\n)",
                    legend=f"{{{{{promql('accelerator')}}}}}",
                )
            ),
            desc="GPU-attached versus CPU-only sessions. Reads empty on the legacy "
            "label generation.",
        ),
        8,
        8,
    )

    return dashboard(
        uid="canfar-sessions__CANFAR_UID_SUFFIX__",
        title="CANFAR · Sessions & Users__CANFAR_TITLE_SUFFIX__",
        description=(
            "Who is on the platform, what they launched, and how long it ran."
        ),
        panels=lay.build(),
        variables=[var_datasource(), var_namespace(), *filter_vars()],
        tags=["sessions"],
        refresh="5m",
        time_from="now-24h",
    )


def board_efficiency() -> dict:
    lay = Layout()
    low = "$low_efficiency_threshold"

    def below(resource: str) -> str:
        used = pod_cpu_used() if resource == "cpu" else pod_memory_used()
        req = pod_requests("cpu" if resource == "cpu" else "memory")
        return (
            f"count(\n"
            f"  (\n"
            f"    (\n      {used}\n    )\n"
            f"    / on (namespace, pod)\n"
            f"    (\n      {req}\n    )\n"
            f"  ) < {low}\n"
            f"  and on (namespace, pod) {session_pods()}\n"
            f"  {RUNNING}\n"
            f")\n"
            f"or vector(0)"
        )

    lay.row("Waste headline")
    lay.add(
        stat(
            "CPU wasted",
            targets(target(waste_hours("cpu"))),
            unit=CORE_HOURS,
            decimals=0,
            desc=(
                "Requested-minus-used CPU integrated over the dashboard range. This is "
                "capacity the scheduler could not give to anyone else."
            ),
            thr=thresholds(("text", None)),
            color_mode="none",
        ),
        4,
        5,
    )
    lay.add(
        stat(
            "Memory wasted",
            targets(target(waste_hours("memory", divisor="1024^3"))),
            unit=GIB_HOURS,
            decimals=0,
            desc="Requested-minus-used memory integrated over the dashboard range.",
            thr=thresholds(("text", None)),
            color_mode="none",
        ),
        4,
        5,
    )
    lay.add(
        gauge(
            "CPU efficiency",
            targets(
                target(
                    f"(\n{total(pod_cpu_used(), running_only=True)}\n)\n/\n"
                    f"(\n{total(pod_requests('cpu'), running_only=True)}\n)"
                )
            ),
            desc="Used ÷ requested CPU across running sessions.",
            thr=EFFICIENCY,
        ),
        4,
        5,
    )
    lay.add(
        gauge(
            "RAM efficiency",
            targets(
                target(
                    f"(\n{total(pod_memory_used(), running_only=True)}\n)\n/\n"
                    f"(\n{total(pod_requests('memory'), running_only=True)}\n)"
                )
            ),
            desc="Working set ÷ requested memory across running sessions.",
            thr=EFFICIENCY,
        ),
        4,
        5,
    )
    lay.add(
        stat(
            "Low-CPU sessions",
            targets(target(below("cpu"))),
            desc=f"Running sessions using less than {low} of their requested CPU.",
            thr=thresholds(("green", None), ("#EAB839", 1), ("red", 10)),
        ),
        4,
        5,
    )
    lay.add(
        stat(
            "Low-RAM sessions",
            targets(target(below("memory"))),
            desc=f"Running sessions using less than {low} of their requested memory.",
            thr=thresholds(("green", None), ("#EAB839", 1), ("red", 10)),
        ),
        4,
        5,
    )

    lay.row("Efficiency over time")
    lay.add(_efficiency_ts("cpu", "kind"), 12, 9)
    lay.add(_efficiency_ts("memory", "kind"), 12, 9)

    lay.row("What users are asking for")
    cpu_req_pod = (
        f"(\n  {pod_requests('cpu')}\n)\n"
        f"and on (namespace, pod) {session_pods()}\n{RUNNING}"
    )
    ram_req_pod = (
        f"(\n  {pod_requests('memory')}\n)\n"
        f"and on (namespace, pod) {session_pods()}\n{RUNNING}"
    )
    lay.add(
        bargauge(
            "Sessions by requested CPU",
            targets(
                target(
                    f'count_values without (namespace, pod) ("cores",\n'
                    f"  clamp_max(ceil(\n{cpu_req_pod}\n  ), 64)\n)",
                    legend="{{cores}} cores",
                    instant=True,
                )
            ),
            desc="How many running sessions sit in each whole-core request bucket. A "
            "long tail on the right with poor efficiency is the over-requesting problem.",
            color=GRYLRD,
        ),
        12,
        9,
    )
    lay.add(
        bargauge(
            "Sessions by requested memory",
            targets(
                target(
                    f'count_values without (namespace, pod) ("gib",\n'
                    f"  clamp_max(2 * ceil(\n(\n{ram_req_pod}\n)\n / (2 * 1024^3)\n  ), 256)\n)",
                    legend="{{gib}} GiB",
                    instant=True,
                )
            ),
            desc="Running sessions bucketed into 2 GiB memory-request bands.",
            color=GRYLRD,
        ),
        12,
        9,
    )
    lay.add(
        heatmap(
            "Requested CPU distribution over time",
            targets(
                target(
                    f'count_values without (namespace, pod) ("cores",\n'
                    f"  clamp_max(ceil(\n{cpu_req_pod}\n  ), 64)\n)",
                    legend="{{cores}}",
                )
            ),
            desc="Session count per CPU-request bucket, over time.",
            y_unit=CORES,
        ),
        12,
        9,
    )
    lay.add(
        heatmap(
            "Requested memory distribution over time",
            targets(
                target(
                    f'count_values without (namespace, pod) ("gib",\n'
                    f"  clamp_max(2 * ceil(\n(\n{ram_req_pod}\n)\n / (2 * 1024^3)\n  ), 256)\n)",
                    legend="{{gib}}",
                )
            ),
            desc="Session count per 2 GiB memory-request bucket, over time.",
            y_unit="gbytes",
        ),
        12,
        9,
    )

    lay.row("Where the waste is")
    lay.add(
        _usage_table("user",
            "Per-user footprint and efficiency",
            "Sort ascending by CPU or RAM efficiency to find who to talk to first; the "
            "footer totals show the platform-wide picture.",
        ),
        24,
        12,
    )

    lay.row("Resource percentiles")
    for resource, unit, metric_used, metric_req in (
        ("CPU", CORES, pod_cpu_used(), pod_requests("cpu")),
        ("Memory", "bytes", pod_memory_used(), pod_requests("memory")),
    ):
        kind_lbl = promql("kind")

        def pctl(expr: str, q: float) -> str:
            return (
                f"quantile by ({kind_lbl}) ({q},\n"
                f"  max_over_time(\n"
                f"    (\n"
                f"      (\n        {expr}\n      )\n"
                f"      * on (namespace, pod) group_left ({kind_lbl})\n"
                f"      {session_pods()}\n"
                f"    )[$__range:5m]\n"
                f"  )\n"
                f")"
            )

        cols = []
        for label, expr in (("requested", metric_req), ("used", metric_used)):
            for q in (0.1, 0.5, 0.9):
                cols.append((f"p{int(q * 100)} {label}", pctl(expr, q)))
        lay.add(
            _instant_table(
                kind_lbl,
                cols,
                f"{resource} percentiles by session kind",
                (
                    f"Per-pod peak {resource} over the range, reduced to percentiles "
                    "across sessions of each kind. This is the "
                    "'Resource Percentiles (By Type)' table from the CANFAR metrics "
                    "specification."
                ),
                dim_display="Session kind",
                overrides=[
                    override(
                        ".*",
                        ("unit", unit),
                        ("decimals", 2),
                        *gradient_cell(),
                        regex=True,
                    ),
                    override(kind_lbl, ("custom.cellOptions", {"type": "auto"})),
                ],
                unit=unit,
            ),
            24,
            8,
        )

    return dashboard(
        uid="canfar-efficiency__CANFAR_UID_SUFFIX__",
        title="CANFAR · Efficiency & Waste__CANFAR_TITLE_SUFFIX__",
        description=(
            "Where the platform is holding capacity that nobody is using, and who to "
            "talk to about it."
        ),
        panels=lay.build(),
        variables=[
            var_datasource(),
            var_namespace(),
            *filter_vars(),
            var_custom(
                "low_efficiency_threshold",
                "0.05, 0.1, 0.25, 0.5",
                label="Low-efficiency threshold",
                current="0.1",
            ),
        ],
        tags=["efficiency"],
        refresh="5m",
        time_from="now-24h",
    )


def board_drilldown() -> dict:
    lay = Layout()
    pods = session_pods(extra=POD_FILTER)

    lay.row("Selected sessions")
    lay.add(
        table(
            "Session inventory",
            targets(
                target(
                    f"{pods}\n"
                    f"* on (namespace, pod) group_left (node, pod_ip, created_by_name)\n"
                    f'  kube_pod_info{{namespace=~"$namespace"}}',
                    instant=True,
                    table=True,
                )
            ),
            desc="Every session pod matching the current filters, with the CANFAR labels "
            "it carries and the node it landed on.",
            transformations=[
                {"id": "labelsToFields", "options": {}},
                {
                    "id": "organize",
                    "options": {
                        "renameByName": {
                            d.promql: d.display for d in DIMS
                        }
                        | {"node": "Node", "pod": "Pod", "namespace": "Namespace"},
                        "excludeByName": {
                            "Time": True,
                            "Value": True,
                            "__name__": True,
                            "job": True,
                            "instance": True,
                            "uid": True,
                            "container": True,
                            "endpoint": True,
                            "service": True,
                            "host_ip": True,
                            "host_network": True,
                            "created_by_kind": True,
                            "pod_ip": False,
                        },
                        "indexByName": {},
                    },
                },
            ],
            paginate=True,
        ),
        24,
        11,
    )

    lay.row("Resource usage")
    lay.add(
        timeseries(
            "CPU per session",
            targets(
                target(
                    by_pod(pod_cpu_used(), extra=POD_FILTER),
                    legend="{{pod}}",
                )
            ),
            unit=CORES,
            desc="Actual CPU burn per selected session.",
            legend="table",
            calcs=["mean", "max", "lastNotNull"],
            placement="right",
        ),
        12,
        9,
    )
    lay.add(
        timeseries(
            "Memory per session",
            targets(
                target(
                    by_pod(pod_memory_used(), extra=POD_FILTER),
                    legend="{{pod}}",
                )
            ),
            unit="bytes",
            desc="Working-set memory per selected session.",
            legend="table",
            calcs=["mean", "max", "lastNotNull"],
            placement="right",
        ),
        12,
        9,
    )
    lay.add(
        timeseries(
            "CPU headroom per session",
            targets(
                target(
                    by_pod(pod_requests("cpu"), extra=POD_FILTER),
                    legend="request · {{pod}}",
                ),
                target(
                    by_pod(pod_limits("cpu"), extra=POD_FILTER),
                    legend="limit · {{pod}}",
                ),
            ),
            unit=CORES,
            desc="Requested and limit CPU per session, to compare against actual burn.",
            legend="table",
            calcs=["lastNotNull"],
            placement="right",
            fill=0,
        ),
        12,
        9,
    )
    lay.add(
        timeseries(
            "Memory headroom per session",
            targets(
                target(
                    by_pod(pod_requests("memory"), extra=POD_FILTER),
                    legend="request · {{pod}}",
                ),
                target(
                    by_pod(pod_limits("memory"), extra=POD_FILTER),
                    legend="limit · {{pod}}",
                ),
            ),
            unit="bytes",
            desc="Requested and limit memory per session. A session whose working set "
            "approaches its limit is about to be OOM killed.",
            legend="table",
            calcs=["lastNotNull"],
            placement="right",
            fill=0,
        ),
        12,
        9,
    )

    lay.row("Network & disk")
    lay.add(
        timeseries(
            "Network receive",
            targets(
                target(
                    by_pod(pod_net("receive"), extra=POD_FILTER),
                    legend="{{pod}}",
                )
            ),
            unit="Bps",
            desc="Inbound network throughput per session.",
            legend="table",
            calcs=["mean", "max"],
            placement="right",
        ),
        12,
        8,
    )
    lay.add(
        timeseries(
            "Network transmit",
            targets(
                target(
                    by_pod(pod_net("transmit"), extra=POD_FILTER),
                    legend="{{pod}}",
                )
            ),
            unit="Bps",
            desc="Outbound network throughput per session.",
            legend="table",
            calcs=["mean", "max"],
            placement="right",
        ),
        12,
        8,
    )
    lay.add(
        timeseries(
            "Filesystem reads",
            targets(
                target(
                    by_pod(pod_fs("reads"), extra=POD_FILTER),
                    legend="{{pod}}",
                )
            ),
            unit="Bps",
            desc="Container filesystem read throughput per session.",
            legend="table",
            calcs=["mean", "max"],
            placement="right",
        ),
        12,
        8,
    )
    lay.add(
        timeseries(
            "Filesystem writes",
            targets(
                target(
                    by_pod(pod_fs("writes"), extra=POD_FILTER),
                    legend="{{pod}}",
                )
            ),
            unit="Bps",
            desc="Container filesystem write throughput per session.",
            legend="table",
            calcs=["mean", "max"],
            placement="right",
        ),
        12,
        8,
    )

    lay.row("Health")
    lay.add(_termination_ts(), 12, 8)
    lay.add(
        timeseries(
            "CPU throttling",
            targets(
                target(
                    f"sum by (pod) (\n"
                    f"  (\n"
                    f"    sum by (namespace, pod) (\n"
                    f"      rate(container_cpu_cfs_throttled_periods_total"
                    f'{{namespace=~"$namespace", container!=""}}[$__rate_interval])\n'
                    f"    )\n"
                    f"    /\n"
                    f"    sum by (namespace, pod) (\n"
                    f"      rate(container_cpu_cfs_periods_total"
                    f'{{namespace=~"$namespace", container!=""}}[$__rate_interval])\n'
                    f"    )\n"
                    f"  )\n"
                    f"  and on (namespace, pod) {pods}\n"
                    f")",
                    legend="{{pod}}",
                )
            ),
            unit="percentunit",
            desc="Fraction of CPU scheduling periods in which the session was throttled "
            "against its limit. Sustained throttling means the session is limit-bound, "
            "not idle.",
            legend="table",
            calcs=["mean", "max"],
            placement="right",
            minimum=0,
            thr=SATURATION,
        ),
        12,
        8,
    )

    return dashboard(
        uid="canfar-drilldown__CANFAR_UID_SUFFIX__",
        title="CANFAR · Session Drilldown__CANFAR_TITLE_SUFFIX__",
        description=(
            "Per-session forensics: pick a user, kind, or pod and see exactly what "
            "that workload did."
        ),
        panels=lay.build(),
        variables=[
            var_datasource(),
            var_namespace(),
            *filter_vars(),
            var_query(
                "pod",
                f'label_values(kube_pod_labels{{namespace=~"$namespace", '
                f'{promql(IDENTITY_DIM)}!=""}}, pod)',
                label="Session pod",
                desc=(
                    "Session pod name, which embeds kind, user, and session ID. Used "
                    "instead of the session-ID label so drilldown does not depend on "
                    "that label being in the kube-state-metrics allowlist."
                ),
            ),
        ],
        tags=["drilldown"],
        refresh="1m",
    )


# ======================================================================================
# Shared dashboards
# ======================================================================================


def board_queue() -> dict:
    lay = Layout()
    cq = "$cluster_queue"

    lay.row("The pulse: is the factory moving?")
    lay.add(
        stat(
            "Admission attempts/s",
            targets(target("sum(rate(kueue_admission_attempts_total[$__rate_interval]))")),
            unit="reqps",
            decimals=2,
            desc="Rate at which Kueue tries to admit workloads. The cluster speedometer.",
            color_mode="none",
        ),
        4,
        5,
    )
    lay.add(
        stat(
            "Admitted workloads",
            targets(
                target(
                    f'sum(kueue_admitted_active_workloads{{cluster_queue=~"{cq}"}})'
                )
            ),
            desc="Workloads currently admitted and running.",
            color_mode="none",
        ),
        4,
        5,
    )
    lay.add(
        stat(
            "Pending workloads",
            targets(target(f'sum(kueue_pending_workloads{{cluster_queue=~"{cq}"}})')),
            desc="Head-of-line backlog. High and flat alongside a flat admission rate "
            "means the scheduler is stalled, not busy.",
            thr=thresholds(("green", None), ("#EAB839", 10), ("red", 50)),
        ),
        4,
        5,
    )
    lay.add(
        stat(
            "Admission latency p99",
            targets(
                target(
                    "histogram_quantile(0.99,\n"
                    "  sum by (le) (\n"
                    "    rate(kueue_admission_attempt_duration_seconds_bucket"
                    "[$__rate_interval])\n"
                    "  )\n)"
                )
            ),
            unit="s",
            decimals=3,
            desc="How long a single admission attempt takes inside the scheduler.",
            thr=thresholds(("green", None), ("#EAB839", 1), ("red", 5)),
        ),
        4,
        5,
    )
    lay.add(
        stat(
            "Queue wait p50",
            targets(
                target(
                    "histogram_quantile(0.5,\n"
                    "  sum by (le) (\n"
                    "    rate(kueue_admission_wait_time_seconds_bucket"
                    "[$__rate_interval])\n"
                    "  )\n)"
                )
            ),
            unit="s",
            desc="Median time a workload spends queued before it starts. The user-facing "
            "SLA number.",
            thr=thresholds(("green", None), ("#EAB839", 60), ("red", 600)),
        ),
        4,
        5,
    )
    lay.add(
        stat(
            "Evictions in range",
            targets(
                target(
                    "sum(increase(kueue_evicted_workloads_total[$__range]))\nor vector(0)"
                )
            ),
            desc="Workloads killed to make room for others. Every eviction is discarded "
            "compute — badput.",
            thr=thresholds(("green", None), ("#EAB839", 1), ("red", 25)),
        ),
        4,
        5,
    )

    lay.row("Throughput and backlog")
    lay.add(
        timeseries(
            "Pending workloads per ClusterQueue",
            targets(
                target(
                    f'sum by (cluster_queue) (kueue_pending_workloads{{cluster_queue=~"{cq}"}})',
                    legend="{{cluster_queue}}",
                )
            ),
            desc="Backlog per queue. Divergence between queues points at a quota or "
            "priority misconfiguration.",
            legend="table",
            calcs=["mean", "max", "lastNotNull"],
            placement="right",
            stack=True,
            fill=25,
            color=GRYLRD,
        ),
        12,
        9,
    )
    lay.add(
        timeseries(
            "Admitted active workloads per ClusterQueue",
            targets(
                target(
                    f"sum by (cluster_queue) "
                    f'(kueue_admitted_active_workloads{{cluster_queue=~"{cq}"}})',
                    legend="{{cluster_queue}}",
                )
            ),
            desc="Work in flight per queue.",
            legend="table",
            calcs=["mean", "max", "lastNotNull"],
            placement="right",
            stack=True,
            fill=25,
        ),
        12,
        9,
    )
    lay.add(
        timeseries(
            "Queue wait time percentiles",
            targets(
                *[
                    target(
                        f"histogram_quantile({q},\n"
                        f"  sum by (le) (\n"
                        f"    rate(kueue_admission_wait_time_seconds_bucket"
                        f"[$__rate_interval])\n"
                        f"  )\n)",
                        legend=f"p{int(q * 100)}",
                    )
                    for q in (0.5, 0.9, 0.99)
                ]
            ),
            unit="s",
            desc="Are users waiting seconds, minutes, or days? Tune borrowing limits "
            "against this.",
            legend="table",
            calcs=["mean", "max"],
            fill=0,
        ),
        12,
        9,
    )
    lay.add(
        timeseries(
            "Evictions by reason",
            targets(
                target(
                    "sum by (reason) (rate(kueue_evicted_workloads_total"
                    "[$__rate_interval]))",
                    legend="{{reason}}",
                )
            ),
            unit="reqps",
            desc="Preemption pressure. Above roughly 5% of the admission rate the policy "
            "is too aggressive and is destroying more work than it unblocks.",
            legend="table",
            calcs=["mean", "max"],
            stack=True,
            thr=ANY_IS_BAD,
        ),
        12,
        9,
    )

    lay.row("Quota and fair share")
    lay.add(
        bargauge(
            "Quota saturation by ClusterQueue and resource",
            targets(
                target(
                    f"sum by (cluster_queue, resource) (\n"
                    f'  kueue_cluster_queue_resource_usage{{cluster_queue=~"{cq}"}}\n'
                    f")\n/\n"
                    f"sum by (cluster_queue, resource) (\n"
                    f'  kueue_cluster_queue_nominal_quota{{cluster_queue=~"{cq}"}}\n'
                    f")",
                    legend="{{cluster_queue}} · {{resource}}",
                    instant=True,
                )
            ),
            unit="percentunit",
            desc="Reserved quota over nominal quota. Above 1.0 the queue is borrowing "
            "from its cohort; persistently below 0.5 the guarantee is oversized.",
            maximum=1,
            thr=SATURATION,
            color={"mode": "thresholds"},
        ),
        12,
        10,
    )
    lay.add(
        _instant_table(
            "cluster_queue",
            [
                (
                    "Nominal quota",
                    'sum by (cluster_queue) (kueue_cluster_queue_nominal_quota'
                    f'{{cluster_queue=~"{cq}", resource="cpu"}})',
                ),
                (
                    "Reserved",
                    "sum by (cluster_queue) (kueue_cluster_queue_resource_reservation"
                    f'{{cluster_queue=~"{cq}", resource="cpu"}})',
                ),
                (
                    "Used",
                    "sum by (cluster_queue) (kueue_cluster_queue_resource_usage"
                    f'{{cluster_queue=~"{cq}", resource="cpu"}})',
                ),
                (
                    "Borrowing limit",
                    "sum by (cluster_queue) (kueue_cluster_queue_borrowing_limit"
                    f'{{cluster_queue=~"{cq}", resource="cpu"}})',
                ),
                ("Pending", f'sum by (cluster_queue) (kueue_pending_workloads{{cluster_queue=~"{cq}"}})'),
                (
                    "Admitted",
                    f'sum by (cluster_queue) (kueue_admitted_active_workloads{{cluster_queue=~"{cq}"}})',
                ),
            ],
            "CPU quota ledger per ClusterQueue",
            "The passive accounting view: what each queue is guaranteed, what it has "
            "reserved, and what it is actually burning.",
            dim_display="ClusterQueue",
            overrides=[
                override("Nominal quota", ("unit", CORES), ("decimals", 1)),
                override("Reserved", ("unit", CORES), ("decimals", 1)),
                override("Used", ("unit", CORES), ("decimals", 1), *gradient_cell()),
                override("Borrowing limit", ("unit", CORES), ("decimals", 1)),
            ],
            sort_col="Used",
        ),
        12,
        10,
    )
    lay.add(
        timeseries(
            "LocalQueue backlog by namespace",
            targets(
                target(
                    "sum by (exported_namespace) (kueue_local_queue_pending_workloads)",
                    legend="pending · {{exported_namespace}}",
                ),
                target(
                    "sum by (exported_namespace) "
                    "(kueue_local_queue_reserving_active_workloads)",
                    legend="reserving · {{exported_namespace}}",
                ),
                target(
                    "sum by (exported_namespace) "
                    "(kueue_local_queue_admitted_active_workloads)",
                    legend="admitted · {{exported_namespace}}",
                ),
            ),
            desc="Per-namespace queue state. `reserving` sessions have won quota and are "
            "starting; `pending` sessions are still waiting.",
            legend="table",
            calcs=["lastNotNull", "max"],
            placement="right",
        ),
        24,
        9,
    )

    return dashboard(
        uid="canfar-queue",
        title="CANFAR · Queue & Scheduling",
        description=(
            "Kueue throughput, wait time, quota saturation, and preemption badput. "
            "Label-scheme independent: every series here comes from Kueue itself."
        ),
        panels=lay.build(),
        variables=[
            var_datasource(),
            var_query(
                "cluster_queue",
                "label_values(kueue_pending_workloads, cluster_queue)",
                label="ClusterQueue",
                desc="Kueue ClusterQueue to scope the dashboard to.",
            ),
        ],
        tags=["queue", "kueue", "shared"],
        refresh="1m",
    )


def board_capacity() -> dict:
    lay = Layout()
    node = '{node=~"$node"}'

    def alloc(resource: str) -> str:
        return f'sum(kube_node_status_allocatable{{resource="{resource}", node=~"$node"}})'

    lay.row("Cluster inventory")
    lay.add(
        stat(
            "Nodes ready",
            targets(
                target(
                    'sum(kube_node_status_condition{condition="Ready", status="true", '
                    'node=~"$node"} == 1)'
                )
            ),
            desc="Nodes passing the Ready condition.",
            color_mode="none",
        ),
        4,
        5,
    )
    lay.add(
        stat(
            "Nodes unschedulable",
            targets(
                target(f"sum(kube_node_spec_unschedulable{node})\nor vector(0)")
            ),
            desc="Cordoned nodes. Their capacity is invisible to the scheduler.",
            thr=ANY_IS_BAD,
        ),
        4,
        5,
    )
    lay.add(
        stat(
            "Allocatable CPU",
            targets(target(alloc("cpu"))),
            unit=CORES,
            desc="Total schedulable cores.",
            color_mode="none",
        ),
        4,
        5,
    )
    lay.add(
        stat(
            "Allocatable memory",
            targets(target(alloc("memory"))),
            unit="bytes",
            desc="Total schedulable memory.",
            color_mode="none",
        ),
        4,
        5,
    )
    lay.add(
        gauge(
            "CPU committed",
            targets(
                target(
                    'sum(kube_pod_container_resource_requests{resource="cpu"})\n'
                    f"/ {alloc('cpu')}"
                )
            ),
            desc="Requested cores over allocatable cores.",
            thr=SATURATION,
        ),
        4,
        5,
    )
    lay.add(
        gauge(
            "Memory committed",
            targets(
                target(
                    'sum(kube_pod_container_resource_requests{resource="memory"})\n'
                    f"/ {alloc('memory')}"
                )
            ),
            desc="Requested memory over allocatable memory.",
            thr=SATURATION,
        ),
        4,
        5,
    )

    lay.row("Cluster utilization")
    lay.add(_capacity_ts("cpu"), 12, 9)
    lay.add(_capacity_ts("memory"), 12, 9)

    lay.row("Per-node")
    lay.add(
        _instant_table(
            "node",
            [
                ("Pods", f"count by (node) (kube_pod_info{{node=~\"$node\"}})"),
                ("Pod capacity", f'sum by (node) (kube_node_status_allocatable{{resource="pods", node=~"$node"}})'),
                ("Cores", f'sum by (node) (kube_node_status_allocatable{{resource="cpu", node=~"$node"}})'),
                (
                    "CPU requested",
                    'sum by (node) (kube_pod_container_resource_requests{resource="cpu", '
                    'node=~"$node"})',
                ),
                (
                    "CPU used",
                    "sum by (node) (rate(container_cpu_usage_seconds_total"
                    '{container!=""}[$__rate_interval])\n'
                    '  * on (namespace, pod) group_left (node) kube_pod_info{node=~"$node"})',
                ),
                ("Memory", f'sum by (node) (kube_node_status_allocatable{{resource="memory", node=~"$node"}})'),
                (
                    "Memory requested",
                    'sum by (node) (kube_pod_container_resource_requests'
                    '{resource="memory", node=~"$node"})',
                ),
                (
                    "Memory used",
                    'sum by (node) (container_memory_working_set_bytes{container!=""}\n'
                    '  * on (namespace, pod) group_left (node) kube_pod_info{node=~"$node"})',
                ),
            ],
            "Node inventory",
            "Capacity, commitment, and real usage per node. A node with high requested "
            "and low used is where reclaimable capacity is hiding.",
            dim_display="Node",
            overrides=[
                override("Cores", ("unit", CORES), ("decimals", 0)),
                override("CPU requested", ("unit", CORES), ("decimals", 1), *gradient_cell()),
                override("CPU used", ("unit", CORES), ("decimals", 1), *gradient_cell()),
                override("Memory", ("unit", "bytes")),
                override("Memory requested", ("unit", "bytes"), *gradient_cell()),
                override("Memory used", ("unit", "bytes"), *gradient_cell()),
            ],
            sort_col="CPU requested",
        ),
        24,
        12,
    )
    lay.add(
        timeseries(
            "CPU utilization by node",
            targets(
                target(
                    "sum by (node) (rate(container_cpu_usage_seconds_total"
                    '{container!=""}[$__rate_interval])\n'
                    '  * on (namespace, pod) group_left (node) kube_pod_info{node=~"$node"})\n'
                    "/\n"
                    'sum by (node) (kube_node_status_allocatable{resource="cpu", node=~"$node"})',
                    legend="{{node}}",
                )
            ),
            unit="percentunit",
            desc="Real CPU burn over allocatable, per node.",
            legend="table",
            calcs=["mean", "max"],
            placement="right",
            minimum=0,
            fill=0,
        ),
        12,
        9,
    )
    lay.add(
        timeseries(
            "Memory utilization by node",
            targets(
                target(
                    'sum by (node) (container_memory_working_set_bytes{container!=""}\n'
                    '  * on (namespace, pod) group_left (node) kube_pod_info{node=~"$node"})\n'
                    "/\n"
                    'sum by (node) (kube_node_status_allocatable{resource="memory", node=~"$node"})',
                    legend="{{node}}",
                )
            ),
            unit="percentunit",
            desc="Working-set memory over allocatable, per node.",
            legend="table",
            calcs=["mean", "max"],
            placement="right",
            minimum=0,
            fill=0,
        ),
        12,
        9,
    )

    lay.row("Pressure and fragmentation")
    for cond, label in (
        ("DiskPressure", "Disk pressure"),
        ("MemoryPressure", "Memory pressure"),
        ("PIDPressure", "PID pressure"),
        ("NetworkUnavailable", "Network unavailable"),
    ):
        lay.add(
            stat(
                label,
                targets(
                    target(
                        f'sum(kube_node_status_condition{{condition="{cond}", '
                        f'status="true", node=~"$node"}})\nor vector(0)'
                    )
                ),
                desc=f"Nodes reporting the {cond} condition.",
                thr=ANY_IS_BAD,
                graph="none",
            ),
            4,
            5,
        )
    lay.add(
        timeseries(
            "Stranded CPU on memory-full nodes",
            targets(
                target(
                    "sum(\n"
                    "  (\n"
                    '    sum by (node) (kube_node_status_allocatable{resource="cpu", node=~"$node"})\n'
                    "    -\n"
                    "    sum by (node) (kube_pod_container_resource_requests"
                    '{resource="cpu"}\n'
                    "      * on (namespace, pod) group_left (node) kube_pod_info"
                    '{node=~"$node"})\n'
                    "  )\n"
                    "  and on (node)\n"
                    "  (\n"
                    "    sum by (node) (kube_pod_container_resource_requests"
                    '{resource="memory"}\n'
                    "      * on (namespace, pod) group_left (node) kube_pod_info"
                    '{node=~"$node"})\n'
                    "    /\n"
                    '    sum by (node) (kube_node_status_allocatable{resource="memory", node=~"$node"})\n'
                    "    > 0.9\n"
                    "  )\n"
                    ")\nor vector(0)",
                    legend="Stranded cores",
                )
            ),
            unit=CORES,
            desc=(
                "Free cores sitting on nodes whose memory is more than 90% committed. "
                "These cores are unschedulable in practice — the slot-fragmentation "
                "signal that says hardware shape and job shape have diverged."
            ),
            legend="hidden",
            color=GRYLRD,
            fill=15,
        ),
        8,
        5,
    )

    return dashboard(
        uid="canfar-capacity",
        title="CANFAR · Cluster Capacity",
        description=(
            "Node inventory, commitment, pressure conditions, and slot fragmentation. "
            "Label-scheme independent."
        ),
        panels=lay.build(),
        variables=[
            var_datasource(),
            var_query(
                "node",
                "label_values(kube_node_info, node)",
                label="Node",
                desc="Restrict to a subset of cluster nodes.",
            ),
        ],
        tags=["capacity", "nodes", "shared"],
        refresh="1m",
    )


def board_storage() -> dict:
    lay = Layout()
    pvc = 'persistentvolumeclaim=~"$pvc"'

    lay.row("Persistent volumes")
    lay.add(
        stat(
            "Volumes tracked",
            targets(target(f"count(kubelet_volume_stats_capacity_bytes{{{pvc}}})")),
            desc="PersistentVolumeClaims reporting stats.",
            color_mode="none",
        ),
        4,
        5,
    )
    lay.add(
        stat(
            "Volumes above 80%",
            targets(
                target(
                    f"count(\n"
                    f"  kubelet_volume_stats_used_bytes{{{pvc}}}\n"
                    f"  / kubelet_volume_stats_capacity_bytes{{{pvc}}}\n"
                    f"  > 0.8\n"
                    f")\nor vector(0)"
                )
            ),
            desc="Volumes at risk of filling up.",
            thr=ANY_IS_BAD,
        ),
        4,
        5,
    )
    lay.add(
        stat(
            "Total capacity",
            targets(target(f"sum(kubelet_volume_stats_capacity_bytes{{{pvc}}})")),
            unit="bytes",
            desc="Sum of provisioned PVC capacity.",
            color_mode="none",
        ),
        4,
        5,
    )
    lay.add(
        stat(
            "Total used",
            targets(target(f"sum(kubelet_volume_stats_used_bytes{{{pvc}}})")),
            unit="bytes",
            desc="Sum of consumed PVC space.",
            color_mode="none",
        ),
        4,
        5,
    )
    lay.add(
        gauge(
            "Aggregate fill",
            targets(
                target(
                    f"sum(kubelet_volume_stats_used_bytes{{{pvc}}})\n"
                    f"/ sum(kubelet_volume_stats_capacity_bytes{{{pvc}}})"
                )
            ),
            desc="Used over provisioned, across all selected volumes.",
            thr=SATURATION,
        ),
        8,
        5,
    )

    lay.add(
        _instant_table(
            "persistentvolumeclaim",
            [
                ("Used", f"sum by (persistentvolumeclaim) (kubelet_volume_stats_used_bytes{{{pvc}}})"),
                (
                    "Capacity",
                    f"sum by (persistentvolumeclaim) (kubelet_volume_stats_capacity_bytes{{{pvc}}})",
                ),
                (
                    "Fill",
                    f"sum by (persistentvolumeclaim) (kubelet_volume_stats_used_bytes{{{pvc}}})\n"
                    f"/ sum by (persistentvolumeclaim) (kubelet_volume_stats_capacity_bytes{{{pvc}}})",
                ),
                (
                    "Inodes used",
                    f"sum by (persistentvolumeclaim) (kubelet_volume_stats_inodes_used{{{pvc}}})\n"
                    f"/ sum by (persistentvolumeclaim) (kubelet_volume_stats_inodes{{{pvc}}})",
                ),
            ],
            "Volume inventory",
            "Space and inode consumption per claim. Inode exhaustion fills a volume "
            "that still reports free bytes, so both columns matter.",
            dim_display="PersistentVolumeClaim",
            overrides=[
                override("Used", ("unit", "bytes")),
                override("Capacity", ("unit", "bytes")),
                override(
                    "Fill",
                    ("unit", "percentunit"),
                    ("decimals", 1),
                    ("max", 1),
                    ("min", 0),
                    ("thresholds", SATURATION),
                    *gradient_cell(),
                ),
                override(
                    "Inodes used",
                    ("unit", "percentunit"),
                    ("decimals", 1),
                    ("max", 1),
                    ("min", 0),
                    ("thresholds", SATURATION),
                    *gradient_cell(),
                ),
            ],
            sort_col="Fill",
            unit="bytes",
        ),
        14,
        11,
    )
    lay.add(
        timeseries(
            "Volume fill over time",
            targets(
                target(
                    f"sum by (persistentvolumeclaim) (kubelet_volume_stats_used_bytes{{{pvc}}})\n"
                    f"/ sum by (persistentvolumeclaim) "
                    f"(kubelet_volume_stats_capacity_bytes{{{pvc}}})",
                    legend="{{persistentvolumeclaim}}",
                )
            ),
            unit="percentunit",
            desc="Trend matters more than the instantaneous number: a volume climbing "
            "steadily will page someone at 3am.",
            legend="table",
            calcs=["lastNotNull", "max"],
            placement="right",
            minimum=0,
            maximum=1,
            thr=SATURATION,
        ),
        10,
        11,
    )

    lay.row("Node block device I/O")
    lay.add(
        timeseries(
            "Read throughput",
            targets(
                target(
                    'sum by (instance) (rate(node_disk_read_bytes_total{device=~"$device"}'
                    "[$__rate_interval]))",
                    legend="{{instance}}",
                )
            ),
            unit="Bps",
            desc="Bytes read from disk per node.",
            legend="table",
            calcs=["mean", "max"],
            placement="right",
        ),
        12,
        8,
    )
    lay.add(
        timeseries(
            "Write throughput",
            targets(
                target(
                    'sum by (instance) (rate(node_disk_written_bytes_total{device=~"$device"}'
                    "[$__rate_interval]))",
                    legend="{{instance}}",
                )
            ),
            unit="Bps",
            desc="Bytes written to disk per node.",
            legend="table",
            calcs=["mean", "max"],
            placement="right",
        ),
        12,
        8,
    )
    lay.add(
        timeseries(
            "IOPS",
            targets(
                target(
                    'sum by (instance) (rate(node_disk_reads_completed_total{device=~"$device"}'
                    "[$__rate_interval]))",
                    legend="read · {{instance}}",
                ),
                target(
                    'sum by (instance) (rate(node_disk_writes_completed_total{device=~"$device"}'
                    "[$__rate_interval]))",
                    legend="write · {{instance}}",
                ),
            ),
            unit="iops",
            desc="Completed operations per second.",
            legend="table",
            calcs=["mean", "max"],
            placement="right",
        ),
        12,
        8,
    )
    lay.add(
        timeseries(
            "Average I/O latency",
            targets(
                target(
                    'rate(node_disk_read_time_seconds_total{device=~"$device"}'
                    "[$__rate_interval])\n"
                    '/ clamp_min(rate(node_disk_reads_completed_total{device=~"$device"}'
                    "[$__rate_interval]), 1)",
                    legend="read · {{instance}} {{device}}",
                ),
                target(
                    'rate(node_disk_write_time_seconds_total{device=~"$device"}'
                    "[$__rate_interval])\n"
                    '/ clamp_min(rate(node_disk_writes_completed_total{device=~"$device"}'
                    "[$__rate_interval]), 1)",
                    legend="write · {{instance}} {{device}}",
                ),
            ),
            unit="s",
            desc="Service time per operation. The clamp keeps idle devices from producing "
            "a divide-by-zero spike instead of a flat line.",
            legend="table",
            calcs=["mean", "max"],
            placement="right",
            thr=thresholds(("green", None), ("#EAB839", 0.02), ("red", 0.1)),
        ),
        12,
        8,
    )

    return dashboard(
        uid="canfar-storage",
        title="CANFAR · Storage",
        description=(
            "PersistentVolumeClaim fill, inode headroom, and node block-device "
            "throughput and latency. Label-scheme independent."
        ),
        panels=lay.build(),
        variables=[
            var_datasource(),
            var_query(
                "pvc",
                "label_values(kubelet_volume_stats_capacity_bytes, persistentvolumeclaim)",
                label="PersistentVolumeClaim",
            ),
            var_query(
                "device",
                "label_values(node_disk_io_now, device)",
                label="Block device",
                regex="/^(sd|vd|nvme|dm-).*/",
            ),
        ],
        tags=["storage", "shared"],
        refresh="1m",
    )


def board_services() -> dict:
    lay = Layout()
    ns = 'namespace=~"$service_namespace"'
    dep = 'deployment=~"$deployment"'

    lay.row("Control plane for CANFAR itself")
    lay.add(
        stat(
            "Deployments",
            targets(target(f"count(kube_deployment_spec_replicas{{{ns}, {dep}}})")),
            desc="Deployments in scope.",
            color_mode="none",
        ),
        4,
        5,
    )
    lay.add(
        stat(
            "Replicas unavailable",
            targets(
                target(
                    f"sum(kube_deployment_status_replicas_unavailable{{{ns}, {dep}}})\n"
                    f"or vector(0)"
                )
            ),
            desc="Replicas the Deployment controller wants but does not have.",
            thr=ANY_IS_BAD,
        ),
        4,
        5,
    )
    lay.add(
        stat(
            "Pods not ready",
            targets(
                target(
                    f'count(kube_pod_status_ready{{{ns}, condition="true"}} == 0)\n'
                    f"or vector(0)"
                )
            ),
            desc="Pods failing their readiness probe.",
            thr=ANY_IS_BAD,
        ),
        4,
        5,
    )
    lay.add(
        stat(
            "Restarts in range",
            targets(
                target(
                    f"sum(increase(kube_pod_container_status_restarts_total{{{ns}}}"
                    f"[$__range]))\nor vector(0)"
                )
            ),
            decimals=0,
            desc="Container restarts across the platform services.",
            thr=thresholds(("green", None), ("#EAB839", 1), ("red", 10)),
        ),
        4,
        5,
    )
    lay.add(
        stat(
            "Containers waiting",
            targets(
                target(
                    f"sum(kube_pod_container_status_waiting_reason{{{ns}, "
                    f'reason=~"CrashLoopBackOff|ImagePullBackOff|ErrImagePull|'
                    f'CreateContainerError"}})\nor vector(0)'
                )
            ),
            desc="Containers stuck in a hard-failure waiting reason.",
            thr=ANY_IS_BAD,
        ),
        4,
        5,
    )
    lay.add(
        stat(
            "Oldest pod age",
            targets(
                target(
                    f"time() - min(kube_pod_start_time{{{ns}}})\nor vector(0)"
                )
            ),
            unit="s",
            desc="Age of the longest-running platform pod: a rough deployment recency "
            "signal.",
            thr=NEUTRAL,
            color_mode="none",
        ),
        4,
        5,
    )

    lay.row("Replica health")
    lay.add(
        timeseries(
            "Deployment replicas",
            targets(
                target(
                    f"sum by (deployment) (kube_deployment_spec_replicas{{{ns}, {dep}}})",
                    legend="desired · {{deployment}}",
                ),
                target(
                    f"sum by (deployment) "
                    f"(kube_deployment_status_replicas_available{{{ns}, {dep}}})",
                    legend="available · {{deployment}}",
                ),
            ),
            desc="Desired against available. A persistent gap is a rollout that never "
            "finished.",
            legend="table",
            calcs=["lastNotNull"],
            placement="right",
            fill=0,
            draw="line",
        ),
        12,
        9,
    )
    lay.add(
        timeseries(
            "Container waiting reasons",
            targets(
                target(
                    f"sum by (reason) (kube_pod_container_status_waiting_reason{{{ns}}} > 0)",
                    legend="{{reason}}",
                )
            ),
            desc="Why containers are not starting.",
            legend="table",
            calcs=["max"],
            stack=True,
            thr=ANY_IS_BAD,
        ),
        12,
        9,
    )
    lay.add(
        timeseries(
            "CPU usage against requests",
            targets(
                target(
                    f"sum by (pod) (rate(container_cpu_usage_seconds_total"
                    f'{{{ns}, container!=""}}[$__rate_interval]))',
                    legend="{{pod}}",
                ),
                target(
                    f'sum(kube_pod_container_resource_requests{{{ns}, resource="cpu"}})',
                    legend="total requested",
                ),
            ),
            unit=CORES,
            desc="Platform service CPU burn against what it reserved.",
            legend="table",
            calcs=["mean", "max"],
            placement="right",
            overrides=[
                override(
                    "total requested",
                    ("color", {"mode": "fixed", "fixedColor": "text"}),
                    ("custom.fillOpacity", 0),
                    ("custom.lineStyle", {"fill": "dash", "dash": [10, 10]}),
                )
            ],
        ),
        12,
        9,
    )
    lay.add(
        timeseries(
            "Memory usage against limits",
            targets(
                target(
                    f"sum by (pod) (container_memory_working_set_bytes"
                    f'{{{ns}, container!=""}})',
                    legend="{{pod}}",
                ),
                target(
                    f'sum(kube_pod_container_resource_limits{{{ns}, resource="memory"}})',
                    legend="total limit",
                ),
            ),
            unit="bytes",
            desc="Platform service memory against its limit ceiling. Approaching the "
            "ceiling precedes an OOM kill.",
            legend="table",
            calcs=["mean", "max"],
            placement="right",
            overrides=[
                override(
                    "total limit",
                    ("color", {"mode": "fixed", "fixedColor": "red"}),
                    ("custom.fillOpacity", 0),
                    ("custom.lineStyle", {"fill": "dash", "dash": [10, 10]}),
                )
            ],
        ),
        12,
        9,
    )

    lay.row("Autoscaling")
    lay.add(
        timeseries(
            "HorizontalPodAutoscaler replicas",
            targets(
                target(
                    f"sum by (horizontalpodautoscaler) "
                    f"(kube_horizontalpodautoscaler_status_current_replicas{{{ns}}})",
                    legend="current · {{horizontalpodautoscaler}}",
                ),
                target(
                    f"sum by (horizontalpodautoscaler) "
                    f"(kube_horizontalpodautoscaler_status_desired_replicas{{{ns}}})",
                    legend="desired · {{horizontalpodautoscaler}}",
                ),
                target(
                    f"sum by (horizontalpodautoscaler) "
                    f"(kube_horizontalpodautoscaler_spec_max_replicas{{{ns}}})",
                    legend="max · {{horizontalpodautoscaler}}",
                ),
            ),
            desc="If current sits pinned at max, the API tier is the bottleneck.",
            legend="table",
            calcs=["lastNotNull", "max"],
            placement="right",
            fill=0,
        ),
        24,
        9,
    )

    return dashboard(
        uid="canfar-services",
        title="CANFAR · Platform Services",
        description=(
            "Health of the CANFAR control plane itself — the Skaha API, the metrics "
            "backend, and their supporting Deployments. Label-scheme independent."
        ),
        panels=lay.build(),
        variables=[
            var_datasource(),
            var_query(
                "service_namespace",
                "label_values(kube_deployment_spec_replicas, namespace)",
                label="Namespace",
                desc="Namespace holding the CANFAR platform Deployments.",
            ),
            var_query(
                "deployment",
                'label_values(kube_deployment_spec_replicas'
                '{namespace=~"$service_namespace"}, deployment)',
                label="Deployment",
            ),
        ],
        tags=["services", "shared"],
        refresh="1m",
    )


# ======================================================================================
# Emit
# ======================================================================================

BOARDS = {
    "overview": board_overview,
    "sessions": board_sessions,
    "efficiency": board_efficiency,
    "drilldown": board_drilldown,
    "queue": board_queue,
    "capacity": board_capacity,
    "storage": board_storage,
    "services": board_services,
}


def render() -> dict[str, str]:
    """Return ``filename -> file text`` for everything under ``dist/``.

    Dashboards are emitted once, with ``__CANFAR_LABEL_*__`` placeholders where
    Prometheus label names go, alongside ``labels.yaml`` mapping each placeholder to the
    real label name per generation. The Helm chart reads that map and substitutes at
    install time, which is what keeps one JSON file per dashboard in the repo.
    """
    out = {f"{name}.json": _dump(fn()) for name, fn in BOARDS.items()}
    out["labels.yaml"] = _labels_yaml()
    return out


def _labels_yaml() -> str:
    """Placeholder -> Prometheus label name, per label generation.

    Hand-editing this is pointless; it is derived from the DIMS table in lib.py via the
    kube-state-metrics name-mangling rules.
    """
    lines = [
        "# Generated by generate.py -- do not edit.",
        "#",
        "# Maps the __CANFAR_LABEL_*__ placeholders in dist/*.json to real Prometheus",
        "# label names, per CANFAR session label generation. Consumed by",
        "# helm/templates/grafana-dashboards.yaml at install time.",
    ]
    for generation in GENERATIONS:
        lines.append(f"{generation}:")
        for token, label in token_map(generation).items():
            lines.append(f"  {token}: {label}")
    return "\n".join(lines) + "\n"


def _dump(board: dict) -> str:
    return json.dumps(board, indent=2, sort_keys=False, ensure_ascii=False) + "\n"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument(
        "--check",
        action="store_true",
        help="verify dist/ matches the generator instead of writing it",
    )
    args = ap.parse_args()

    files = render()
    stale = []
    for rel, text in sorted(files.items()):
        path = DIST / rel
        if args.check:
            if not path.exists() or path.read_text(encoding="utf-8") != text:
                stale.append(rel)
            continue
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")

    existing = {
        str(p.relative_to(DIST)) for p in DIST.rglob("*") if p.is_file()
    } if DIST.exists() else set()
    orphans = sorted(existing - set(files))

    if args.check:
        if stale or orphans:
            for rel in stale:
                print(f"stale:  {rel}", file=sys.stderr)
            for rel in orphans:
                print(f"orphan: {rel}", file=sys.stderr)
            print(
                "\ndist/ is out of date; run `python3 generate.py`.", file=sys.stderr
            )
            return 1
        print(f"dist/ is up to date ({len(files) - 1} dashboards + labels.yaml).")
        return 0

    for rel in orphans:
        (DIST / rel).unlink()
        print(f"removed orphan {rel}")
    for d in sorted(DIST.rglob("*"), reverse=True):
        if d.is_dir() and not any(d.iterdir()):
            d.rmdir()
            print(f"removed empty dir {d.relative_to(DIST)}")
    for rel in sorted(files):
        print(f"wrote dist/{rel}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
