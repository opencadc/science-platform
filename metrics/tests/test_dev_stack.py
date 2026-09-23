"""Safety and fixture-contract tests for the disposable kind lifecycle."""

from __future__ import annotations

import copy
import json
import subprocess
import time
import urllib.parse
from pathlib import Path

import pytest
import yaml

from metrics.dev import fixtures, stack
from metrics.dev.cli import build_parser

METRICS_ROOT = Path(__file__).parents[1]


def _safe_output(command: list[str]) -> str:
    """Return the approved identity for guard commands."""
    if command == ["kubectl", "config", "current-context"]:
        return stack.KUBE_CONTEXT
    if command == ["kind", "get", "clusters"]:
        return stack.KIND_CLUSTER
    if "config" in command and "view" in command:
        return stack.KUBE_CONTEXT
    if "get" in command and "nodes" in command:
        return stack.CONTROL_PLANE
    raise AssertionError(command)


def _write_documents(path: Path, documents: list[dict[str, object]]) -> None:
    """Write test YAML documents without mutating the checked-in fixture."""
    path.write_text(
        "---\n".join(yaml.safe_dump(document, sort_keys=False) for document in documents),
        encoding="utf-8",
    )


def _load_documents(path: Path) -> list[dict[str, object]]:
    """Load fixture documents for contract-focused tests."""
    return [
        document
        for document in yaml.safe_load_all(path.read_text(encoding="utf-8"))
        if document is not None
    ]


def test_context_guard_accepts_only_the_metrics_kind_identity(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(stack, "_output", _safe_output)
    stack.assert_safe_context()


@pytest.mark.parametrize(
    ("command_key", "unsafe_value"),
    [
        ("current", "keel-prod"),
        ("cluster", "keel-prod"),
        ("node", "keel-prod-control-plane"),
    ],
)
def test_context_guard_fails_closed_for_unrelated_targets(
    monkeypatch: pytest.MonkeyPatch, command_key: str, unsafe_value: str
) -> None:
    def unsafe_output(command: list[str]) -> str:
        if command == ["kubectl", "config", "current-context"] and command_key == "current":
            return unsafe_value
        if "config" in command and "view" in command and command_key == "cluster":
            return unsafe_value
        if "get" in command and "nodes" in command and command_key == "node":
            return unsafe_value
        return _safe_output(command)

    monkeypatch.setattr(stack, "_output", unsafe_output)
    with pytest.raises(stack.DevStackError, match="refusing"):
        stack.assert_safe_context()


def test_destroy_requires_confirmation_before_exact_delete(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    calls: list[list[str]] = []
    monkeypatch.setattr(stack, "assert_safe_context", lambda: None)

    def record(command: list[str], **_kwargs: object) -> subprocess.CompletedProcess[str]:
        calls.append(list(command))
        return subprocess.CompletedProcess(command, 0, "", "")

    monkeypatch.setattr(stack, "_run", record)
    with pytest.raises(stack.DevStackError, match="--confirm kind-metrics"):
        stack.destroy(None)
    assert calls == []

    stack.destroy(stack.KUBE_CONTEXT)
    assert calls == [["kind", "delete", "cluster", "--name", stack.KIND_CLUSTER]]


def test_local_stack_versions_and_scope_are_pinned() -> None:
    assert stack.KIND_VERSION == "0.32.0"
    assert stack.KIND_NODE_IMAGE.endswith(
        "sha256:3f5c8443c620245e4d355cfe09e96a91ead32ceaa569d3f1ca9edf0cb2fe2ff4"
    )
    assert stack.KUEUE_VERSION == "0.19.2"
    assert stack.CLUSTER_QUEUES == ("cq-proton", "cq-electron", "cq-fair")
    assert stack.WORKLOAD_NAMESPACES == (
        "canfar-workloads",
        "canfar-workloads-secondary",
    )


def test_metrics_dev_has_one_supported_lifecycle() -> None:
    parser = build_parser()
    assert not hasattr(parser.parse_args(["up"]), "profile")
    assert not hasattr(parser.parse_args(["smoke"]), "profile")
    with pytest.raises(SystemExit):
        parser.parse_args(["up", "--unsupported"])


def test_deploy_only_uses_the_metrics_chart_and_no_owned_dependencies(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    calls: list[tuple[str, ...]] = []
    monkeypatch.setattr(stack, "_helm", lambda *args: calls.append(args))

    stack._deploy("metrics", "test")

    assert len(calls) == 1
    command = calls[0]
    assert str(stack.CHART) in command
    assert "producer" not in " ".join(command)
    assert "redis.enabled=true" not in command
    assert "collector.enabled=true" not in command


def test_up_converges_test_dependencies_before_api(monkeypatch: pytest.MonkeyPatch) -> None:
    events: list[str] = []
    monkeypatch.setattr(stack, "_ensure_cluster", lambda: events.append("cluster"))
    monkeypatch.setattr(
        stack,
        "_cleanup_retired_accounting_profile",
        lambda: events.append("cleanup"),
    )
    monkeypatch.setattr(stack, "_install_kueue", lambda: events.append("kueue"))
    monkeypatch.setattr(stack, "test_dependencies", lambda: events.append("dependencies"))
    monkeypatch.setattr(stack, "fixtures", lambda: events.append("fixtures"))
    monkeypatch.setattr(
        stack,
        "_build_and_load_image",
        lambda: events.append("build") or ("canfar-metrics-local", "dev-test"),
    )
    monkeypatch.setattr(
        stack,
        "_deploy",
        lambda repository, tag: events.append(f"deploy:{repository}:{tag}"),
    )

    stack.up()

    assert events == [
        "cluster",
        "cleanup",
        "kueue",
        "dependencies",
        "fixtures",
        "build",
        "deploy:canfar-metrics-local:dev-test",
    ]


def test_image_cleans_retired_accounting_profile_before_deploy(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    events: list[str] = []
    monkeypatch.setattr(
        stack,
        "_build_and_load_image",
        lambda: events.append("build") or ("canfar-metrics-local", "dev-test"),
    )
    monkeypatch.setattr(
        stack,
        "_cleanup_retired_accounting_profile",
        lambda: events.append("cleanup"),
    )
    monkeypatch.setattr(
        stack,
        "_deploy",
        lambda repository, tag: events.append(f"deploy:{repository}:{tag}"),
    )

    stack.image()

    assert events == [
        "build",
        "cleanup",
        "deploy:canfar-metrics-local:dev-test",
    ]


def test_retired_accounting_cleanup_is_bounded_and_idempotent(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    calls: list[tuple[str, ...]] = []
    monkeypatch.setattr(stack, "assert_safe_context", lambda: calls.append(("safe",)))
    monkeypatch.setattr(stack, "_kubectl", lambda *args: calls.append(args))

    stack._cleanup_retired_accounting_profile()

    expected = [
        (
            "--namespace",
            stack.METRICS_NAMESPACE,
            "delete",
            "deployment,service,configmap,serviceaccount",
            "-l",
            "metrics.canfar.net/profile=accounting",
            "--ignore-not-found",
            "--wait",
        ),
        *(
            (
                "--namespace",
                namespace,
                "delete",
                "role,rolebinding",
                "-l",
                "metrics.canfar.net/profile=accounting",
                "--ignore-not-found",
            )
            for namespace in stack.WORKLOAD_NAMESPACES
        ),
        (
            "delete",
            "clusterrole,clusterrolebinding",
            "-l",
            "metrics.canfar.net/profile=accounting",
            "--ignore-not-found",
        ),
    ]
    first_run = calls.copy()
    assert first_run == [("safe",), *expected]

    stack._cleanup_retired_accounting_profile()

    assert calls == first_run + first_run


def test_up_cleans_retired_accounting_profile_before_deploy(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    events: list[str] = []
    monkeypatch.setattr(stack, "_ensure_cluster", lambda: events.append("cluster"))
    monkeypatch.setattr(
        stack,
        "_cleanup_retired_accounting_profile",
        lambda: events.append("cleanup"),
        raising=False,
    )
    monkeypatch.setattr(stack, "_install_kueue", lambda: events.append("kueue"))
    monkeypatch.setattr(stack, "test_dependencies", lambda: events.append("dependencies"))
    monkeypatch.setattr(stack, "fixtures", lambda: events.append("fixtures"))
    monkeypatch.setattr(
        stack,
        "_build_and_load_image",
        lambda: events.append("build") or ("canfar-metrics-local", "dev-test"),
    )
    monkeypatch.setattr(
        stack,
        "_deploy",
        lambda repository, tag: events.append(f"deploy:{repository}:{tag}"),
    )

    stack.up()

    assert events == [
        "cluster",
        "cleanup",
        "kueue",
        "dependencies",
        "fixtures",
        "build",
        "deploy:canfar-metrics-local:dev-test",
    ]


def test_down_cleans_retired_accounting_profile(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    events: list[str] = []
    monkeypatch.setattr(stack, "assert_safe_context", lambda: events.append("safe"))
    monkeypatch.setattr(
        stack,
        "_cleanup_retired_accounting_profile",
        lambda: events.append("cleanup"),
        raising=False,
    )
    monkeypatch.setattr(
        stack,
        "_run",
        lambda command, **_kwargs: (
            events.append(f"run:{command[0]}") or subprocess.CompletedProcess(command, 0, "", "")
        ),
    )
    monkeypatch.setattr(stack, "_kubectl", lambda *args: events.append(f"kubectl:{args[0]}"))

    stack.down()

    assert events == ["safe", "run:helm", "cleanup", "kubectl:delete"]


def test_reset_reapplies_fixtures_without_deleting_namespaces_or_rbac(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    events: list[str] = []
    monkeypatch.setattr(stack, "assert_safe_context", lambda: events.append("safe"))
    monkeypatch.setattr(stack, "fixtures", lambda: events.append("fixtures"))
    monkeypatch.setattr(
        stack,
        "_kubectl",
        lambda *_args: pytest.fail("reset must delegate bounded pruning to fixtures"),
    )

    stack.reset()

    assert events == ["safe", "fixtures"]


def test_run_host_uses_disposable_redis_and_required_runtime_settings(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    port_forward_commands: list[list[str]] = []
    run_commands: list[list[str]] = []
    run_env: dict[str, str] = {}

    class FakeForward:
        def terminate(self) -> None:
            return None

        def wait(self, timeout: int) -> None:
            return None

    def fake_popen(command: list[str], **_kwargs: object) -> FakeForward:
        port_forward_commands.append(command)
        return FakeForward()

    def record_run(
        command: list[str], *, env: dict[str, str] | None = None, **_kwargs: object
    ) -> subprocess.CompletedProcess[str]:
        assert env is not None
        run_commands.append(command)
        run_env.update(env)
        return subprocess.CompletedProcess(command, 0, "", "")

    monkeypatch.delenv("METRICS_CACHE__BACKEND", raising=False)
    monkeypatch.setattr(stack, "assert_safe_context", lambda: None)
    monkeypatch.setattr(stack.subprocess, "Popen", fake_popen)
    monkeypatch.setattr(stack, "_output", lambda _command: "apiVersion: v1\n")
    monkeypatch.setattr(stack, "_wait_for_tcp_port", lambda *_args, **_kwargs: None)
    monkeypatch.setattr(stack, "_run", record_run)

    stack.run_host()

    assert port_forward_commands == [
        [
            "kubectl",
            "--context",
            stack.KUBE_CONTEXT,
            "--namespace",
            stack.METRICS_NAMESPACE,
            "port-forward",
            "service/metrics-test-redis",
            "16379:6379",
        ]
    ]
    assert run_commands == [["uv", "run", "python", "-m", "metrics.main"]]
    assert run_env["METRICS_REDIS_URL"] == "redis://127.0.0.1:16379/0"
    assert run_env["METRICS_CACHE__KEY_SECRET"] == "metrics-dev-ephemeral-cache-key!!"
    assert run_env["METRICS_CLUSTER_NAME"] == stack.KUBE_CONTEXT
    assert run_env["METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES"] == (
        '["cq-proton","cq-electron","cq-fair"]'
    )
    assert run_env["METRICS_PROVIDERS__KUEUE__NAMESPACES"] == (
        '["canfar-workloads","canfar-workloads-secondary"]'
    )
    assert "METRICS_CACHE__BACKEND" not in run_env


def test_prometheus_readiness_uses_exact_platform_query_and_validates_cpu_memory(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    query_calls: list[dict[str, object]] = []
    requests: list[object] = []
    timestamp = str(time.time())
    payload = {
        "status": "success",
        "data": {
            "resultType": "vector",
            "result": [
                {"metric": {"resource": "cpu"}, "value": [timestamp, "0.5"]},
                {"metric": {"resource": "memory"}, "value": [timestamp, "0.25"]},
            ],
        },
    }

    class FakeResponse:
        status = 200

        def __enter__(self) -> "FakeResponse":
            return self

        def __exit__(self, *_args: object) -> None:
            return None

        def read(self) -> bytes:
            return json.dumps(payload).encode()

    class FakeProcess:
        def poll(self) -> None:
            return None

    def build_query(**kwargs: object) -> str:
        query_calls.append(kwargs)
        return "platform-query"

    def open_request(request: object, **_kwargs: object) -> FakeResponse:
        requests.append(request)
        return FakeResponse()

    monkeypatch.setattr(stack, "_query", build_query, raising=False)
    monkeypatch.setattr(stack.urllib.request, "urlopen", open_request)

    stack._wait_for_prometheus_readiness(FakeProcess(), "19090")

    assert query_calls == [
        {
            "scope": "platform",
            "subject": None,
            "cluster": stack.KUBE_CONTEXT,
            "namespaces": list(stack.WORKLOAD_NAMESPACES),
        }
    ]
    request = requests[0]
    assert request.get_method() == "POST"
    assert request.full_url == "http://127.0.0.1:19090/api/v1/query"
    assert urllib.parse.parse_qs(request.data.decode()) == {"query": ["platform-query"]}


def test_prometheus_readiness_rejects_a_partial_resource_vector(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    timestamp = str(time.time())
    payload = {
        "status": "success",
        "data": {
            "resultType": "vector",
            "result": [{"metric": {"resource": "cpu"}, "value": [timestamp, "0.5"]}],
        },
    }

    class FakeProcess:
        def poll(self) -> None:
            return None

    class FakeResponse:
        status = 200

        def __enter__(self) -> "FakeResponse":
            return self

        def __exit__(self, *_args: object) -> None:
            return None

        def read(self) -> bytes:
            return json.dumps(payload).encode()

    clock = iter((0.0, 0.0, 0.5, 1.0))
    monkeypatch.setattr(stack.time, "monotonic", lambda: next(clock))
    monkeypatch.setattr(stack.time, "sleep", lambda _seconds: None)
    monkeypatch.setattr(stack, "_PROMETHEUS_READY_DEADLINE_SECONDS", 1.0)
    monkeypatch.setattr(stack.urllib.request, "urlopen", lambda *_args, **_kwargs: FakeResponse())

    with pytest.raises(stack.DevStackError, match="valid CPU and memory platform vector"):
        stack._wait_for_prometheus_readiness(FakeProcess(), "19090")


def test_smoke_stops_started_forwards_if_a_later_forward_fails(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    started: list[object] = []

    class FakeForward:
        def __init__(self) -> None:
            self.events: list[str] = []

        def terminate(self) -> None:
            self.events.append("terminate")

        def wait(self, timeout: int) -> None:
            self.events.append(f"wait:{timeout}")

        def kill(self) -> None:
            self.events.append("kill")

    def fake_popen(_command: list[str], **_kwargs: object) -> FakeForward:
        if len(started) == 2:
            raise OSError("port-forward failed to start")
        forward = FakeForward()
        started.append(forward)
        return forward

    monkeypatch.setattr(stack, "assert_safe_context", lambda: None)
    monkeypatch.setattr(stack, "fixtures", lambda: None)
    monkeypatch.setattr(stack.subprocess, "Popen", fake_popen)
    monkeypatch.setattr(stack, "_wait_for_prometheus_readiness", lambda *_args: None)
    monkeypatch.setattr(stack, "_restart_metrics_deployment", lambda: None)

    with pytest.raises(OSError, match="port-forward failed"):
        stack.smoke()

    assert [forward.events for forward in started] == [
        ["terminate", "wait:5"],
        ["terminate", "wait:5"],
    ]


def test_smoke_stops_every_forward_when_prometheus_readiness_fails(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    events: list[str] = []

    class FakeForward:
        def __init__(self, name: str) -> None:
            self.name = name

        def terminate(self) -> None:
            events.append(f"terminate:{self.name}")

        def wait(self, timeout: int) -> None:
            events.append(f"wait:{self.name}:{timeout}")

    def fake_popen(command: list[str], **_kwargs: object) -> FakeForward:
        forward = FakeForward(command[-1].rsplit(":", 1)[-1])
        events.append(f"start:{command[-1]}")
        return forward

    monkeypatch.setattr(stack, "assert_safe_context", lambda: None)
    monkeypatch.setattr(stack, "fixtures", lambda: events.append("fixtures"))
    monkeypatch.setattr(stack.subprocess, "Popen", fake_popen)
    monkeypatch.setattr(
        stack,
        "_wait_for_prometheus_readiness",
        lambda *_args: (_ for _ in ()).throw(stack.DevStackError("Prometheus not ready")),
    )

    with pytest.raises(stack.DevStackError, match="Prometheus not ready"):
        stack.smoke()

    assert events[0] == "fixtures"
    assert events[1:] == [
        "start:19090:9090",
        "terminate:9090",
        "wait:9090:5",
    ]


def test_smoke_checks_prometheus_before_restart_and_integration(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    events: list[str] = []

    class FakeForward:
        def terminate(self) -> None:
            events.append("terminate")

        def wait(self, timeout: int) -> None:
            events.append(f"wait:{timeout}")

    def fake_popen(command: list[str], **_kwargs: object) -> FakeForward:
        events.append(f"start:{command[-1]}")
        return FakeForward()

    monkeypatch.setattr(stack, "assert_safe_context", lambda: None)
    monkeypatch.setattr(stack, "fixtures", lambda: events.append("fixtures"))
    monkeypatch.setattr(stack.subprocess, "Popen", fake_popen)
    monkeypatch.setattr(
        stack, "_wait_for_prometheus_readiness", lambda *_args: events.append("prom")
    )
    monkeypatch.setattr(stack, "_restart_metrics_deployment", lambda: events.append("restart"))
    monkeypatch.setattr(stack, "_wait_for_http_health", lambda *_args: events.append("health"))
    monkeypatch.setattr(
        stack, "_wait_for_tcp_port", lambda *_args, **_kwargs: events.append("redis")
    )
    monkeypatch.setattr(
        stack, "_run_smoke_integration", lambda *_args: events.append("integration")
    )

    stack.smoke()

    assert events == [
        "fixtures",
        "start:19090:9090",
        "prom",
        "terminate",
        "wait:5",
        "restart",
        "start:18080:8000",
        "start:16380:6379",
        "health",
        "redis",
        "integration",
        "terminate",
        "terminate",
        "wait:5",
        "wait:5",
    ]


@pytest.mark.parametrize(
    "repository",
    [
        "",
        " ",
        "registry.example.com/team/metrics@sha256:deadbeef",
        "registry.example.com/team/#metrics",
        "registry.example.com/team/metrics\nnext",
        "Registry.example.com/team/metrics",
        "registry.example.com:bad/team/metrics",
        "registry.example.com:65536/team/metrics",
        "registry..example.com/team/metrics",
        "registry.example.com/team//metrics",
    ],
)
def test_image_repository_validation_rejects_unsafe_references(repository: str) -> None:
    with pytest.raises(stack.DevStackError, match="METRICS_IMAGE_REPOSITORY"):
        stack._validate_image_reference(repository, "ci-image")


@pytest.mark.parametrize(
    "tag",
    [
        "",
        " ",
        "latest",
        "LATEST",
        "tag/branch",
        "tag:1",
        "tag@sha256:deadbeef",
        "tag#comment",
        "tag\nnext",
        "a" * 129,
        ".tag",
        "-tag",
    ],
)
def test_image_tag_validation_rejects_unsafe_references(tag: str) -> None:
    with pytest.raises(stack.DevStackError, match="METRICS_IMAGE_TAG"):
        stack._validate_image_reference("registry.example.com:5000/team/metrics", tag)


def test_image_reference_validation_keeps_local_and_ci_names() -> None:
    for repository, tag in (
        ("canfar-metrics-local", "dev-20260825"),
        ("registry.example.com:5000/team/metrics", "ci-image.1"),
        ("registry.gitlab.com/canfar/metrics", "sha-abc_123"),
    ):
        assert stack._validate_image_reference(repository, tag) == (repository, tag)


def test_invalid_image_environment_fails_before_context_or_build(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("METRICS_IMAGE_REPOSITORY", "registry.example.com/team/metrics#bad")
    monkeypatch.setenv("METRICS_IMAGE_TAG", "ci-image")
    monkeypatch.setattr(
        stack,
        "assert_safe_context",
        lambda: pytest.fail("unsafe image input must be rejected before context checks"),
    )
    with pytest.raises(stack.DevStackError, match="METRICS_IMAGE_REPOSITORY"):
        stack._build_and_load_image()


def test_fixture_metadata_contract_is_valid() -> None:
    fixtures.validate_fixture_metadata(stack.FIXTURES, stack.WORKLOAD_FIXTURES)


def test_fixture_metadata_allows_distinct_local_queues_with_same_labels(tmp_path: Path) -> None:
    topology = _load_documents(stack.FIXTURES)
    duplicate = next(
        copy.deepcopy(document)
        for document in topology
        if document.get("kind") == "LocalQueue"
        and document.get("metadata", {}).get("name") == "lq-bob-physics"
    )
    duplicate["metadata"]["name"] = "lq-bob-physics-duplicate"
    duplicate["metadata"]["namespace"] = "canfar-workloads-secondary"
    topology.append(duplicate)
    topology_path = tmp_path / "topology.yaml"
    _write_documents(topology_path, topology)

    fixtures.validate_fixture_metadata(topology_path, stack.WORKLOAD_FIXTURES)


def test_fixture_metadata_rejects_local_queue_community_mismatch(tmp_path: Path) -> None:
    topology = _load_documents(stack.FIXTURES)
    queue = next(
        document
        for document in topology
        if document.get("kind") == "LocalQueue"
        and document.get("metadata", {}).get("name") == "lq-bob-physics"
    )
    queue["metadata"]["labels"]["canfar.net/community"] = "astronomy"
    topology_path = tmp_path / "topology.yaml"
    _write_documents(topology_path, topology)

    with pytest.raises(stack.DevStackError, match="community does not match"):
        fixtures.validate_fixture_metadata(topology_path, stack.WORKLOAD_FIXTURES)


def test_fixture_metadata_rejects_pod_label_mismatch(tmp_path: Path) -> None:
    workloads = _load_documents(stack.WORKLOAD_FIXTURES)
    job = next(document for document in workloads if document.get("kind") == "Job")
    job["spec"]["template"]["metadata"]["labels"] = copy.deepcopy(
        job["spec"]["template"]["metadata"]["labels"]
    )
    job["spec"]["template"]["metadata"]["labels"]["canfar.net/community"] = "physics"
    workloads_path = tmp_path / "workloads.yaml"
    _write_documents(workloads_path, workloads)

    with pytest.raises(stack.DevStackError, match="Pod template .* community"):
        fixtures.validate_fixture_metadata(stack.FIXTURES, workloads_path)


def test_fixture_metadata_rejects_unknown_local_queue(tmp_path: Path) -> None:
    workloads = _load_documents(stack.WORKLOAD_FIXTURES)
    job = next(document for document in workloads if document.get("kind") == "Job")
    job["metadata"]["labels"]["kueue.x-k8s.io/queue-name"] = "missing-queue"
    workloads_path = tmp_path / "workloads.yaml"
    _write_documents(workloads_path, workloads)

    with pytest.raises(stack.DevStackError, match="references unknown LocalQueue"):
        fixtures.validate_fixture_metadata(stack.FIXTURES, workloads_path)


def test_test_dependencies_are_disposable_only() -> None:
    documents = _load_documents(stack.TEST_DEPENDENCIES)
    resource_names = {
        document.get("metadata", {}).get("name")
        for document in documents
        if isinstance(document.get("metadata"), dict)
    }
    assert "metrics-test-redis" in resource_names
    assert "metrics-test-prometheus" in resource_names
    assert "metrics-test-otel-collector" in resource_names
    assert all(
        document.get("metadata", {}).get("labels", {}).get("metrics.canfar.net/test-only") == "true"
        for document in documents
        if isinstance(document.get("metadata"), dict)
    )
    assert all(
        str(name) == "metrics" or str(name).startswith("metrics-test") for name in resource_names
    )


def test_kind_values_reference_external_test_services() -> None:
    values = yaml.safe_load((METRICS_ROOT / "scripts" / "kind-values.yaml").read_text())
    env = values["env"]
    assert env["METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES"] == (
        '["cq-proton","cq-electron","cq-fair"]'
    )
    assert env["METRICS_PROVIDERS__KUEUE__NAMESPACES"] == (
        '["canfar-workloads","canfar-workloads-secondary"]'
    )
    assert not any(name.endswith("__ENABLED") for name in env)
    assert values["redis"]["urlSecret"]["name"] == "metrics-test-redis"
    assert values["cacheKeySecret"]["name"] == "metrics-test-cache-key"
    assert "metrics-test-prometheus" in env["METRICS_PROVIDERS__PROMQL__BASE_URL"]
    assert "metrics-test-otel-collector" in env["METRICS_OTEL__EXPORTER_OTLP_ENDPOINT"]


def test_kind_smoke_wrapper_uses_the_single_lifecycle() -> None:
    script = (METRICS_ROOT / "scripts" / "kind-smoke.sh").read_text(encoding="utf-8")
    assert "uv run metrics-dev up" in script
    assert "exec uv run metrics-dev smoke" in script
    assert "profile" not in script


def test_dev_setup_documents_retired_accounting_profile_migration() -> None:
    documentation = (METRICS_ROOT / "docs" / "dev-setup.md").read_text(encoding="utf-8")
    section = documentation.split("## Retired accounting profile migration", 1)[1].split("## ", 1)[
        0
    ]
    normalized = " ".join(section.split())
    assert "lack Helm ownership/release tracking" in section
    assert (
        "The Role/RoleBinding inspect/delete pair must be repeated for every "
        "`METRICS_PROVIDERS__KUEUE__NAMESPACES` entry; `canfar-workloads` and "
        "`canfar-workloads-secondary` are only the disposable fixture's configured examples."
    ) in normalized
    commands = (
        "kubectl --context kind-metrics --namespace metrics get deployment,service,configmap,serviceaccount -l metrics.canfar.net/profile=accounting -o yaml",
        "kubectl --context kind-metrics --namespace metrics delete deployment,service,configmap,serviceaccount -l metrics.canfar.net/profile=accounting --ignore-not-found --wait",
        "kubectl --context kind-metrics --namespace canfar-workloads get role,rolebinding -l metrics.canfar.net/profile=accounting -o yaml",
        "kubectl --context kind-metrics --namespace canfar-workloads delete role,rolebinding -l metrics.canfar.net/profile=accounting --ignore-not-found",
        "kubectl --context kind-metrics --namespace canfar-workloads-secondary get role,rolebinding -l metrics.canfar.net/profile=accounting -o yaml",
        "kubectl --context kind-metrics --namespace canfar-workloads-secondary delete role,rolebinding -l metrics.canfar.net/profile=accounting --ignore-not-found",
        "kubectl --context kind-metrics get clusterrole,clusterrolebinding -l metrics.canfar.net/profile=accounting -o yaml",
        "kubectl --context kind-metrics delete clusterrole,clusterrolebinding -l metrics.canfar.net/profile=accounting --ignore-not-found",
    )
    assert all(command in section for command in commands)
    delete_lines = [line for line in section.splitlines() if " delete " in line]
    assert all(
        "-A" not in line and "--all" not in line and "*" not in line for line in delete_lines
    )
