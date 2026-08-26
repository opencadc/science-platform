"""Render contracts for the external-service Metrics Helm chart."""

from __future__ import annotations

import shutil
import subprocess
from pathlib import Path
from typing import Any

import pytest
import yaml

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


def test_chart_metadata_describes_api_only_external_services() -> None:
    """The chart metadata must not advertise chart-owned dependencies."""
    chart = yaml.safe_load((CHART / "Chart.yaml").read_text(encoding="utf-8"))
    assert chart["description"] == (
        "CANFAR Metrics API chart for operator-configured deployments with external services"
    )


def test_dev_values_define_complete_external_fixture_configuration() -> None:
    """The disposable profile names every queue, namespace, and external secret."""
    values = yaml.safe_load((CHART / "values-dev.yaml").read_text(encoding="utf-8"))
    assert values["clusterName"] == "dev-cluster"
    assert values["kueue"]["clusterQueues"] == ["cq-proton", "cq-electron"]
    assert values["kueue"]["namespaces"] == ["canfar-workloads"]
    assert values["redis"]["urlSecret"] == {
        "name": "metrics-dev-redis",
        "key": "redis-url",
    }
    assert values["cacheKeySecret"] == {"name": "metrics-dev-cache", "key": "key-secret"}
    assert "collector" not in values
    assert "accounting" not in values
    assert "METRICS_OTEL__METRICS_ENABLED" not in values["env"]
    assert "METRICS_CLUSTER_NAME" not in values["env"]
    assert "METRICS_CACHE__BACKEND" not in values["env"]
    assert not any(key.endswith(("__TRACES_ENABLED", "__LOGS_ENABLED")) for key in values["env"])


def test_values_do_not_expose_obsolete_cache_backend_selector() -> None:
    """Redis Secret references are the sole cache deployment contract."""
    values = yaml.safe_load((CHART / "values.yaml").read_text(encoding="utf-8"))
    assert "cacheBackend" not in values


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
    assert _deployment(documents)["spec"]["template"]["spec"]["containers"][0]["image"] == (
        "images.opencadc.org/platform/metrics:v0.1.5"
    )
    assert environment["METRICS_REDIS_URL"]["valueFrom"] == {
        "secretKeyRef": {"name": "metrics-api-redis", "key": "redis-url"}
    }
    assert environment["METRICS_CACHE__KEY_SECRET"]["valueFrom"] == {
        "secretKeyRef": {"name": "metrics-api-cache", "key": "key-secret"}
    }
    assert environment["METRICS_CLUSTER_NAME"]["value"] == "test-cluster"
    assert "METRICS_CACHE__BACKEND" not in environment
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


def test_env_json_lists_are_supported_when_structured_lists_are_empty(tmp_path: Path) -> None:
    """Operators can provide the approved JSON list settings through env values."""
    values = _write_values(
        tmp_path,
        {
            "clusterName": "env-cluster",
            "serviceAccount": {"create": True},
            "rbac": {"create": True},
            "env": {
                "METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES": '["cq-env"]',
                "METRICS_PROVIDERS__KUEUE__NAMESPACES": '["env-workloads"]',
                "METRICS_PROVIDERS__PROMQL__BASE_URL": "http://prometheus.metrics:9090",
                "METRICS_PROVIDERS__PROMQL__MIMIR_TENANT_ID": "env-tenant",
                "METRICS_OTEL__EXPORTER_OTLP_ENDPOINT": "http://otel.metrics:4318",
            },
        },
    )
    environment = _environment(_render("env-boundary", values_file=values))
    assert environment["METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES"]["value"] == '["cq-env"]'
    assert environment["METRICS_PROVIDERS__KUEUE__NAMESPACES"]["value"] == '["env-workloads"]'
    assert environment["METRICS_PROVIDERS__PROMQL__BASE_URL"]["value"] == (
        "http://prometheus.metrics:9090"
    )
    assert environment["METRICS_PROVIDERS__PROMQL__MIMIR_TENANT_ID"]["value"] == "env-tenant"
    assert environment["METRICS_OTEL__EXPORTER_OTLP_ENDPOINT"]["value"] == (
        "http://otel.metrics:4318"
    )
    assert environment["METRICS_OTEL__METRICS_ENABLED"]["value"] == "true"


def test_rbac_is_limited_to_configured_clusterqueues_and_localqueues(tmp_path: Path) -> None:
    """RBAC grants only Kueue reads and never Pod access."""
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

    roles = [document for document in documents if document["kind"] == "Role"]
    assert {document["metadata"]["namespace"] for document in roles} == {
        "astro-workloads",
        "physics-workloads",
    }
    assert all(
        document["rules"]
        == [
            {
                "apiGroups": ["kueue.x-k8s.io"],
                "resources": ["localqueues"],
                "verbs": ["list"],
            }
        ]
        for document in roles
    )
    assert not any("pods" in str(document.get("rules", [])).lower() for document in documents)

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
    "override",
    [
        {"clusterName": "unknown"},
        {"clusterName": "Cluster-A"},
        {"clusterName": "cluster_a"},
    ],
)
def test_cluster_identity_is_a_real_lowercase_dns_name(
    tmp_path: Path,
    override: dict[str, Any],
) -> None:
    """Deployment identity rejects the unknown sentinel and non-DNS names."""
    error = _render_error(
        "invalid-cluster-identity",
        values_file=_write_deployable_values(tmp_path, **override),
    )
    assert "lower-case DNS cluster identity" in error


def test_obsolete_cache_backend_env_is_stripped_for_legacy_redis(tmp_path: Path) -> None:
    """The legacy Redis value may transition without reaching CacheConfig."""
    values = _write_deployable_values(
        tmp_path,
        env={"METRICS_CACHE__BACKEND": "redis"},
    )
    environment = _environment(_render("legacy-redis-cache", values_file=values))
    assert "METRICS_CACHE__BACKEND" not in environment


def test_obsolete_cache_backend_env_rejects_other_values(tmp_path: Path) -> None:
    """An obsolete key cannot silently select a different cache implementation."""
    values = _write_deployable_values(
        tmp_path,
        env={"METRICS_CACHE__BACKEND": "memory"},
    )
    assert "must be exactly redis" in _render_error("memory-cache", values_file=values)


def test_rbac_requires_complete_kueue_configuration(tmp_path: Path) -> None:
    """The chart must reject an incomplete fixture/configuration contract."""
    values = _write_values(
        tmp_path,
        {
            "clusterName": "test-cluster",
            "serviceAccount": {"create": True},
            "rbac": {"create": True},
            "kueue": {"clusterQueues": ["cq-only"], "namespaces": []},
        },
    )
    error = _render_error("incomplete-kueue", values_file=values)
    assert "Kueue namespace" in error


def test_structured_and_env_kueue_configuration_must_match(tmp_path: Path) -> None:
    """Duplicate configuration surfaces cannot silently describe different fixtures."""
    values = _write_values(
        tmp_path,
        {
            "clusterName": "test-cluster",
            "rbac": {"create": True},
            "kueue": {"clusterQueues": ["cq-one"], "namespaces": ["workloads"]},
            "env": {
                "METRICS_PROVIDERS__KUEUE__CLUSTER_QUEUES": '["cq-two"]',
            },
        },
    )
    error = _render_error("mismatched-kueue", values_file=values)
    assert "must match" in error


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


def test_explicit_otel_toggles_are_removed_without_an_endpoint(tmp_path: Path) -> None:
    """OTLP signal toggles cannot override endpoint-driven activation."""
    values = _write_values(
        tmp_path,
        {
            "clusterName": "test-cluster",
            "kueue": {
                "clusterQueues": ["cq-test"],
                "namespaces": ["metrics-workloads"],
            },
            "env": {
                "METRICS_OTEL__METRICS_ENABLED": "false",
            },
        },
    )
    environment = _environment(_render("disabled-otel-toggles", values_file=values))
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
