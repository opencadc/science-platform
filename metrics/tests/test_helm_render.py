"""Render contracts for the external-service Metrics Helm chart."""

from __future__ import annotations

import os
import shutil
import subprocess
from pathlib import Path
from typing import Any

import pytest
import yaml

import metrics
from metrics.core.settings import Settings
from metrics.main import configuration_errors

METRICS_ROOT = Path(__file__).parents[1]
CHART = METRICS_ROOT / "helm" / "metrics-api"


def _render(
    release: str,
    *,
    values_file: Path | None = None,
    namespace: str = "metrics",
) -> list[dict[str, Any]]:
    """Render the chart into parsed Kubernetes documents."""
    helm = shutil.which("helm")
    if helm is None:
        pytest.skip("helm is not installed")
    command = [helm, "template", release, str(CHART), "--namespace", namespace]
    if values_file is not None:
        command.extend(["--values", str(values_file)])
    result = subprocess.run(
        command,
        cwd=METRICS_ROOT,
        check=True,
        capture_output=True,
        text=True,
    )
    return [document for document in yaml.safe_load_all(result.stdout) if document]


def _render_error(
    release: str,
    *,
    values_file: Path | None = None,
    namespace: str = "metrics",
) -> str:
    """Render the chart and return Helm's error output."""
    helm = shutil.which("helm")
    if helm is None:
        pytest.skip("helm is not installed")
    command = [helm, "template", release, str(CHART), "--namespace", namespace]
    if values_file is not None:
        command.extend(["--values", str(values_file)])
    result = subprocess.run(
        command,
        cwd=METRICS_ROOT,
        check=False,
        capture_output=True,
        text=True,
    )
    assert result.returncode != 0, result.stdout
    return result.stderr


def _write_values(tmp_path: Path, values: dict[str, Any]) -> Path:
    """Write a temporary values override for one render."""
    path = tmp_path / "values.yaml"
    path.write_text(yaml.safe_dump(values), encoding="utf-8")
    return path


def _deployment(documents: list[dict[str, Any]]) -> dict[str, Any]:
    """Return the Metrics API Deployment document."""
    return next(
        document
        for document in documents
        if document.get("kind") == "Deployment"
        and document["spec"]["template"]["metadata"]["labels"].get("app.kubernetes.io/component")
        == "api"
    )


def _environment(documents: list[dict[str, Any]]) -> dict[str, dict[str, Any]]:
    """Index API container environment variables by name."""
    entries = _deployment(documents)["spec"]["template"]["spec"]["containers"][0]["env"]
    return {entry["name"]: entry for entry in entries}


def _write_complete_values(tmp_path: Path, **overrides: Any) -> Path:
    """Write the smallest complete configuration for RBAC-enabled rendering."""
    values: dict[str, Any] = {
        "clusterName": "test-cluster",
        "serviceAccount": {"create": True},
        "rbac": {"create": True},
        "kueue": {
            "clusterQueues": ["cq-astronomy", "cq-physics"],
            "namespaces": ["astro-workloads", "physics-workloads"],
        },
        "redis": {"urlSecret": {"name": "shared-redis", "key": "url"}},
        "cacheKeySecret": {"name": "metrics-cache", "key": "hmac"},
    }
    values.update(overrides)
    return _write_values(tmp_path, values)


def _write_deployable_values(tmp_path: Path, **overrides: Any) -> Path:
    """Write the smallest configuration that permits a Deployment to render."""
    values: dict[str, Any] = {
        "clusterName": "test-cluster",
        "kueue": {
            "clusterQueues": ["cq-test"],
            "namespaces": ["metrics-workloads"],
        },
        "rbac": {"create": False},
    }
    values.update(overrides)
    return _write_values(tmp_path, values)


def test_default_render_contains_only_api_resources_and_external_secret_refs(
    tmp_path: Path,
) -> None:
    """The production chart renders the API but never dependency workloads."""
    documents = _render(
        "production-boundary",
        values_file=_write_deployable_values(tmp_path),
    )
    kinds = {document["kind"] for document in documents}
    assert kinds <= {"Deployment", "NetworkPolicy", "Service"}

    for document in documents:
        labels = document.get("metadata", {}).get("labels", {})
        assert labels.get("app.kubernetes.io/component", "api") != "redis"
        assert labels.get("app.kubernetes.io/component", "api") != "collector"
        assert "accounting" not in document["metadata"]["name"].lower()

    environment = _environment(documents)
    pod = _deployment(documents)["spec"]["template"]["spec"]
    container = pod["containers"][0]
    assert container["image"] == f"images.opencadc.org/platform/metrics:v{metrics.__version__}"
    assert container["securityContext"]["readOnlyRootFilesystem"] is True
    assert container["lifecycle"]["preStop"] == {"sleep": {"seconds": 5}}
    assert {"name": "tmp", "mountPath": "/tmp"} in container["volumeMounts"]
    assert pod["terminationGracePeriodSeconds"] > 5
    assert environment["METRICS_REDIS_URL"]["valueFrom"] == {
        "secretKeyRef": {"name": "metrics-api-redis", "key": "redis-url"}
    }
    assert environment["METRICS_CACHE__KEY_SECRET"]["valueFrom"] == {
        "secretKeyRef": {"name": "metrics-api-cache", "key": "key-secret"}
    }
    assert environment["METRICS_CLUSTER_NAME"]["value"] == "test-cluster"
    assert all(
        not name.startswith("METRICS_ACCOUNTING")
        and "LIFETIME" not in name
        and not name.startswith("METRICS_USAGE")
        for name in environment
    )


def test_stale_dependency_flags_cannot_create_chart_owned_resources(tmp_path: Path) -> None:
    """Obsolete local-service values are inert and never render resources."""
    values = _write_values(
        tmp_path,
        {
            "clusterName": "test-cluster",
            "kueue": {
                "clusterQueues": ["cq-test"],
                "namespaces": ["metrics-workloads"],
            },
            "rbac": {"create": False},
            "redis": {"enabled": True},
            "collector": {"enabled": True},
            "accounting": {"enabled": True},
        },
    )
    documents = _render("obsolete-flags", values_file=values)
    assert {document["kind"] for document in documents} <= {
        "Deployment",
        "NetworkPolicy",
        "Service",
    }
    assert not any(
        document.get("metadata", {}).get("labels", {}).get("app.kubernetes.io/component")
        in {"redis", "collector", "accounting"}
        for document in documents
    )


def test_structured_values_wire_kueue_lists_optional_backends_and_secrets(tmp_path: Path) -> None:
    """Structured Helm values produce the exact runtime configuration contract."""
    values = _write_complete_values(
        tmp_path,
        promql={
            "baseUrl": "https://mimir.example/api/prom",
            "mimirTenantId": "canfar-test",
        },
        otel={"endpoint": "https://otel.example/v1"},
        env={"METRICS_CUSTOM_SETTING": "custom-value"},
    )
    documents = _render("configured-boundary", values_file=values)
    environment = _environment(documents)

    assert environment["METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES"]["value"] == (
        '["cq-astronomy","cq-physics"]'
    )
    assert environment["METRICS_PROVIDERS__KUEUE__NAMESPACES"]["value"] == (
        '["astro-workloads","physics-workloads"]'
    )
    assert environment["METRICS_PROVIDERS__PROMQL__BASE_URL"]["value"] == (
        "https://mimir.example/api/prom"
    )
    assert environment["METRICS_PROVIDERS__PROMQL__MIMIR_TENANT_ID"]["value"] == ("canfar-test")
    assert environment["METRICS_OTEL__EXPORTER_OTLP_ENDPOINT"]["value"] == (
        "https://otel.example/v1"
    )
    assert environment["METRICS_OTEL__METRICS_ENABLED"]["value"] == "true"
    assert not any(key.endswith(("__TRACES_ENABLED", "__LOGS_ENABLED")) for key in environment)
    assert environment["METRICS_CUSTOM_SETTING"]["value"] == "custom-value"
    assert environment["METRICS_REDIS_URL"]["valueFrom"] == {
        "secretKeyRef": {"name": "shared-redis", "key": "url"}
    }
    assert environment["METRICS_CACHE__KEY_SECRET"]["valueFrom"] == {
        "secretKeyRef": {"name": "metrics-cache", "key": "hmac"}
    }
    assert len(environment) == len(
        _deployment(documents)["spec"]["template"]["spec"]["containers"][0]["env"]
    )


def test_rbac_grants_kueue_and_session_workload_reads(tmp_path: Path) -> None:
    """RBAC grants Kueue reads plus session Job, Pod, and PodMetrics access."""
    documents = _render("kueue-rbac", values_file=_write_complete_values(tmp_path))
    cluster_role = next(document for document in documents if document["kind"] == "ClusterRole")
    assert cluster_role["rules"] == [
        {
            "apiGroups": ["kueue.x-k8s.io"],
            "resources": ["clusterqueues"],
            "resourceNames": ["cq-astronomy", "cq-physics"],
            "verbs": ["get"],
        }
    ]

    expected_role_rules = [
        {
            "apiGroups": ["kueue.x-k8s.io"],
            "resources": ["localqueues"],
            "verbs": ["list"],
        },
        {
            "apiGroups": ["batch"],
            "resources": ["jobs"],
            "verbs": ["get", "list"],
        },
        {
            "apiGroups": [""],
            "resources": ["pods"],
            "verbs": ["get", "list"],
        },
        {
            "apiGroups": ["metrics.k8s.io"],
            "resources": ["pods"],
            "verbs": ["get", "list"],
        },
    ]
    roles = [document for document in documents if document["kind"] == "Role"]
    assert {document["metadata"]["namespace"] for document in roles} == {
        "astro-workloads",
        "physics-workloads",
    }
    assert all(document["rules"] == expected_role_rules for document in roles)

    deployment = _deployment(documents)
    service_account = next(
        document for document in documents if document["kind"] == "ServiceAccount"
    )
    metrics_service_account = service_account["metadata"]["name"]
    assert metrics_service_account != "default"
    assert deployment["spec"]["template"]["spec"]["serviceAccountName"] == metrics_service_account
    assert all(
        subject["name"] == metrics_service_account
        for document in documents
        if document["kind"] in {"ClusterRoleBinding", "RoleBinding"}
        for subject in document["subjects"]
    )


def test_owned_rbac_requires_named_external_service_account(tmp_path: Path) -> None:
    """Owned Kueue permissions must never bind to the namespace default account."""
    values = _write_complete_values(
        tmp_path,
        serviceAccount={"create": False, "name": ""},
    )
    error = _render_error("unnamed-external-service-account", values_file=values)
    assert "serviceAccount.name is required" in error


def test_owned_rbac_uses_named_external_service_account(tmp_path: Path) -> None:
    """Owned Kueue permissions use the named existing account when creation is disabled."""
    values = _write_complete_values(
        tmp_path,
        serviceAccount={"create": False, "name": "metrics-existing"},
    )
    documents = _render("named-external-service-account", values_file=values)
    assert not any(document["kind"] == "ServiceAccount" for document in documents)
    assert _deployment(documents)["spec"]["template"]["spec"]["serviceAccountName"] == (
        "metrics-existing"
    )
    assert all(
        subject["name"] == "metrics-existing"
        for document in documents
        if document["kind"] in {"ClusterRoleBinding", "RoleBinding"}
        for subject in document["subjects"]
    )


def test_owned_rbac_rejects_default_service_account(tmp_path: Path) -> None:
    """Owned Kueue permissions must never target the namespace default account."""
    values = _write_complete_values(
        tmp_path,
        serviceAccount={"create": True, "name": "default"},
    )
    error = _render_error("default-service-account", values_file=values)
    assert "must not be default" in error


def test_deployment_requires_kueue_lists_even_without_owned_rbac(tmp_path: Path) -> None:
    """Deployment configuration is mandatory even when RBAC is operator-owned."""
    missing_cluster_queues = _write_values(
        tmp_path,
        {
            "clusterName": "test-cluster",
            "rbac": {"create": False},
            "kueue": {"clusterQueues": [], "namespaces": ["metrics-workloads"]},
        },
    )
    assert "at least one ClusterQueue" in _render_error(
        "missing-cluster-queues",
        values_file=missing_cluster_queues,
    )

    missing_namespaces = _write_values(
        tmp_path,
        {
            "clusterName": "test-cluster",
            "rbac": {"create": False},
            "kueue": {"clusterQueues": ["cq-test"], "namespaces": []},
        },
    )
    assert "at least one Kueue namespace" in _render_error(
        "missing-namespaces",
        values_file=missing_namespaces,
    )


@pytest.mark.parametrize(
    "key",
    [
        "METRICS_REDIS_URL",
        "METRICS_CACHE__KEY_SECRET",
        "METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES",
        "METRICS_PROVIDERS__PROMQL__BASE_URL",
        "METRICS_OTEL__METRICS_ENABLED",
        "METRICS_PLATFORM_NAME",
    ],
)
def test_env_cannot_set_keys_the_chart_renders(tmp_path: Path, key: str) -> None:
    """A second, possibly plaintext, source for a rendered key fails the render."""
    values = _write_deployable_values(tmp_path, env={key: "anything"})
    assert f"env.{key} is rendered from structured values" in _render_error(
        "duplicate-source", values_file=values
    )


def test_deployment_requires_a_cluster_name(tmp_path: Path) -> None:
    """Metrics validates the identity; the chart only requires one."""
    values = _write_deployable_values(tmp_path, clusterName="")
    assert "clusterName is required" in _render_error("no-cluster-name", values_file=values)


def test_duplicate_kueue_entries_render_once(tmp_path: Path) -> None:
    """RBAC must not render two objects with one name for a repeated entry."""
    values = _write_complete_values(
        tmp_path,
        kueue={"clusterQueues": ["cq-a", "cq-a"], "namespaces": ["work", "work"]},
    )
    documents = _render("duplicates", values_file=values)
    assert len([document for document in documents if document["kind"] == "Role"]) == 1
    assert _environment(documents)["METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES"]["value"] == (
        '["cq-a"]'
    )


@pytest.mark.parametrize(
    "values_path",
    [CHART / "values-dev.yaml", METRICS_ROOT / "scripts" / "kind-values.yaml"],
    ids=["values-dev", "kind-values"],
)
def test_rendered_environment_is_accepted_by_settings(
    monkeypatch: pytest.MonkeyPatch, values_path: Path
) -> None:
    """Every shipped profile renders an environment that Metrics accepts at startup."""
    references = {
        "METRICS_REDIS_URL": "redis://external.example:6379/0",
        "METRICS_CACHE__KEY_SECRET": "rendered-profile-cache-key-0123456789",
        "METRICS_OTEL__POD_UID": "pod-uid",
    }
    for name in tuple(os.environ):
        if name.startswith("METRICS_"):
            monkeypatch.delenv(name)
    environment = {
        name: entry["value"] if "value" in entry else references[name]
        for name, entry in _environment(_render("profile", values_file=values_path)).items()
    }
    for name, value in environment.items():
        monkeypatch.setenv(name, value)
    assert configuration_errors(environment) == []
    assert Settings().providers.kueue.cluster_queues


def test_optional_efficiency_and_telemetry_are_omitted_by_default(tmp_path: Path) -> None:
    """The API does not receive optional backends unless explicitly configured."""
    environment = _environment(
        _render("optional-backends", values_file=_write_deployable_values(tmp_path))
    )
    assert "METRICS_PROVIDERS__PROMQL__BASE_URL" not in environment
    assert "METRICS_PROVIDERS__PROMQL__MIMIR_TENANT_ID" not in environment
    assert "METRICS_OTEL__EXPORTER_OTLP_ENDPOINT" not in environment
    assert "METRICS_OTEL__METRICS_ENABLED" not in environment
    assert not any(key.endswith(("__TRACES_ENABLED", "__LOGS_ENABLED")) for key in environment)


def test_promql_tenant_without_endpoint_is_not_rendered(tmp_path: Path) -> None:
    """A tenant cannot activate PromQL without an explicit backend endpoint."""
    values = _write_deployable_values(
        tmp_path,
        promql={"mimirTenantId": "orphan-tenant"},
    )
    environment = _environment(_render("orphan-promql-tenant", values_file=values))
    assert "METRICS_PROVIDERS__PROMQL__BASE_URL" not in environment
    assert "METRICS_PROVIDERS__PROMQL__MIMIR_TENANT_ID" not in environment
    assert not any(name.endswith("__ENABLED") for name in environment)


def test_network_policy_contains_only_configured_external_egress(tmp_path: Path) -> None:
    """NetworkPolicy has no selector for a chart-owned dependency."""
    values = _write_values(
        tmp_path,
        {
            "clusterName": "test-cluster",
            "kueue": {
                "clusterQueues": ["cq-test"],
                "namespaces": ["metrics-workloads"],
            },
            "networkPolicy": {
                "egress": {
                    "dns": [],
                    "kubeApiServer": [
                        {
                            "to": [{"ipBlock": {"cidr": "10.96.0.0/12"}}],
                            "ports": [{"port": 443, "protocol": "TCP"}],
                        }
                    ],
                    "redis": [
                        {
                            "to": [{"ipBlock": {"cidr": "10.0.0.0/8"}}],
                            "ports": [{"port": 6379, "protocol": "TCP"}],
                        }
                    ],
                    "otlp": [],
                    "promql": [],
                }
            },
        },
    )
    policy = next(
        document
        for document in _render("external-egress", values_file=values)
        if document["kind"] == "NetworkPolicy"
    )
    rendered = str(policy["spec"]["egress"])
    assert "component: redis" not in rendered
    assert "component: collector" not in rendered
    assert "10.96.0.0/12" in rendered
    assert "10.0.0.0/8" in rendered


def test_network_policy_preserves_configured_ingress_and_optional_egress(tmp_path: Path) -> None:
    """NetworkPolicy accepts only operator-provided peers and ports."""
    values = _write_values(
        tmp_path,
        {
            "clusterName": "test-cluster",
            "kueue": {
                "clusterQueues": ["cq-test"],
                "namespaces": ["metrics-workloads"],
            },
            "rbac": {"create": False},
            "networkPolicy": {
                "ingress": [
                    {
                        "from": [
                            {
                                "namespaceSelector": {
                                    "matchLabels": {"kubernetes.io/metadata.name": "canfar"}
                                },
                                "podSelector": {"matchLabels": {"app.kubernetes.io/name": "skaha"}},
                            }
                        ],
                        "ports": [{"port": 8000, "protocol": "TCP"}],
                    }
                ],
                "egress": {
                    "dns": [
                        {
                            "to": [
                                {
                                    "namespaceSelector": {
                                        "matchLabels": {
                                            "kubernetes.io/metadata.name": "kube-system"
                                        }
                                    }
                                }
                            ],
                            "ports": [{"port": 53, "protocol": "UDP"}],
                        }
                    ],
                    "kubeApiServer": [
                        {
                            "to": [{"ipBlock": {"cidr": "10.96.0.0/12"}}],
                            "ports": [{"port": 443, "protocol": "TCP"}],
                        }
                    ],
                    "redis": [
                        {
                            "to": [{"ipBlock": {"cidr": "10.0.0.0/8"}}],
                            "ports": [{"port": 6379, "protocol": "TCP"}],
                        }
                    ],
                    "promql": [
                        {
                            "to": [{"ipBlock": {"cidr": "192.0.2.0/24"}}],
                            "ports": [{"port": 9090, "protocol": "TCP"}],
                        }
                    ],
                    "otlp": [
                        {
                            "to": [{"ipBlock": {"cidr": "198.51.100.0/24"}}],
                            "ports": [{"port": 4318, "protocol": "TCP"}],
                        }
                    ],
                },
            },
        },
    )
    policy = next(
        document
        for document in _render("explicit-network-policy", values_file=values)
        if document["kind"] == "NetworkPolicy"
    )
    assert policy["spec"]["ingress"] == [
        {
            "from": [
                {
                    "namespaceSelector": {"matchLabels": {"kubernetes.io/metadata.name": "canfar"}},
                    "podSelector": {"matchLabels": {"app.kubernetes.io/name": "skaha"}},
                }
            ],
            "ports": [{"port": 8000, "protocol": "TCP"}],
        }
    ]
    assert {
        (
            rule["to"][0].get("ipBlock", {}).get("cidr", "dns"),
            tuple((port["port"], port["protocol"]) for port in rule["ports"]),
        )
        for rule in policy["spec"]["egress"]
    } == {
        ("dns", ((53, "UDP"),)),
        ("10.96.0.0/12", ((443, "TCP"),)),
        ("10.0.0.0/8", ((6379, "TCP"),)),
        ("198.51.100.0/24", ((4318, "TCP"),)),
        ("192.0.2.0/24", ((9090, "TCP"),)),
    }


def test_network_policy_empty_rules_are_explicit_default_denies(tmp_path: Path) -> None:
    """Empty operator rule lists render as valid explicit deny lists."""
    values = _write_deployable_values(
        tmp_path,
        networkPolicy={
            "ingress": [],
            "egress": {
                "dns": [],
                "kubeApiServer": [],
                "redis": [],
                "otlp": [],
                "promql": [],
            },
        },
    )
    policy = next(
        document
        for document in _render("empty-network-policy", values_file=values)
        if document["kind"] == "NetworkPolicy"
    )
    assert policy["spec"]["ingress"] == []
    assert policy["spec"]["egress"] == []


def test_long_release_keeps_all_resource_names_within_dns_limit(tmp_path: Path) -> None:
    """Long release names remain valid for API and RBAC resources."""
    documents = _render(
        "release-" + "x" * 45,
        values_file=_write_complete_values(tmp_path),
        namespace="namespace-" + "n" * 53,
    )
    names = [
        document["metadata"]["name"]
        for document in documents
        if document.get("metadata", {}).get("name")
    ]
    assert names
    assert all(len(name) <= 63 for name in names)
    expected_namespaces = {None, "astro-workloads", "physics-workloads"}
    assert all(
        document["metadata"].get("namespace") in expected_namespaces for document in documents
    )


def test_chart_versions_follow_the_package_release() -> None:
    """release-please keeps Chart.yaml in step, so the default image is this release."""
    chart = yaml.safe_load((CHART / "Chart.yaml").read_text())
    assert chart["appVersion"] == metrics.__version__
    assert chart["version"] == metrics.__version__
